package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.ChallengeState;
import com.finntech.guardian.domain.GuardianEnums.DailyResult;
import com.finntech.guardian.domain.GuardianEnums.Grade;
import com.finntech.guardian.domain.GuardianEnums.PhrasingMode;
import com.finntech.guardian.domain.GuardianEnums.SuppressedReason;
import com.finntech.guardian.domain.GuardianEnums.Tone;
import com.finntech.guardian.domain.GuardianEnums.TxType;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 지킴이 Agent — 규칙 원본 · 순수 판정 함수 (지킴이 Agent 설계서 v1.2).
 *
 * <p><b>이 클래스의 위치.</b> 임계값·확률·케이스 조건은 <b>DB에 넣지 않는다.</b> 깃으로 버전 관리돼야
 * "누가 언제 왜 바꿨는지"를 추적할 수 있기 때문이다. 튜닝 대상 수치는 {@link GuardianProperties}로
 * 빼서 {@code application.yml}에 두되(마스터 §4 원칙 4), 그것도 결국 깃 안에 있다.
 * 케이스의 <b>구조</b>(우선순위·톤·쿨다운)는 값이 아니라 로직이므로 여기 남는다.
 *
 * <p><b>전부 순수 함수다.</b> DB도 시각도 건드리지 않고 입력을 받아 결과만 돌려주므로,
 * 화면·API 없이 단위 테스트만으로 로직을 고정할 수 있다({@code GuardianRulesTest}).
 * 시각이 필요한 곳은 {@code now}를 인자로 받는다 — 엔진이 {@code now()}를 직접 읽으면
 * 같은 입력이 시간에 따라 다른 출력을 내어 재현성 검증이 불가능해진다(마스터 §4 원칙 3).
 *
 * <p>설계서의 TypeScript 원본을 옮긴 것이며 <b>숫자와 판정 순서는 원본과 1:1</b>이다.
 * 금액은 원 단위 정수이므로 {@code long}을 쓴다(설계서의 {@code integer}).
 */
public final class GuardianRules {

    private GuardianRules() {}

    // =====================================================================
    //  1. 핵심 산수 (설계서 §3.3)
    // =====================================================================

    /** 데이터가 없을 때의 페이스 버퍼. */
    public static final double DEFAULT_BUFFER_RATIO = 0.15;
    /** 버퍼 상한 — 아무리 결제 단가가 커도 하루치 여유가 이보다 커지면 페이스가 무의미해진다. */
    public static final double MAX_BUFFER_RATIO = 0.20;

    /** 단건 이 금액 미만은 잔돈 버킷으로 모은다 (설계서 §3.5). */
    public static final long MICRO_TX_THRESHOLD = 5_000L;
    /** 잔돈 버킷 합계가 이 금액을 넘으면 C8을 하루 1회. */
    public static final long MICRO_BUCKET_TRIGGER = 12_000L;

    public static final int UNDO_WINDOW_HOURS = 24;
    /** 부분 달성으로 인정하는 하한 (설계서 §8) — 5주차 인터뷰로 검증할 가정값. */
    public static final double PARTIAL_UNLOCK_THRESHOLD = 0.70;

    /** 판정에 필요한 챌린지 값만 담은 뷰. JPA 엔티티와 분리해 테스트가 DB 없이 돈다. */
    public record ChallengeView(
            ChallengeState state,
            Set<String> categories,
            Set<String> sanctuaryCategories,
            long baselineAmount,
            long targetSaving,
            /** 챌린지 한도 = 기준 지출 − 지킬 돈 */
            long challengeCap,
            double bufferRatio,
            int daysTotal,
            long spentAmount) {

        public boolean isChallengeCategory(String code) {
            return code != null && categories.contains(code);
        }

        public boolean isSanctuary(String code) {
            return code != null && sanctuaryCategories.contains(code);
        }
    }

    public record Snapshot(
            long spentAmount,
            long remainingCap,
            double spentRatio,
            long securedSaving,
            double achievementRate,
            int daysElapsed,
            int daysLeft,
            double paceRatio,
            double allowedRatio) {}

    /**
     * 스냅샷을 계산한다.
     *
     * <p><b>{@code daysElapsed}는 "판정 대상 날짜 기준"이다.</b> 새벽 배치에서 전날을 판정할 때
     * 오늘 기준으로 계산하면 {@code allowedRatio}가 하루치({@code 1/daysTotal})만큼 커져
     * 설계서의 검산과 어긋난다.
     */
    public static Snapshot computeSnapshot(ChallengeView ch, int daysElapsed) {
        long cap = ch.challengeCap();
        long spent = ch.spentAmount();
        long securedSaving = Math.min(ch.targetSaving(), Math.max(0L, ch.baselineAmount() - spent));
        double paceRatio = ch.daysTotal() > 0 ? (double) daysElapsed / ch.daysTotal() : 0.0;
        return new Snapshot(
                spent,
                cap - spent,
                cap > 0 ? (double) spent / cap : 0.0,
                securedSaving,
                ch.targetSaving() > 0 ? (double) securedSaving / ch.targetSaving() : 0.0,
                daysElapsed,
                Math.max(0, ch.daysTotal() - daysElapsed),
                paceRatio,
                paceRatio + ch.bufferRatio());
    }

    /**
     * 페이스 버퍼 = min(0.20, 평균 결제액 / 한도). 데이터가 없으면 0.15.
     *
     * <p>결제 한 건이 곧바로 페이스를 깨면 안 되므로, 그 사용자의 <b>평소 한 건 크기</b>만큼을
     * 여유로 준다. 배달(단가 큼)은 버퍼가 넓고 카페(단가 작음)는 좁아진다.
     */
    public static double computeBufferRatio(Long avgTransactionAmount, long cap) {
        if (avgTransactionAmount == null || avgTransactionAmount <= 0 || cap <= 0) {
            return DEFAULT_BUFFER_RATIO;
        }
        double raw = (double) avgTransactionAmount / cap;
        return Math.min(MAX_BUFFER_RATIO, Math.round(raw * 1000.0) / 1000.0);
    }

    // =====================================================================
    //  2. 일 판정 (설계서 §3.5) — 마이룸 사물 지급
    // =====================================================================

    public record DailyJudgment(
            DailyResult result,
            boolean grantObject,
            /** 등급 가중치. 미지급이면 null. */
            Map<Grade, Double> gradeWeights,
            String reasonCode,
            VerdictSnapshot snapshot) {}

    /** 판정 당시 스냅샷 — 이게 없으면 "왜 그날 그랬지"를 나중에 답할 수 없다(설계서 §3.5). */
    public record VerdictSnapshot(long spentAtDate, double spentRatio, double paceRatio, double allowedRatio) {}

    /** 판정을 멈추는 상태 — 정산 중이거나 아직 시작 전이면 사물을 주지 않는다. */
    private static final Set<ChallengeState> NON_JUDGING = Set.of(
            ChallengeState.SETTLING, ChallengeState.CLOSED, ChallengeState.ABANDONED, ChallengeState.SETUP);

    /**
     * 하루를 세 갈래로 판정한다.
     * <ul>
     *   <li>안 쓴 날 → 지급 + 희귀 확률 보너스</li>
     *   <li>썼지만 페이스 안 → 지급</li>
     *   <li>페이스보다 앞선 소비 → 미지급</li>
     * </ul>
     *
     * <p>미지급은 지출한 날에만 생기므로, <b>참는 날은 언제나 보상받는다.</b>
     * 한도를 초과한(EXCEEDED) 사용자도 무지출 날이면 계속 받는다 — 사물은 벌이 아니다.
     *
     * @param daysElapsed        판정 대상 날짜 기준 경과일
     * @param spentUntilDate     판정 대상 날짜까지의 누적 집계 지출
     * @param countedTxOnDate    그날 집계된 거래 건수(EXCLUDED·EXEMPTED·성역 제외 후)
     * @param noSpendStreakAfter 이 날을 반영한 뒤의 무지출 연속일
     */
    public static DailyJudgment dailyJudgment(ChallengeView ch, int daysElapsed, long spentUntilDate,
                                              int countedTxOnDate, int noSpendStreakAfter) {
        double paceRatio = ch.daysTotal() > 0 ? (double) daysElapsed / ch.daysTotal() : 0.0;
        double allowedRatio = paceRatio + ch.bufferRatio();
        double spentRatio = ch.challengeCap() > 0 ? (double) spentUntilDate / ch.challengeCap() : 0.0;
        VerdictSnapshot snap = new VerdictSnapshot(spentUntilDate, spentRatio, paceRatio, allowedRatio);

        if (NON_JUDGING.contains(ch.state())) {
            return new DailyJudgment(DailyResult.NO_GRANT, false, null, null, snap);
        }

        if (countedTxOnDate == 0) {
            return new DailyJudgment(DailyResult.NO_SPEND_DAY, true,
                    gradeWeights(DailyResult.NO_SPEND_DAY, noSpendStreakAfter),
                    "NO_SPEND_STREAK_" + noSpendStreakAfter, snap);
        }

        boolean onPace = spentRatio <= allowedRatio;
        return new DailyJudgment(
                onPace ? DailyResult.ON_PACE_DAY : DailyResult.OFF_PACE_DAY,
                onPace,
                onPace ? gradeWeights(DailyResult.ON_PACE_DAY, 0) : null,
                onPace ? "ON_PACE" : null,
                snap);
    }

    /** 등급 확률 — 지킴이가 가중치를 정하고, 추첨 실행은 보상 계층이 한다(설계서 §3.5). */
    public static Map<Grade, Double> gradeWeights(DailyResult result, int noSpendStreak) {
        if (result == DailyResult.ON_PACE_DAY) return weights(0.85, 0.14, 0.01);
        if (noSpendStreak >= 7) return weights(0.50, 0.38, 0.12);
        if (noSpendStreak >= 3) return weights(0.60, 0.32, 0.08);
        return weights(0.70, 0.25, 0.05);
    }

    private static Map<Grade, Double> weights(double common, double rare, double epic) {
        Map<Grade, Double> m = new EnumMap<>(Grade.class);
        m.put(Grade.COMMON, common);
        m.put(Grade.RARE, rare);
        m.put(Grade.EPIC, epic);
        return Map.copyOf(m);
    }

    // =====================================================================
    //  3. 개입 케이스 (설계서 §4) — 위에서부터 평가, 먼저 걸린 하나만
    // =====================================================================

    /** 쿨다운 단위. */
    public enum Cooldown {
        /** 챌린지당 1회 */
        CHALLENGE,
        /** 주 1회 */
        WEEK,
        /** 주 2회 */
        WEEK2,
        /** 일 1회 */
        DAY
    }

    public record CaseDef(
            String id,
            String label,
            boolean silent,
            Tone tone,
            PhrasingMode phrasingMode,
            /** null이면 쿨다운 없음 */
            Cooldown cooldown,
            /** true면 일일 알림 예산과 야간 침묵을 무시한다 */
            boolean bypassBudget,
            /** 거래 순간이 아니라 새벽 배치에서 평가 */
            boolean batchOnly) {}

    private static CaseDef silentCase(String id, String label) {
        return new CaseDef(id, label, true, null, null, null, false, false);
    }

    private static CaseDef speakCase(String id, String label, Tone tone, PhrasingMode mode,
                                     Cooldown cd, boolean bypassBudget, boolean batchOnly) {
        return new CaseDef(id, label, false, tone, mode, cd, bypassBudget, batchOnly);
    }

    /** 평가 순서가 곧 우선순위다. 위에서부터 보고 먼저 걸린 하나만 실행한다. */
    public static final List<CaseDef> CASES = List.of(
            silentCase("C14", "초과 확정 후 추가 결제"),
            silentCase("C13", "오늘 알림 예산 소진"),
            silentCase("C12", "환불로 한도 복원"),
            speakCase("C7", "카테고리 미분류", Tone.NEUTRAL_ASK, PhrasingMode.DEFINITIVE, null, false, false),
            speakCase("C6", "한도 초과 확정", Tone.FACT_RESET, PhrasingMode.DEFINITIVE, Cooldown.CHALLENGE, true, false),
            speakCase("C3", "한도 80% 임박", Tone.REWARD_WARNING, PhrasingMode.TENTATIVE, Cooldown.CHALLENGE, false, false),
            speakCase("C11", "종료 임박 + 초과 상태", Tone.FACT_RESET, PhrasingMode.DEFINITIVE, Cooldown.DAY, false, true),
            speakCase("C2", "주 3회째 반복 결제", Tone.PATTERN_HINT, PhrasingMode.TENTATIVE, Cooldown.WEEK, false, false),
            speakCase("C1", "챌린지 첫 결제", Tone.SOFT_REMINDER, PhrasingMode.TENTATIVE, Cooldown.CHALLENGE, false, false),
            speakCase("C8", "잔돈 결제 누적", Tone.SOFT_REMINDER, PhrasingMode.TENTATIVE, Cooldown.DAY, false, false),
            speakCase("C9", "위험 시간대 사전 넛지", Tone.NUDGE_AHEAD, PhrasingMode.DEFINITIVE, Cooldown.WEEK, false, true),
            speakCase("C5", "무지출 3일 연속", Tone.PRAISE, PhrasingMode.DEFINITIVE, Cooldown.WEEK2, false, true),
            speakCase("C10", "종료 임박 + 지킬 돈 유지", Tone.PRAISE, PhrasingMode.DEFINITIVE, Cooldown.DAY, false, true),
            silentCase("C4", "무관 카테고리 지출"));

    /** 시각 기반 1건 — 주간 회고(매트릭스 밖). */
    public static final CaseDef W1 =
            speakCase("W1", "주간 회고", Tone.WEEKLY_RECAP, PhrasingMode.DEFINITIVE, Cooldown.WEEK, false, true);

    /** 아침 세리머니 — 푸시가 아니라 앱을 열면 뜨는 모달. 알림 예산을 쓰지 않는다. */
    public static final CaseDef M1 =
            speakCase("M1", "아침 세리머니", Tone.MORNING_CEREMONY, PhrasingMode.DEFINITIVE, null, false, false);

    private static final Map<String, CaseDef> CASE_BY_ID = buildCaseIndex();

    private static Map<String, CaseDef> buildCaseIndex() {
        Map<String, CaseDef> m = new LinkedHashMap<>();
        for (CaseDef c : CASES) m.put(c.id(), c);
        m.put(W1.id(), W1);
        m.put(M1.id(), M1);
        return Map.copyOf(m);
    }

    public static CaseDef caseById(String id) {
        CaseDef def = CASE_BY_ID.get(id);
        if (def == null) throw new IllegalArgumentException("unknown case: " + id);
        return def;
    }

    /** 거래 한 건의 판정 재료. */
    public record TxView(String category, Double categoryConfidence, TxType txType, long amount) {}

    public record InterventionContext(
            ChallengeView challenge,
            Snapshot snap,
            /** 배치 평가면 null */
            TxView tx,
            /** 이번 주 해당 카테고리 집계 결제 건수 */
            int weeklyCountForCategory,
            /** 챌린지 전체 해당 카테고리 집계 결제 건수 */
            int countedCountForCategory,
            /** 오늘 잔돈 버킷 합계 */
            long dailyMicroBucket,
            /** 오늘 PUSH로 나간 알림 수 */
            int pushSentToday,
            /** case_id → 최근 발송 시각들 */
            Map<String, List<LocalDateTime>> caseSentAt,
            /** 쿨다운 계산 기준 시각 (재현성 — 엔진이 now()를 직접 읽지 않는다) */
            LocalDateTime now) {}

    public record InterventionDecision(
            String caseId,
            boolean silent,
            SuppressedReason reason,
            Tone tone,
            PhrasingMode phrasingMode) {

        static InterventionDecision silent(String caseId, SuppressedReason reason) {
            return new InterventionDecision(caseId, true, reason, null, null);
        }

        static InterventionDecision speak(CaseDef def) {
            return new InterventionDecision(def.id(), false, null, def.tone(), def.phrasingMode());
        }
    }

    /**
     * 거래 순간의 개입 판정. {@code batchOnly} 케이스(C5·C9·C10·C11·W1)는 여기서 평가하지 않는다.
     *
     * <p>분류 신뢰도가 임계 미만이면 <b>집계하지 않고</b> C7 질문만 보낸다 — 분류 전에는 판정할 수 없다.
     */
    public static InterventionDecision evaluateIntervention(InterventionContext ctx, GuardianProperties props) {
        ChallengeView ch = ctx.challenge();
        Snapshot snap = ctx.snap();
        TxView tx = ctx.tx();

        if (ch.state() == ChallengeState.EXCEEDED) {
            return InterventionDecision.silent("C14", SuppressedReason.CASE_SILENT);
        }
        if (ctx.pushSentToday() >= props.getNotification().getDailyPushLimit()) {
            return InterventionDecision.silent("C13", SuppressedReason.BUDGET);
        }
        if (tx != null && tx.txType() == TxType.REFUND) {
            return InterventionDecision.silent("C12", SuppressedReason.CASE_SILENT);
        }

        if (tx != null && (tx.category() == null
                || orZero(tx.categoryConfidence()) < props.getCategoryConfidenceThreshold())) {
            return decide("C7", ctx);
        }

        if (tx != null && ch.isSanctuary(tx.category())) {
            return InterventionDecision.silent("C4", SuppressedReason.CASE_SILENT);
        }
        if (tx != null && tx.category() != null && !ch.isChallengeCategory(tx.category())) {
            return InterventionDecision.silent("C4", SuppressedReason.CASE_SILENT);
        }

        if (snap.spentRatio() > 1.0) return decide("C6", ctx);
        if (snap.spentRatio() >= props.getAtRiskRatio()) return decide("C3", ctx);
        if (ctx.weeklyCountForCategory() >= props.getRepeatWeeklyCount()) return decide("C2", ctx);
        if (ctx.countedCountForCategory() == 1) return decide("C1", ctx);
        if (ctx.dailyMicroBucket() >= props.getMicroBucketTrigger()) return decide("C8", ctx);

        return InterventionDecision.silent("C4", SuppressedReason.CASE_SILENT);
    }

    private static InterventionDecision decide(String id, InterventionContext ctx) {
        CaseDef def = caseById(id);
        if (!cooldownOk(def, ctx.caseSentAt(), ctx.now())) {
            return InterventionDecision.silent(id, SuppressedReason.COOLDOWN);
        }
        return InterventionDecision.speak(def);
    }

    private static double orZero(Double v) { return v == null ? 0.0 : v; }

    /** 이 케이스를 지금 또 보내도 되는가. */
    public static boolean cooldownOk(CaseDef def, Map<String, List<LocalDateTime>> sentAt, LocalDateTime now) {
        Cooldown cd = def.cooldown();
        if (cd == null) return true;
        List<LocalDateTime> list = sentAt == null ? List.of() : sentAt.getOrDefault(def.id(), List.of());
        if (list.isEmpty()) return true;
        if (cd == Cooldown.CHALLENGE) return false;

        long hours = switch (cd) {
            case DAY -> 24L;
            case WEEK, WEEK2 -> 24L * 7L;
            case CHALLENGE -> 0L;
        };
        LocalDateTime since = now.minusHours(hours);
        long within = list.stream().filter(t -> t.isAfter(since)).count();
        return cd == Cooldown.WEEK2 ? within < 2 : within == 0;
    }

    // =====================================================================
    //  4. 알림 예산 (설계서 §4.1)
    // =====================================================================

    /**
     * 조건부 화법의 고정구.
     * 매 알림에서 써야 하는 표현이라 <b>반복 금지 대상에서 빼야</b> 한다.
     * 이걸 빼먹으면 며칠 뒤 지킴이가 단정형으로 되돌아가고, 원인 추적이 어렵다.
     */
    public static final List<String> FIXED_PHRASES = List.of(
            "결제가 들어왔어요",
            "챌린지에 넣으면",
            "이 결제까지 넣으면",
            "들어온 결제가",
            "챌린지랑 상관없어요",
            "알려줘서 고마워요");

    /** 로그 적재 전 고정구를 걸러낸다 — 반복 감지가 고정구에 걸려 오작동하지 않게. */
    public static List<String> stripFixedPhrases(List<String> keyPhrases) {
        if (keyPhrases == null) return List.of();
        return keyPhrases.stream()
                .filter(p -> p != null && FIXED_PHRASES.stream().noneMatch(p::contains))
                .toList();
    }

    /** 야간(기본 22:00~08:00)이면 푸시를 미룬다. */
    public static boolean isNight(LocalDateTime at, GuardianProperties props) {
        int h = at.getHour();
        int start = props.getNotification().getNightStartHour();
        int end = props.getNotification().getNightEndHour();
        return start <= end ? (h >= start && h < end) : (h >= start || h < end);
    }

    // =====================================================================
    //  5. 포인트 (설계서 §3.7)
    // =====================================================================

    /** 주간 상한을 적용한 실제 적립분. 상한을 넘긴 만큼은 잘린다. */
    public static long applyWeeklyCap(long amount, long alreadyEarnedThisWeek, long weeklyCap) {
        return Math.max(0L, Math.min(amount, weeklyCap - alreadyEarnedThisWeek));
    }

    // =====================================================================
    //  6. 상태 전이 (설계서 §8)
    // =====================================================================

    /**
     * 결제 반영 뒤의 챌린지 상태.
     * <p>초과(EXCEEDED)는 <b>되돌리기 유예가 만료된 뒤에만</b> 확정한다 — 거래 순간에 이 함수로
     * EXCEEDED를 만들면, 24시간 안에 "내 소비 아님"으로 되돌렸을 때 이미 초과 알림이 나간 뒤가 된다.
     */
    public static ChallengeState nextStateOnSpend(ChallengeState current, double spentRatio, double atRiskRatio) {
        if (current == ChallengeState.EXCEEDED) return ChallengeState.EXCEEDED;
        if (spentRatio > 1.0) return ChallengeState.EXCEEDED;
        if (spentRatio >= atRiskRatio) return ChallengeState.AT_RISK;
        return ChallengeState.ACTIVE;
    }

    /** 최종 정산 — 달성률로 결과 상태를 정한다. */
    public static ChallengeState settle(double achievementRate, double partialThreshold) {
        if (achievementRate >= 1.0) return ChallengeState.SUCCESS;
        if (achievementRate >= partialThreshold) return ChallengeState.PARTIAL;
        if (achievementRate > 0) return ChallengeState.SHORTFALL;
        return ChallengeState.FAILED;
    }

    /** 월말 실적 구분 — 연결부 계약 (G)의 {@code result}. */
    public static String settlementResult(long accumulatedSpend, long spendLimit, long baselineTotal) {
        if (accumulatedSpend <= spendLimit) return "full";
        if (accumulatedSpend < baselineTotal) return "partial";
        return "missed";
    }
}
