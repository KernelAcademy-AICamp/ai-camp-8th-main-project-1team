package com.finntech.guardian;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.engine.AnalysisEngine;
import com.finntech.engine.AnalysisResult;
import com.finntech.guardian.domain.*;
import com.finntech.guardian.domain.GuardianEnums.*;
import com.finntech.guardian.repository.*;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 지킴이 Agent — 챌린지 원장과 거래 개입 (지킴이 Agent 설계서 v1.2).
 *
 * <p><b>낙관적 판정(설계서 D1-A).</b> 거래가 들어오면 즉시 집계하고 24시간 되돌리기를 준다.
 * "확인받고 차감"은 사용자가 매번 확인 버튼을 눌러야 해서 알림 피로가 커진다.
 *
 * <p><b>판단은 규칙이, 표현은 AI가(마스터 §4 원칙 1).</b> 이 클래스는 원장을 움직이고
 * {@link GuardianRules}에 판정을 물어볼 뿐이며, 문장은 {@link GuardianNarrative}가 맨 마지막에 만든다.
 *
 * <p><b>시각은 반드시 {@link GuardianClock}으로.</b> {@code LocalDateTime.now()}를 직접 부르면
 * 데모의 "다음 날로 이동"이 동작하지 않고 재현성도 깨진다(마스터 §4 원칙 3).
 */
@Service
public class GuardianService {

    private final GuardianChallengeRepository challengeRepository;
    private final GuardianTransactionRepository txRepository;
    private final GuardianNotificationRepository notificationRepository;
    private final DailyVerdictRepository verdictRepository;
    private final GuardianRewardService rewardService;
    private final GuardianNarrative narrative;
    private final GuardianClock clock;
    private final GuardianProperties props;
    private final AnalysisEngine analysisEngine;
    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;

    public GuardianService(GuardianChallengeRepository challengeRepository,
                           GuardianTransactionRepository txRepository,
                           GuardianNotificationRepository notificationRepository,
                           DailyVerdictRepository verdictRepository,
                           GuardianRewardService rewardService,
                           GuardianNarrative narrative,
                           GuardianClock clock,
                           GuardianProperties props,
                           AnalysisEngine analysisEngine,
                           ConsumptionRepository consumptionRepository,
                           CategoryRepository categoryRepository) {
        this.challengeRepository = challengeRepository;
        this.txRepository = txRepository;
        this.notificationRepository = notificationRepository;
        this.verdictRepository = verdictRepository;
        this.rewardService = rewardService;
        this.narrative = narrative;
        this.clock = clock;
        this.props = props;
        this.analysisEngine = analysisEngine;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
    }

    // ======================================================================
    //  1. 챌린지 시작
    // ======================================================================

    /**
     * 챌린지를 시작한다. 기준 지출과 평균 결제액은 <b>기존 분석 결과에서 파생</b>한다 —
     * 서비스가 임계치를 다시 계산하지 않는다(마스터 §4 원칙 2).
     *
     * @param categories   줄이기로 한 카테고리 코드
     * @param targetSaving 지킬 돈. 기준 지출보다 작아야 한다.
     */
    @Transactional
    public GuardianChallenge createChallenge(Long userId, List<String> categories, List<String> sanctuary,
                                             Long targetSaving, String rewardName, Long rewardPrice,
                                             Integer durationDays) {
        if (categories == null || categories.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "줄일 카테고리를 하나 이상 골라주세요");
        }
        if (challengeRepository.findRunning(userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 진행 중인 챌린지가 있어요");
        }

        LocalDateTime now = clock.now(userId);
        Baseline baseline = baselineFor(userId, categories, now);
        if (baseline.monthlyAmount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "이 카테고리의 소비 이력이 없어 기준 지출을 잡을 수 없어요");
        }
        long target = targetSaving == null ? baseline.monthlyAmount() / 3 : targetSaving;
        if (target <= 0 || target >= baseline.monthlyAmount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지킬 돈은 0보다 크고 기준 지출(" + GuardianCopy.won(baseline.monthlyAmount()) + "원)보다 작아야 해요");
        }

        long cap = baseline.monthlyAmount() - target;
        double bufferRatio = GuardianRules.computeBufferRatio(baseline.avgTransactionAmount(), cap);
        LocalDate start = now.toLocalDate();
        int days = durationDays == null ? props.getDefaultDurationDays() : durationDays;

        GuardianChallenge ch = new GuardianChallenge(userId, categories, sanctuary,
                baseline.monthlyAmount(), target, bufferRatio,
                start, start.plusDays(days - 1L), rewardName, rewardPrice, now);
        rewardService.items(userId, now);   // 보유 아이템 레코드를 미리 만들어 둔다
        return challengeRepository.save(ch);
    }

    /** 기준 지출(카테고리 월평균 합)과 평균 결제액. */
    record Baseline(long monthlyAmount, Long avgTransactionAmount) {}

    /**
     * 기존 {@link AnalysisResult}에서 기준선을 파생한다.
     * 관측 개월수는 분석이 이미 센 {@code monthlySpend}의 키 수를 그대로 쓴다.
     */
    Baseline baselineFor(Long userId, List<String> categories, LocalDateTime now) {
        AnalysisResult analysis = analysisEngine.analyze(userId, now);
        int months = Math.max(1, analysis.monthlySpend().size());

        long monthly = 0L;
        long total = 0L;
        long count = 0L;
        for (String code : categories) {
            AnalysisResult.CategoryStat stat = analysis.categoryStats().get(code);
            if (stat == null) continue;
            long amount = stat.totalAmount().setScale(0, RoundingMode.HALF_UP).longValue();
            monthly += amount / months;
            total += amount;
            count += stat.count();
        }
        return new Baseline(monthly, count > 0 ? total / count : null);
    }

    // ======================================================================
    //  2. 거래 수신 (설계서 §API 1)
    // ======================================================================

    public record IngestCommand(LocalDateTime occurredAt, String merchantName, String merchantDisplayName,
                                long amount, String mcc, String category, Double categoryConfidence,
                                TxType txType, boolean demo, Long sourceConsumptionId) {}

    public record IngestResult(GuardianTransaction transaction, GuardianRules.Snapshot snapshot,
                               ChallengeState state, GuardianNotification notification) {}

    /**
     * 거래 한 건을 원장에 넣고 개입 여부를 판정한다.
     *
     * <p>순서가 고정이다: ① 환불 복원 ② 분류 미확정 보류 ③ 성역·무관 제외 ④ 낙관적 집계
     * ⑤ 개입 판정 ⑥ 문장. 분류 신뢰도가 임계 미만이면 <b>집계하지 않고</b> 되묻기만 한다 —
     * 분류 전에는 판정할 수 없다.
     */
    @Transactional
    public IngestResult ingest(Long userId, IngestCommand cmd) {
        LocalDateTime now = clock.now(userId);
        LocalDate today = now.toLocalDate();
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElse(null);

        boolean micro = cmd.amount() < props.getMicroTxThreshold();
        GuardianTransaction tx = new GuardianTransaction(userId, ch == null ? null : ch.getId(),
                cmd.occurredAt() == null ? now : cmd.occurredAt(), now,
                cmd.merchantName(), cmd.merchantDisplayName(), cmd.amount(), cmd.mcc(),
                cmd.category(), cmd.categoryConfidence(), cmd.txType(), micro, cmd.demo());
        tx.setSourceConsumptionId(cmd.sourceConsumptionId());

        // 챌린지가 없거나 정산 단계면 원장만 남기고 조용히 끝낸다.
        if (ch == null || !ch.isRunning()) {
            tx.exclude();
            txRepository.save(tx);
            return new IngestResult(tx, null, ch == null ? null : ch.getState(), null);
        }

        boolean counted = classify(ch, tx, today, now);
        if (counted) {
            ch.setSpentAmount(ch.getSpentAmount() + tx.getAmount());
            // 초과 확정은 배치의 몫이다 — 거래 순간에 EXCEEDED로 넘기면 24시간 안에
            // "챌린지랑 상관없어요"로 되돌렸을 때 이미 초과 알림이 나간 뒤가 된다.
            ChallengeState next = GuardianRules.nextStateOnSpend(
                    ch.getState(), ratio(ch), props.getAtRiskRatio());
            ch.setState(next == ChallengeState.EXCEEDED ? ChallengeState.AT_RISK : next);
        }
        txRepository.save(tx);
        challengeRepository.save(ch);

        GuardianRules.Snapshot snap = snapshotOf(ch, today);
        GuardianRules.InterventionDecision decision = GuardianRules.evaluateIntervention(
                context(ch, snap, tx, today, now), props);
        GuardianNotification noti = deliver(ch, tx, decision, snap, today, now);

        return new IngestResult(tx, snap, ch.getState(), noti);
    }

    /**
     * 거래를 분류해 상태를 세운다. 집계했으면 true.
     *
     * <p>{@link GuardianRules#evaluateIntervention}의 앞쪽 분기와 같은 조건을 본다.
     * 판정 함수는 순수하게(DB 접근 없이) 두어야 단위 테스트가 가능하므로, 원장을 실제로
     * 움직이는 쪽은 여기서 따로 처리한다.
     */
    private boolean classify(GuardianChallenge ch, GuardianTransaction tx, LocalDate today, LocalDateTime now) {
        if (tx.getTxType() == TxType.REFUND) {
            restoreRefund(ch, tx);
            return false;
        }
        // 분류가 확정되지 않았으면 보류. 나중에 분류가 붙으면 그때 집계한다.
        if (tx.getCategory() == null
                || orZero(tx.getCategoryConfidence()) < props.getCategoryConfidenceThreshold()) {
            return false;
        }
        if (ch.getSanctuarySet().contains(tx.getCategory())
                || !ch.getCategorySet().contains(tx.getCategory())) {
            tx.exclude();
            return false;
        }
        tx.count(today, now.plusHours(props.getUndoWindowHours()));
        return true;
    }

    /** 환불 — 원 거래를 찾아 한도를 조용히 복원한다(C12). 알림은 만들지 않는다. */
    private void restoreRefund(GuardianChallenge ch, GuardianTransaction refund) {
        refund.exclude();
        txRepository.findByChallenge(ch.getId()).stream()
                .filter(GuardianTransaction::isCounted)
                .filter(t -> t.getAmount() == refund.getAmount()
                        && Objects.equals(t.getCategory(), refund.getCategory()))
                .reduce((first, second) -> second)   // 가장 최근 것
                .ifPresent(original -> {
                    original.exclude();
                    refund.setOriginalTxId(original.getId());
                    ch.setSpentAmount(ch.getSpentAmount() - original.getAmount());
                    txRepository.save(original);
                });
    }

    // ======================================================================
    //  3. 되돌리기 (설계서 §API 2)
    // ======================================================================

    public record UndoResult(GuardianTransaction transaction, GuardianRules.Snapshot snapshot,
                             ChallengeState state, String toast, GuardianItems items) {}

    /**
     * 되돌리기. 유예가 지났으면 거절한다.
     *
     * <p>결과로 알림을 만들지 않는다 — 화면 숫자만 조용히 갱신한다. 되돌린 것까지 알림이 오면
     * 사용자는 자기가 한 행동을 통보받는 셈이 된다.
     */
    @Transactional
    public UndoResult undo(Long userId, Long transactionId, UndoReason reason) {
        LocalDateTime now = clock.now(userId);
        GuardianTransaction tx = txRepository.findById(transactionId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "거래를 찾을 수 없어요"));

        if (!tx.isUndoable(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, GuardianCopy.UNDO_EXPIRED);
        }

        GuardianItems items = rewardService.items(userId, now);
        if (reason == UndoReason.EXEMPTION && !items.useExemption(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "면제권이 없어요");
        }

        GuardianChallenge ch = challengeRepository.findById(tx.getChallengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없어요"));

        tx.undo(reason, now);
        ch.setSpentAmount(ch.getSpentAmount() - tx.getAmount());
        // 이 거래 때문에 일어난 상태 전이를 취소한다.
        ch.setState(GuardianRules.nextStateOnSpend(
                ch.getState() == ChallengeState.EXCEEDED ? ChallengeState.ACTIVE : ch.getState(),
                ratio(ch), props.getAtRiskRatio()));

        txRepository.save(tx);
        challengeRepository.save(ch);

        GuardianRules.Snapshot snap = snapshotOf(ch, now.toLocalDate());
        return new UndoResult(tx, snap, ch.getState(),
                GuardianCopy.undoToast(snap.remainingCap()), items);
    }

    /** 늦게 붙은 분류를 반영한다 — PENDING_CATEGORY를 풀고 집계까지 이어간다(라벨링 포인트 대상). */
    @Transactional
    public IngestResult classifyPending(Long userId, Long transactionId, String category, Double confidence) {
        LocalDateTime now = clock.now(userId);
        LocalDate today = now.toLocalDate();
        GuardianTransaction tx = txRepository.findById(transactionId)
                .filter(t -> t.getUserId().equals(userId))
                .filter(t -> t.getState() == TxState.PENDING_CATEGORY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "분류 대기 중인 거래가 아니에요"));

        GuardianChallenge ch = challengeRepository.findById(tx.getChallengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없어요"));

        tx.assignCategory(category, confidence == null ? 1.0 : confidence);
        if (classify(ch, tx, today, now)) {
            ch.setSpentAmount(ch.getSpentAmount() + tx.getAmount());
            ChallengeState next = GuardianRules.nextStateOnSpend(ch.getState(), ratio(ch), props.getAtRiskRatio());
            ch.setState(next == ChallengeState.EXCEEDED ? ChallengeState.AT_RISK : next);
        }
        txRepository.save(tx);
        challengeRepository.save(ch);

        rewardService.award(userId, ch.getId(), PointType.LABELING, today, tx.getId(), now);
        return new IngestResult(tx, snapshotOf(ch, today), ch.getState(), null);
    }

    // ======================================================================
    //  4. 마이데이터 브리지
    // ======================================================================

    /**
     * 마이데이터 투영({@code Consumption(MYDATA)})에서 아직 원장에 안 들어온 결제를 끌어온다.
     *
     * <p>{@code MyDataLinkService}를 고치지 않고 <b>당겨오는</b> 방식을 택했다 — 연동 서비스에
     * 지킴이 호출을 심으면 리포트·점수·FDS와 얽힌 경로에 지킴이 장애가 번진다.
     *
     * @return 새로 적재한 건수
     */
    @Transactional
    public int syncFromMyData(Long userId) {
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElse(null);
        if (ch == null) return 0;

        LocalDateTime from = ch.getStartDate().atStartOfDay();
        LocalDateTime to = ch.getEndDate().plusDays(1).atStartOfDay();
        int added = 0;
        for (Consumption c : consumptionRepository.findInRange(userId, from, to)) {
            if (txRepository.existsByUserIdAndSourceConsumptionId(userId, c.getId())) continue;
            Category cat = c.getCategory();
            ingest(userId, new IngestCommand(
                    c.getOccurredAt(), cat.getDisplayName(), cat.getDisplayName(),
                    c.getAmount().setScale(0, RoundingMode.HALF_UP).longValue(),
                    null, cat.getCode(), 1.0, TxType.EXPENSE, false, c.getId()));
            added++;
        }
        return added;
    }

    // ======================================================================
    //  5. 알림 전달
    // ======================================================================

    /** 결정을 실제 알림으로 만든다. 침묵이면 사유와 함께 로그만 남긴다. */
    GuardianNotification deliver(GuardianChallenge ch, GuardianTransaction tx,
                                 GuardianRules.InterventionDecision decision,
                                 GuardianRules.Snapshot snap, LocalDate today, LocalDateTime now) {
        Long txId = tx == null ? null : tx.getId();
        if (decision.silent()) {
            return notificationRepository.save(GuardianNotification.silent(
                    ch.getUserId(), ch.getId(), txId, decision.caseId(), decision.reason(), now));
        }

        GuardianRules.CaseDef def = GuardianRules.caseById(decision.caseId());
        // 야간에는 미루되, 한도 초과 통보(C6)처럼 미룰 수 없는 건은 예외다.
        if (!def.bypassBudget() && GuardianRules.isNight(now, props)) {
            return notificationRepository.save(GuardianNotification.silent(
                    ch.getUserId(), ch.getId(), txId, decision.caseId(), SuppressedReason.NIGHT, now));
        }

        Map<String, Object> numbers = numbersFor(ch, tx, snap, today);
        GuardianNarrative.Message msg = narrative.compose(
                decision.caseId(), decision.tone(), decision.phrasingMode(),
                numbers, recentKeyPhrases(ch.getId(), now), false);

        return notificationRepository.save(GuardianNotification.spoken(
                ch.getUserId(), ch.getId(), txId, decision.caseId(),
                decision.tone(), decision.phrasingMode(), DeliveryKind.PUSH,
                msg.title(), msg.body(), GuardianRules.stripFixedPhrases(msg.keyPhrases()),
                msg.fallback(), GuardianCopy.PROMPT_VERSION, now));
    }

    /** 문장에 넣을 값 — 전부 이미 계산이 끝난 것이다. LLM은 여기 있는 것만 쓴다. */
    private Map<String, Object> numbersFor(GuardianChallenge ch, GuardianTransaction tx,
                                           GuardianRules.Snapshot snap, LocalDate today) {
        Map<String, Object> v = new TreeMap<>();
        v.put("remaining", Math.max(0L, snap.remainingCap()));
        v.put("cap", ch.getChallengeCap());
        v.put("secured", snap.securedSaving());
        v.put("daysLeft", snap.daysLeft());
        v.put("days", ch.getNoSpendStreak());
        if (tx != null) {
            v.put("amount", tx.getAmount());
            v.put("category", categoryLabel(tx.getCategory()));
            v.put("count", txRepository.countCountedByCategory(ch.getId(), tx.getCategory()));
            v.put("total", txRepository.sumMicroOnDate(ch.getId(), today));
        }
        return v;
    }

    /** 카테고리 코드 → 사람이 읽는 이름. 코드에 카테고리 이름을 박지 않는다(마스터 §4 원칙 4). */
    String categoryLabel(String code) {
        if (code == null) return "";
        return categoryRepository.findByCode(code).map(Category::getDisplayName).orElse(code);
    }

    /** 최근 쓴 특징 표현 — 지킴이가 같은 말을 반복하지 않게 한다. */
    List<String> recentKeyPhrases(Long challengeId, LocalDateTime now) {
        List<String> out = new ArrayList<>();
        for (GuardianNotification n : notificationRepository.findSpokenSince(challengeId, now.minusDays(7))) {
            out.addAll(n.getKeyPhraseList());
            if (out.size() >= 12) break;
        }
        return out;
    }

    /** 케이스별 최근 발송 시각 — 쿨다운 판정의 재료. 말한 것만 센다. */
    Map<String, List<LocalDateTime>> caseSentAt(Long challengeId) {
        Map<String, List<LocalDateTime>> m = new TreeMap<>();
        for (GuardianNotification n : notificationRepository.findAllSpoken(challengeId)) {
            m.computeIfAbsent(n.getCaseId(), k -> new ArrayList<>()).add(n.getSentAt());
        }
        return m;
    }

    // ======================================================================
    //  6. 스냅샷 · 컨텍스트
    // ======================================================================

    /** 판정 함수에 넘길 챌린지 뷰. */
    GuardianRules.ChallengeView viewOf(GuardianChallenge ch) {
        return new GuardianRules.ChallengeView(ch.getState(), ch.getCategorySet(), ch.getSanctuarySet(),
                ch.getBaselineAmount(), ch.getTargetSaving(), ch.getChallengeCap(),
                ch.getBufferRatio(), ch.getDaysTotal(), ch.getSpentAmount());
    }

    public GuardianRules.Snapshot snapshotOf(GuardianChallenge ch, LocalDate onDate) {
        return GuardianRules.computeSnapshot(viewOf(ch), ch.daysElapsedOn(onDate));
    }

    private GuardianRules.InterventionContext context(GuardianChallenge ch, GuardianRules.Snapshot snap,
                                                      GuardianTransaction tx, LocalDate today, LocalDateTime now) {
        GuardianRules.TxView txView = tx == null ? null : new GuardianRules.TxView(
                tx.getCategory(), tx.getCategoryConfidence(), tx.getTxType(), tx.getAmount());

        String category = tx == null ? null : tx.getCategory();
        int weekly = category == null ? 0 : txRepository.countCountedByCategoryInRange(
                ch.getId(), category, today.minusDays(6), today);
        int total = category == null ? 0 : txRepository.countCountedByCategory(ch.getId(), category);

        LocalDateTime dayStart = today.atStartOfDay();
        int pushToday = notificationRepository.countPushToday(ch.getUserId(), dayStart, dayStart.plusDays(1));

        return new GuardianRules.InterventionContext(viewOf(ch), snap, txView, weekly, total,
                txRepository.sumMicroOnDate(ch.getId(), today), pushToday, caseSentAt(ch.getId()), now);
    }

    private double ratio(GuardianChallenge ch) {
        return ch.getChallengeCap() > 0 ? (double) ch.getSpentAmount() / ch.getChallengeCap() : 0.0;
    }

    private static double orZero(Double v) { return v == null ? 0.0 : v; }

    // ======================================================================
    //  7. 홈 (설계서 §API 3) — 프론트는 다시 계산하지 않는다
    // ======================================================================

    public record GrassCell(LocalDate date, DailyResult result, boolean granted, boolean protectedDay) {}

    public record CeremonyView(LocalDate verdictDate, DailyResult result, String objectId,
                               Grade grade, String message, boolean rerollAvailable) {}

    public record HomeView(LocalDateTime asOf, GuardianChallenge challenge, String categoryLabel,
                           GuardianRules.Snapshot snapshot, int pendingCount, String pendingBadge,
                           CeremonyView ceremony, List<GrassCell> grass, GuardianItems items,
                           int unreadNotifications, boolean demoMode) {}

    /** 홈 한 방 — 프론트가 그릴 값을 전부 계산해 내려준다. */
    @Transactional
    public HomeView home(Long userId) {
        LocalDateTime now = clock.now(userId);
        LocalDate today = now.toLocalDate();
        GuardianChallenge ch = challengeRepository.findRunning(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 챌린지가 없어요"));

        GuardianRules.Snapshot snap = snapshotOf(ch, today);
        int pending = txRepository.findPendingCategory(ch.getId()).size();

        List<GrassCell> grass = new ArrayList<>();
        for (DailyVerdict v : verdictRepository.findSince(ch.getId(), today.minusDays(29))) {
            grass.add(new GrassCell(v.getVerdictDate(), v.getResult(), v.isGrantObject(), false));
        }

        CeremonyView ceremony = verdictRepository.findUnseenCeremonies(userId).stream().findFirst()
                .map(v -> new CeremonyView(v.getVerdictDate(), v.getResult(), v.getGrantedObjectId(),
                        v.getGrantedGrade(), v.getCeremonyMessage(), !v.isRerolled()))
                .orElse(null);

        String label = ch.getCategorySet().stream()
                .map(this::categoryLabel).reduce((a, b) -> a + "·" + b).orElse("");

        return new HomeView(now, ch, label, snap, pending,
                pending > 0 ? GuardianCopy.pendingBadge(pending) : null,
                ceremony, grass, rewardService.items(userId, now),
                notificationRepository.countUnread(userId), clock.isDemoMode(userId));
    }

    /** 세리머니를 열었다 — 이 시각이 기록돼야 홈의 미개봉 뱃지가 꺼진다. */
    @Transactional
    public void markCeremonySeen(Long userId, Long verdictId) {
        DailyVerdict v = verdictRepository.findById(verdictId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "판정을 찾을 수 없어요"));
        v.setCeremonySeenAt(clock.now(userId));
        verdictRepository.save(v);
    }

    // ======================================================================
    //  8. 알림 목록 · 피드백 (설계서 §API 5)
    // ======================================================================

    /** 침묵 기록은 빼고 내려준다 — 지표 계산용이지 사용자에게 보일 것이 아니다. */
    public List<GuardianNotification> notifications(Long userId) {
        return notificationRepository.findVisible(userId);
    }

    /** 별점보다 이 태그가 중요하다 — 프롬프트를 어느 방향으로 고칠지는 사유가 정한다. */
    @Transactional
    public void feedback(Long userId, Long notificationId, Feedback feedback, FeedbackReason reason) {
        GuardianNotification n = notificationRepository.findById(notificationId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없어요"));
        n.recordFeedback(feedback, reason, clock.now(userId));
        notificationRepository.save(n);
    }
}
