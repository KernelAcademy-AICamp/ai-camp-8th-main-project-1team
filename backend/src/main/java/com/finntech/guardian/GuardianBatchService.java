package com.finntech.guardian;

import com.finntech.guardian.domain.*;
import com.finntech.guardian.domain.GuardianEnums.*;
import com.finntech.guardian.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 새벽 배치 (설계서 §API 4) — 하루를 마감하고 다음 날 아침을 준비한다.
 *
 * <p>순서가 고정이다: ① 되돌리기 유예 만료 확정 ② 일 판정 ③ 사물 지급·세리머니 문구
 * ④ 시간 기반 케이스 ⑤ 주간 정산 ⑥ 종료일이면 최종 정산.
 *
 * <p><b>{@code daysElapsed}는 오늘이 아니라 판정 대상 날짜 기준이다.</b> 오늘 기준으로 계산하면
 * {@code allowedRatio}가 하루치만큼 커져 설계서의 검산과 어긋난다.
 *
 * <p><b>세리머니 문구는 여기서 미리 만들어 저장한다.</b> 사용자가 앱을 열 때 꺼내 보여주므로,
 * 새벽에 전 사용자 분량이 한꺼번에 생성돼 LLM 호출이 몰린다 — 폴백이 특히 중요한 구간이다.
 */
@Service
public class GuardianBatchService {

    private final GuardianChallengeRepository challengeRepository;
    private final GuardianTransactionRepository txRepository;
    private final DailyVerdictRepository verdictRepository;
    private final WeeklyMissionRepository missionRepository;
    private final GuardianRewardService rewardService;
    private final GuardianNarrative narrative;
    private final GuardianService guardianService;
    private final GuardianClock clock;
    private final GuardianProperties props;

    public GuardianBatchService(GuardianChallengeRepository challengeRepository,
                                GuardianTransactionRepository txRepository,
                                DailyVerdictRepository verdictRepository,
                                WeeklyMissionRepository missionRepository,
                                GuardianRewardService rewardService,
                                GuardianNarrative narrative,
                                GuardianService guardianService,
                                GuardianClock clock,
                                GuardianProperties props) {
        this.challengeRepository = challengeRepository;
        this.txRepository = txRepository;
        this.verdictRepository = verdictRepository;
        this.missionRepository = missionRepository;
        this.rewardService = rewardService;
        this.narrative = narrative;
        this.guardianService = guardianService;
        this.clock = clock;
        this.props = props;
    }

    public record BatchResult(DailyVerdict verdict, GuardianRewardService.Granted granted,
                              List<GuardianNotification> notifications, List<String> pointEvents,
                              ChallengeState stateTransition) {}

    /**
     * 하루치 배치를 돌린다.
     *
     * @param targetDate 판정 대상 날짜. null이면 어제(=가상 시계 기준 오늘의 전날).
     */
    @Transactional
    public BatchResult runDaily(Long userId, LocalDate targetDate) {
        LocalDateTime now = clock.now(userId);
        LocalDate target = targetDate == null ? now.toLocalDate().minusDays(1) : targetDate;

        GuardianChallenge ch = challengeRepository.findRunning(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 챌린지가 없어요"));

        List<GuardianNotification> sent = new ArrayList<>();
        List<String> points = new ArrayList<>();

        // ① 되돌리기 유예가 끝난 거래를 확정한다.
        ChallengeState transition = confirmExpiredUndos(ch, now, sent);

        // ② 일 판정 — 같은 날을 두 번 판정하지 않는다(멱등).
        Optional<DailyVerdict> existing = verdictRepository.findByChallengeIdAndVerdictDate(ch.getId(), target);
        if (existing.isPresent()) {
            return new BatchResult(existing.get(), null, sent, points, transition);
        }

        int countedOnDate = txRepository.findCountedOn(ch.getId(), target).size();
        long spentUntil = txRepository.sumCountedUntil(ch.getId(), target);
        int streakAfter = countedOnDate == 0 ? ch.getNoSpendStreak() + 1 : 0;

        GuardianRules.DailyJudgment j = GuardianRules.dailyJudgment(
                guardianService.viewOf(ch), ch.daysElapsedOn(target), spentUntil, countedOnDate, streakAfter);

        ch.setNoSpendStreak(streakAfter);
        ch.setGrassStreak(applyGrassGuard(userId, ch, j, streakAfter, now));

        DailyVerdict verdict = verdictRepository.save(new DailyVerdict(
                userId, ch.getId(), target, j.result(), j.grantObject(), j.gradeWeights(), j.reasonCode(),
                j.snapshot().spentAtDate(), j.snapshot().spentRatio(),
                j.snapshot().paceRatio(), j.snapshot().allowedRatio(), streakAfter, now));

        // ③ 사물 지급 + 세리머니 문구를 미리 만들어 둔다.
        GuardianRewardService.Granted granted = null;
        if (j.grantObject()) {
            granted = rewardService.grantObject(userId, ch.getId(), target,
                    j.gradeWeights(), j.reasonCode(), 0, now).orElse(null);
            if (granted != null) {
                verdict.grant(granted.objectId(), granted.grade());
                verdict.setCeremonyMessage(ceremonyMessage(granted));
                verdictRepository.save(verdict);
            }
        }

        // ④ 시간 기반 케이스.
        evaluateBatchCases(ch, target, now, sent);

        // ⑤ 주간 정산 — 일요일에 미션과 위기 방어를 확정한다.
        if (target.getDayOfWeek() == DayOfWeek.SUNDAY) {
            points.addAll(settleWeek(userId, ch, target, now));
        }

        // ⑥ 종료일이 지났으면 최종 정산.
        if (target.isAfter(ch.getEndDate()) || target.isEqual(ch.getEndDate())) {
            transition = settle(ch, target, now, points);
        }

        challengeRepository.save(ch);
        return new BatchResult(verdict, granted, sent, points, transition);
    }

    // ======================================================================
    //  ① 되돌리기 만료 확정
    // ======================================================================

    /**
     * 유예가 끝난 거래로 한도를 넘겼으면 EXCEEDED로 넘기고 C6를 1회 보낸다.
     * <b>이 케이스만 알림 예산과 야간 침묵을 무시한다</b> — 한도를 넘긴 사실은 미룰 수 없다.
     */
    private ChallengeState confirmExpiredUndos(GuardianChallenge ch, LocalDateTime now,
                                               List<GuardianNotification> sent) {
        List<GuardianTransaction> expired = txRepository.findExpiredUndo(ch.getId(), now);
        if (expired.isEmpty()) return null;

        double ratio = ch.getChallengeCap() > 0
                ? (double) ch.getSpentAmount() / ch.getChallengeCap() : 0.0;
        if (ratio <= 1.0 || ch.getState() == ChallengeState.EXCEEDED) return null;

        ch.setState(ChallengeState.EXCEEDED);
        GuardianRules.Snapshot snap = guardianService.snapshotOf(ch, now.toLocalDate());
        GuardianRules.CaseDef c6 = GuardianRules.caseById("C6");
        if (GuardianRules.cooldownOk(c6, guardianService.caseSentAt(ch.getId()), now)) {
            sent.add(guardianService.deliver(ch, null,
                    new GuardianRules.InterventionDecision("C6", false, null, c6.tone(), c6.phrasingMode()),
                    snap, now.toLocalDate(), now));
        }
        return ChallengeState.EXCEEDED;
    }

    // ======================================================================
    //  ④ 시간 기반 케이스 (C5 · C10 · C11)
    // ======================================================================

    /**
     * 배치에서만 평가하는 케이스. C9(위험 시간대 사전 넛지)는 시간대 패턴 분석이 필요해
     * 이번 범위에서 제외했다 — 근거 없이 보내면 "지난 4주 중 몇 번"을 지어내게 된다.
     */
    private void evaluateBatchCases(GuardianChallenge ch, LocalDate target, LocalDateTime now,
                                    List<GuardianNotification> sent) {
        GuardianRules.Snapshot snap = guardianService.snapshotOf(ch, target);
        Map<String, List<LocalDateTime>> sentAt = guardianService.caseSentAt(ch.getId());

        // C5 — 무지출이 사흘 이상 이어졌다.
        if (ch.getNoSpendStreak() >= 3) {
            fire("C5", ch, snap, sentAt, target, now, sent);
        }

        // C10 / C11 — 종료가 임박했다. 한도를 넘겼으면 사실 통보(C11), 아니면 격려(C10).
        if (snap.daysLeft() > 0 && snap.daysLeft() <= 3) {
            fire(snap.spentRatio() > 1.0 ? "C11" : "C10", ch, snap, sentAt, target, now, sent);
        }
    }

    private void fire(String caseId, GuardianChallenge ch, GuardianRules.Snapshot snap,
                      Map<String, List<LocalDateTime>> sentAt, LocalDate target, LocalDateTime now,
                      List<GuardianNotification> sent) {
        GuardianRules.CaseDef def = GuardianRules.caseById(caseId);
        GuardianRules.InterventionDecision decision =
                GuardianRules.cooldownOk(def, sentAt, now)
                        ? new GuardianRules.InterventionDecision(caseId, false, null, def.tone(), def.phrasingMode())
                        : new GuardianRules.InterventionDecision(caseId, true, SuppressedReason.COOLDOWN, null, null);
        sent.add(guardianService.deliver(ch, null, decision, snap, target, now));
    }

    // ======================================================================
    //  ⑤ 주간 정산
    // ======================================================================

    /**
     * 주간 미션 달성과 위기 방어를 확정한다.
     *
     * <p><b>위기 방어</b> = 한도의 80%를 넘긴 상태(AT_RISK)로 한 주를 버텼다. 초과하지 않고
     * 견딘 주는 아무 사건도 안 일어난 것처럼 보이므로, 여기서 명시적으로 인정한다.
     */
    private List<String> settleWeek(Long userId, GuardianChallenge ch, LocalDate target, LocalDateTime now) {
        List<String> awarded = new ArrayList<>();
        LocalDate weekStart = GuardianRewardService.weekStart(target);

        missionRepository.findCurrent(userId, weekStart).ifPresent(m -> {
            int current = switch (m.getConditionType()) {
                case CATEGORY_COUNT_MAX -> txRepository.countCountedByCategoryInRange(
                        ch.getId(), m.getCategory(), m.getPeriodStart(), m.getPeriodEnd());
                case NO_SPEND_STREAK_MIN -> ch.getNoSpendStreakBest();
                case LABELING_COUNT_MIN -> 0;
            };
            boolean ok = m.satisfiedBy(current);
            m.evaluate(ok, now);
            missionRepository.save(m);
            if (ok && rewardService.award(userId, ch.getId(), PointType.WEEKLY_MISSION, target, m.getId(), now) > 0) {
                awarded.add(PointType.WEEKLY_MISSION.name());
            }
        });

        if (ch.getState() == ChallengeState.AT_RISK
                && rewardService.award(userId, ch.getId(), PointType.RISK_DEFENSE, target, null, now) > 0) {
            awarded.add(PointType.RISK_DEFENSE.name());
        }
        return awarded;
    }

    // ======================================================================
    //  ⑥ 최종 정산
    // ======================================================================

    private ChallengeState settle(GuardianChallenge ch, LocalDate target, LocalDateTime now, List<String> points) {
        GuardianRules.Snapshot snap = guardianService.snapshotOf(ch, target);
        ChallengeState result = GuardianRules.settle(snap.achievementRate(), props.getPartialUnlockThreshold());
        ch.setState(result);
        ch.setSettledAt(now);
        if (result == ChallengeState.SUCCESS || result == ChallengeState.PARTIAL) {
            if (rewardService.award(ch.getUserId(), ch.getId(), PointType.MONTHLY_COMPLETE, target, null, now) > 0) {
                points.add(PointType.MONTHLY_COMPLETE.name());
            }
        }
        return result;
    }

    // ======================================================================
    //  세리머니 · 잔디
    // ======================================================================

    /**
     * 잔디 연속일. 미지급 날이라도 보호권이 있으면(자동 사용 설정 시) 표시상 연속을 유지한다.
     * <b>실제 무지출 연속일({@code noSpendStreak})은 건드리지 않는다</b> — 보호권으로 확률 보너스까지
     * 사면 참는 행동의 의미가 사라진다.
     */
    private int applyGrassGuard(Long userId, GuardianChallenge ch, GuardianRules.DailyJudgment j,
                                int streakAfter, LocalDateTime now) {
        if (j.grantObject()) return ch.getGrassStreak() + 1;

        GuardianItems items = rewardService.items(userId, now);
        if (items.isAutoUseGrassGuard() && items.useGrassGuard(now)) {
            ch.setGrassProtectedDays(ch.getGrassProtectedDays() + 1);
            return ch.getGrassStreak() + 1;
        }
        return 0;
    }

    /** 아침 세리머니 문구 — 푸시가 아니라 앱을 열면 뜨는 모달에 쓴다. */
    private String ceremonyMessage(GuardianRewardService.Granted granted) {
        Map<String, Object> v = new TreeMap<>();
        v.put("objectId", granted.objectId());
        return narrative.compose("M1", Tone.MORNING_CEREMONY, PhrasingMode.DEFINITIVE,
                v, List.of(), false).body();
    }
}
