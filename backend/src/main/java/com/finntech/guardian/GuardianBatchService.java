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
     * <p><b>판정 대상 날짜는 반드시 지나간 날이어야 한다.</b> 예전에는 {@code targetDate}를
     * 그대로 믿어서, 챌린지를 만든 날 종료일을 넣어 부르면 곧장 ⑥ 최종 정산으로 들어갔다.
     * 그때는 집계 지출이 0이라 달성률이 1.0(설계서 §1: 확보 절약액 = min(지킬 돈,
     * 기준 지출 − 0) = 지킬 돈)이 되어, <b>한 푼도 아끼지 않고 완주 보상 100P를 받았다.</b>
     * 인증이 {@code ?userId=} 뿐이라 누구나 부를 수 있었다.
     *
     * <p>시작 전 날짜도 막는다 — 설계서 §2 "아직 시작 전이면 판정하지 않는다". 예전에는
     * 챌린지를 만든 당일 배치를 돌리면 대상이 전날(= 시작 전)이 되어, 챌린지가 존재하지도
     * 않던 날에 무지출 판정이 나고 사물이 지급됐다.
     *
     * @param targetDate 판정 대상 날짜. null이면 어제(=가상 시계 기준 오늘의 전날).
     *                   미래이거나 종료일 이후면 400.
     */
    @Transactional
    public BatchResult runDaily(Long userId, LocalDate targetDate) {
        LocalDateTime now = clock.now(userId);
        LocalDate yesterday = now.toLocalDate().minusDays(1);
        LocalDate target = targetDate == null ? yesterday : targetDate;

        GuardianChallenge ch = challengeRepository.findRunning(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 챌린지가 없어요"));

        if (target.isAfter(yesterday)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "아직 끝나지 않은 날은 판정할 수 없어요");
        }
        if (target.isAfter(ch.getEndDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "챌린지 기간이 지난 날짜예요");
        }
        // 시작 전이면 판정할 것이 없다. 예외가 아니라 조용히 넘긴다 — 자동 배치가 챌린지를
        // 만든 당일에도 돌기 때문에, 여기서 던지면 정상 흐름이 매번 실패로 보인다.
        if (target.isBefore(ch.getStartDate())) {
            return new BatchResult(null, null, List.of(), List.of(), null);
        }

        List<GuardianNotification> sent = new ArrayList<>();
        List<String> points = new ArrayList<>();

        // ① 되돌리기 유예가 끝난 거래를 확정한다.
        ChallengeState transition = confirmExpiredUndos(ch, now, sent);

        // ② 일 판정 — 같은 날을 두 번 판정하지 않는다(멱등).
        //
        // 한계: 판정이 끝난 날짜에 거래가 **늦게** 도착하면(마이데이터 전송 지연 등) 그 날의
        // 판정은 옛 집계 그대로 남는다. 무지출로 판정돼 사물이 이미 지급됐다면 되돌릴 방법이
        // 없다 — 설계서에 보상 회수 개념이 없기 때문이다. 배치가 '어제'까지만 판정하고
        // 자동 동기화가 5분마다 도는 현재 구성에서는 드물지만, 0은 아니다.
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

        // ⑤ 주간 미션이 없으면 만든다. 정산(일요일)보다 **먼저** 있어야 평가할 대상이 생긴다.
        ensureWeeklyMission(userId, ch, target, now);

        // ⑤-2 주간 정산 — 일요일에 미션과 위기 방어를 확정한다.
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

        if (heldTheLineAllWeek(ch, weekStart, target)
                && rewardService.award(userId, ch.getId(), PointType.RISK_DEFENSE, target, null, now) > 0) {
            awarded.add(PointType.RISK_DEFENSE.name());
        }
        return awarded;
    }

    /**
     * 그 주의 미션을 보장한다.
     *
     * <p><b>이 메서드가 없어서 주간 미션 30P가 영영 지급되지 않았다.</b> 설계서 §9는 미션 내용을
     * "보상 계층이 정한다"는 열린 항목으로 남겨 뒀고, 판정({@link #settleWeek})만 구현돼 있었다.
     * 그래서 {@code new WeeklyMission(...)}을 부르는 곳이 코드베이스 어디에도 없었고,
     * {@code findCurrent}는 언제나 비어 {@code ifPresent}가 조용히 넘어갔다.
     * 주간 상한 100P의 30%가 닫혀 있던 셈이다.
     *
     * <p><b>내용은 챌린지가 이미 가진 값에서만 유도한다</b> — 새 개념을 만들지 않는다.
     * 조건은 이미 판정 코드가 있는 {@code NO_SPEND_STREAK_MIN}을 쓰고, 목표 일수는
     * 설정값이다(원칙 4). 개입 케이스 C5("무지출 3일 연속")와 같은 결을 유지한다.
     * 더 다양한 미션은 보상 계층이 규칙을 정할 때 조건 타입을 바꿔 끼우면 된다.
     */
    private void ensureWeeklyMission(Long userId, GuardianChallenge ch, LocalDate target, LocalDateTime now) {
        LocalDate weekStart = GuardianRewardService.weekStart(target);
        if (missionRepository.findCurrent(userId, weekStart).isPresent()) return;

        int threshold = props.getWeeklyMissionNoSpendDays();
        if (threshold <= 0) return;   // 0이면 주간 미션을 쓰지 않겠다는 뜻

        missionRepository.save(new WeeklyMission(userId, ch.getId(),
                MissionCondition.NO_SPEND_STREAK_MIN, null, threshold,
                weekStart, weekStart.plusDays(6), now));
    }

    /**
     * 설계서 §6의 "위기 방어(AT_RISK로 <b>한 주 버팀</b>)" 판정.
     *
     * <p>예전에는 일요일 <b>그 순간</b>의 상태만 봤다. 그래서 1주차 6일 만에 한도 80%를 태우고
     * 일요일에 AT_RISK로 앉아 있던 사용자는 20P를 받고, 3주 내내 아껴 79%에서 멈춘 사용자는
     * 못 받았다 — 파산 직전에 상을 주고 잘 지킨 사람을 빠뜨리는 정반대 판정이었다.
     *
     * <p>이제 그 주의 일 판정 기록을 본다. ① 한 번이라도 위험 구간에 들어갔고 ② 끝까지
     * 초과로 넘어가지 않았을 때만 "버텼다"로 인정한다. 애초에 위험에 닿지 않은 주는
     * 방어할 것이 없었으므로 대상이 아니다.
     */
    private boolean heldTheLineAllWeek(GuardianChallenge ch, LocalDate weekStart, LocalDate weekEnd) {
        if (ch.getState() == ChallengeState.EXCEEDED) return false;
        List<DailyVerdict> week = verdictRepository.findRange(ch.getId(), weekStart, weekEnd);
        if (week.isEmpty()) return false;
        // 위험 구간을 밟은 적이 있어야 '방어'다. 지출 비율이 위험 임계 이상이었던 날을 찾는다.
        return week.stream().anyMatch(v -> v.getSpentRatio() >= props.getAtRiskRatio());
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
