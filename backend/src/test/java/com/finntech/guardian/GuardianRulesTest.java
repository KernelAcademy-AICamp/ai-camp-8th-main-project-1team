package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 지킴이 규칙 검증 — 설계서 v1.2의 {@code 04_규칙검증.test.ts}를 그대로 옮긴 것이다.
 *
 * <p>부록 A 시연 타임라인 11일치가 그대로 들어 있어, 판정 로직이 어긋나면
 * <b>어느 날짜에서 어긋났는지</b> 바로 나온다. 규칙을 고칠 일이 생기면 여기부터 돌린다.
 *
 * <p>레포지토리·DB 없이 순수 함수만 호출한다 — 화면도 API도 없이 로직을 고정할 수 있어야 한다.
 */
class GuardianRulesTest {

    /** 설계서 부록 A의 배달 챌린지: 기준 250,000 · 지킬 돈 100,000 · 예산 150,000 · 30일 · 버퍼 0.14 */
    private static final GuardianRules.ChallengeView BASE = new GuardianRules.ChallengeView(
            ChallengeState.ACTIVE, Set.of("DELIVERY"), Set.of(),
            250_000L, 100_000L, 150_000L, 0.14, 30, 0L);

    private static final GuardianProperties PROPS = new GuardianProperties();

    private static GuardianRules.ChallengeView spent(long amount) {
        return new GuardianRules.ChallengeView(BASE.state(), BASE.categories(), BASE.sanctuaryCategories(),
                BASE.baselineAmount(), BASE.targetSaving(), BASE.challengeCap(),
                BASE.bufferRatio(), BASE.daysTotal(), amount);
    }

    private static GuardianRules.ChallengeView withState(ChallengeState state, long amount) {
        return new GuardianRules.ChallengeView(state, BASE.categories(), BASE.sanctuaryCategories(),
                BASE.baselineAmount(), BASE.targetSaving(), BASE.challengeCap(),
                BASE.bufferRatio(), BASE.daysTotal(), amount);
    }

    // =====================================================================
    //  buffer_ratio (설계서 §3.3)
    // =====================================================================

    @Test
    @DisplayName("버퍼는 min(0.20, 평균 결제액/예산) — 단가가 큰 카테고리일수록 여유가 넓다")
    void bufferRatio() {
        assertEquals(0.14, GuardianRules.computeBufferRatio(21_000L, 150_000L), 1e-9);   // 배달
        assertEquals(0.084, GuardianRules.computeBufferRatio(5_200L, 62_000L), 1e-9);    // 카페
        // 상한 0.20을 넘지 않는다 — 하루치 여유가 이보다 크면 페이스가 무의미해진다
        assertEquals(0.20, GuardianRules.computeBufferRatio(90_000L, 150_000L), 1e-9);
        // 데이터가 없으면 기본값
        assertEquals(0.15, GuardianRules.computeBufferRatio(null, 150_000L), 1e-9);
        assertEquals(0.15, GuardianRules.computeBufferRatio(21_000L, 0L), 1e-9);
    }

    // =====================================================================
    //  secured_saving 검산 (설계서 §3.3)
    // =====================================================================

    @Test
    @DisplayName("확보한 절약액 = min(지킬 돈, max(0, 기준 지출 − 지출))")
    void securedSaving() {
        assertEquals(100_000L, GuardianRules.computeSnapshot(spent(122_000L), 21).securedSaving());
        assertEquals(100_000L, GuardianRules.computeSnapshot(spent(150_000L), 21).securedSaving());
        assertEquals(70_000L, GuardianRules.computeSnapshot(spent(180_000L), 21).securedSaving());
        // 기준 지출까지 다 써버리면 확보액은 0 — 음수로 내려가지 않는다
        assertEquals(0L, GuardianRules.computeSnapshot(spent(260_000L), 21).securedSaving());
    }

    // =====================================================================
    //  일 판정 — 부록 A 시연 타임라인
    // =====================================================================

    /** {일차, 그날 결제액, 기대 판정} */
    private record Day(int day, long amount, DailyResult expected) {}

    @Test
    @DisplayName("부록 A 시연 타임라인 11일치가 그대로 재현된다")
    void demoTimeline() {
        List<Day> timeline = List.of(
                new Day(2, 0, DailyResult.NO_SPEND_DAY),
                new Day(3, 32_000, DailyResult.ON_PACE_DAY),
                new Day(4, 0, DailyResult.NO_SPEND_DAY),
                new Day(5, 0, DailyResult.NO_SPEND_DAY),
                new Day(6, 0, DailyResult.NO_SPEND_DAY),
                new Day(7, 28_000, DailyResult.OFF_PACE_DAY),
                new Day(8, 35_000, DailyResult.OFF_PACE_DAY),
                new Day(9, 30_000, DailyResult.OFF_PACE_DAY),
                new Day(14, 30_000, DailyResult.OFF_PACE_DAY),
                new Day(16, 0, DailyResult.NO_SPEND_DAY),
                new Day(20, 25_000, DailyResult.OFF_PACE_DAY));

        long cumulative = 0;
        int streak = 0;
        for (Day d : timeline) {
            cumulative += d.amount();
            streak = d.amount() == 0 ? streak + 1 : 0;

            GuardianRules.DailyJudgment j = GuardianRules.dailyJudgment(
                    spent(cumulative), d.day(), cumulative, d.amount() == 0 ? 0 : 1, streak);

            assertEquals(d.expected(), j.result(),
                    "day%d spent=%d ratio=%.3f allowed=%.3f".formatted(
                            d.day(), cumulative, j.snapshot().spentRatio(), j.snapshot().allowedRatio()));
        }
    }

    @Test
    @DisplayName("최종 정산 — 180,000원 지출은 달성률 0.7이라 PARTIAL")
    void finalSettlement() {
        GuardianRules.Snapshot snap = GuardianRules.computeSnapshot(spent(180_000L), 30);
        assertEquals(70_000L, snap.securedSaving());
        assertEquals(0.7, snap.achievementRate(), 1e-9);
        assertEquals(ChallengeState.PARTIAL,
                GuardianRules.settle(snap.achievementRate(), GuardianRules.PARTIAL_UNLOCK_THRESHOLD));
    }

    @Test
    @DisplayName("정산 구간 — 1.0 SUCCESS · 0.70 PARTIAL · 그 미만 SHORTFALL · 0 FAILED")
    void settleBoundaries() {
        double t = GuardianRules.PARTIAL_UNLOCK_THRESHOLD;
        assertEquals(ChallengeState.SUCCESS, GuardianRules.settle(1.0, t));
        assertEquals(ChallengeState.PARTIAL, GuardianRules.settle(0.70, t));
        assertEquals(ChallengeState.SHORTFALL, GuardianRules.settle(0.69, t));
        assertEquals(ChallengeState.FAILED, GuardianRules.settle(0.0, t));
    }

    @Test
    @DisplayName("참는 날은 언제나 보상받는다 — 예산을 초과해도 무지출이면 지급")
    void noSpendAlwaysGrantsEvenWhenExceeded() {
        GuardianRules.DailyJudgment j = GuardianRules.dailyJudgment(
                withState(ChallengeState.EXCEEDED, 180_000L), 20, 180_000L, 0, 3);
        assertEquals(DailyResult.NO_SPEND_DAY, j.result());
        assertTrue(j.grantObject(), "사물은 벌이 아니다 — 초과 상태에서도 무지출은 보상한다");
    }

    @Test
    @DisplayName("정산·종료 상태에서는 판정하지 않는다")
    void nonJudgingStates() {
        for (ChallengeState s : List.of(ChallengeState.SETTLING, ChallengeState.CLOSED,
                ChallengeState.ABANDONED, ChallengeState.SETUP)) {
            GuardianRules.DailyJudgment j = GuardianRules.dailyJudgment(
                    withState(s, 0L), 5, 0L, 0, 5);
            assertEquals(DailyResult.NO_GRANT, j.result(), s + "는 판정 대상이 아니다");
            assertFalse(j.grantObject());
        }
    }

    // =====================================================================
    //  등급 확률
    // =====================================================================

    @Test
    @DisplayName("등급 확률의 합은 언제나 1.0")
    void gradeWeightsSumToOne() {
        List<Map<Grade, Double>> all = List.of(
                GuardianRules.gradeWeights(DailyResult.ON_PACE_DAY, 0),
                GuardianRules.gradeWeights(DailyResult.NO_SPEND_DAY, 1),
                GuardianRules.gradeWeights(DailyResult.NO_SPEND_DAY, 4),
                GuardianRules.gradeWeights(DailyResult.NO_SPEND_DAY, 9));
        for (Map<Grade, Double> w : all) {
            double sum = w.get(Grade.COMMON) + w.get(Grade.RARE) + w.get(Grade.EPIC);
            assertEquals(1.0, sum, 1e-9);
        }
    }

    @Test
    @DisplayName("무지출이 길수록 희귀 확률이 오른다 — 참는 행동에 보상이 붙는다")
    void longerStreakRaisesRarity() {
        double e1 = GuardianRules.gradeWeights(DailyResult.NO_SPEND_DAY, 1).get(Grade.EPIC);
        double e4 = GuardianRules.gradeWeights(DailyResult.NO_SPEND_DAY, 4).get(Grade.EPIC);
        double e9 = GuardianRules.gradeWeights(DailyResult.NO_SPEND_DAY, 9).get(Grade.EPIC);
        assertTrue(e1 < e4 && e4 < e9, "EPIC 확률은 연속일에 따라 단조 증가해야 한다");
        // 페이스만 지킨 날은 무지출보다 희귀도가 낮다
        assertTrue(GuardianRules.gradeWeights(DailyResult.ON_PACE_DAY, 0).get(Grade.EPIC) < e1);
    }

    // =====================================================================
    //  포인트
    // =====================================================================

    @Test
    @DisplayName("주간 상한 100P = 미션 30 + 위기 방어 20 + 라벨링 50(25건)")
    void weeklyCapComposition() {
        GuardianProperties.Point p = PROPS.getPoint();
        assertEquals(100, p.getWeeklyMission() + p.getRiskDefense() + p.getLabeling() * 25);
        assertEquals(p.getWeeklyCap(), p.getWeeklyMission() + p.getRiskDefense() + p.getLabeling() * 25);
    }

    @Test
    @DisplayName("상한 초과분은 잘린다")
    void weeklyCapTruncates() {
        assertEquals(10L, GuardianRules.applyWeeklyCap(30, 90, 100));
        assertEquals(30L, GuardianRules.applyWeeklyCap(30, 0, 100));
        assertEquals(0L, GuardianRules.applyWeeklyCap(30, 100, 100));
        assertEquals(0L, GuardianRules.applyWeeklyCap(30, 120, 100));   // 음수로 내려가지 않는다
    }

    @Test
    @DisplayName("출석 보상은 없다 — 포인트는 절약 행동의 증명이어야 한다")
    void noAttendanceReward() {
        assertEquals(0, PROPS.getPoint().getAttendanceReward());
    }

    // =====================================================================
    //  고정구
    // =====================================================================

    @Test
    @DisplayName("고정구는 반복 금지 대상에서 빠진다 — 빼먹으면 조건부 화법이 무너진다")
    void stripFixedPhrases() {
        assertEquals(List.of("아직 여유 있어요"),
                GuardianRules.stripFixedPhrases(List.of("챌린지에 넣으면", "아직 여유 있어요")));
        assertEquals(List.of(), GuardianRules.stripFixedPhrases(List.of("결제가 들어왔어요")));
        assertEquals(List.of(), GuardianRules.stripFixedPhrases(null));
    }

    // =====================================================================
    //  개입 케이스 매트릭스 (설계서 §4)
    // =====================================================================

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 8, 3, 12, 0);

    private GuardianRules.InterventionDecision decide(GuardianRules.ChallengeView ch, int daysElapsed,
                                                      GuardianRules.TxView tx, int weekly, int total,
                                                      long micro, int pushToday) {
        return GuardianRules.evaluateIntervention(new GuardianRules.InterventionContext(
                ch, GuardianRules.computeSnapshot(ch, daysElapsed), tx,
                weekly, total, micro, pushToday, Map.of(), NOON), PROPS);
    }

    private static GuardianRules.TxView delivery(long amount) {
        return new GuardianRules.TxView("DELIVERY", 0.94, TxType.EXPENSE, amount);
    }

    @Test
    @DisplayName("챌린지 첫 결제 → C1")
    void firstPurchase() {
        assertEquals("C1", decide(spent(32_000L), 3, delivery(32_000L), 0, 1, 0, 0).caseId());
    }

    @Test
    @DisplayName("무관 카테고리 → C4 침묵. 사용자의 다른 소비에는 참견하지 않는다")
    void unrelatedCategoryIsSilent() {
        GuardianRules.InterventionDecision d = decide(BASE, 5,
                new GuardianRules.TxView("CAFE", 0.9, TxType.EXPENSE, 4_800L), 0, 1, 0, 0);
        assertEquals("C4", d.caseId());
        assertTrue(d.silent());
        assertEquals(SuppressedReason.CASE_SILENT, d.reason());
    }

    @Test
    @DisplayName("성역 카테고리 → C4 침묵. 챌린지 대상이어도 건드리지 않는다")
    void sanctuaryIsSilent() {
        GuardianRules.ChallengeView ch = new GuardianRules.ChallengeView(
                ChallengeState.ACTIVE, Set.of("DELIVERY"), Set.of("DELIVERY"),
                250_000L, 100_000L, 150_000L, 0.14, 30, 0L);
        assertTrue(decide(ch, 5, delivery(32_000L), 0, 1, 0, 0).silent());
    }

    @Test
    @DisplayName("예산 80% 도달 → C3")
    void atRiskWarning() {
        assertEquals("C3", decide(spent(125_000L), 9, delivery(30_000L), 0, 1, 0, 0).caseId());
    }

    @Test
    @DisplayName("v1.5 — 초과는 거래 시점에 말하지 않는다. C6는 유예가 지난 뒤 배치가 보낸다")
    void exceededIsNotAnnouncedAtTransactionTime() {
        // 사용률 1.067. v1.2에서는 여기서 바로 C6가 나갔다.
        // 그러면 사용자가 24시간 안에 "챌린지랑 상관없어요"로 되돌려도 이미 초과 통보가 나간 뒤가 된다.
        GuardianRules.InterventionDecision d = decide(spent(160_000L), 12, delivery(30_000L), 0, 1, 0, 0);
        assertNotEquals("C6", d.caseId(), "C6는 배치 전용이 됐다");

        // C3도 아니다 — "80%예요"는 이미 넘긴 사람에게 뒤처진 말이다.
        assertNotEquals("C3", d.caseId());
    }

    @Test
    @DisplayName("v1.5 — 유예가 만료돼 초과가 확정되면 배치가 C6를 고른다")
    void batchAnnouncesC6WhenConfirmed() {
        GuardianRules.Snapshot snap = GuardianRules.computeSnapshot(spent(160_000L), 12);
        assertEquals("C6", GuardianRules.batchCase(snap, ChallengeState.AT_RISK, 0, true, PROPS));
    }

    @Test
    @DisplayName("v1.5 — C6만 알림 예산과 야간 침묵을 무시한다. 초과 사실은 미룰 수 없다")
    void onlyC6BypassesBudgetAndNight() {
        assertTrue(GuardianRules.caseById("C6").bypassBudget());
        assertTrue(GuardianRules.caseById("C6").batchOnly(), "C6는 배치에서만 평가한다");
    }

    @Test
    @DisplayName("이미 초과 확정된 뒤의 추가 결제 → C14 침묵. 계속 잔소리하지 않는다")
    void afterExceededIsSilent() {
        GuardianRules.InterventionDecision d = decide(
                withState(ChallengeState.EXCEEDED, 180_000L), 20, delivery(25_000L), 0, 1, 0, 0);
        assertEquals("C14", d.caseId());
        assertTrue(d.silent());
    }

    @Test
    @DisplayName("알림 예산 소진 → C13 침묵")
    void budgetExhausted() {
        GuardianRules.InterventionDecision d = decide(BASE, 5, delivery(9_000L), 0, 1, 0, 2);
        assertEquals("C13", d.caseId());
        assertEquals(SuppressedReason.BUDGET, d.reason());
    }

    @Test
    @DisplayName("환불 → C12 침묵. 예산 복원은 조용히 한다")
    void refundIsSilent() {
        GuardianRules.InterventionDecision d = decide(spent(32_000L), 5,
                new GuardianRules.TxView("DELIVERY", 0.94, TxType.REFUND, 32_000L), 0, 1, 0, 0);
        assertEquals("C12", d.caseId());
        assertTrue(d.silent());
    }

    @Test
    @DisplayName("미분류 · 신뢰도 미달 → C7. 분류 전에는 판정할 수 없다")
    void unclassifiedAsksBack() {
        assertEquals("C7", decide(BASE, 5,
                new GuardianRules.TxView(null, null, TxType.EXPENSE, 32_000L), 0, 1, 0, 0).caseId());
        assertEquals("C7", decide(BASE, 5,
                new GuardianRules.TxView("DELIVERY", 0.69, TxType.EXPENSE, 32_000L), 0, 1, 0, 0).caseId());
        // 임계값을 넘기면 정상 경로
        assertNotEquals("C7", decide(BASE, 5,
                new GuardianRules.TxView("DELIVERY", 0.70, TxType.EXPENSE, 32_000L), 0, 1, 0, 0).caseId());
    }

    @Test
    @DisplayName("주 3회째 반복 → C2")
    void repeatPattern() {
        assertEquals("C2", decide(spent(60_000L), 7, delivery(20_000L), 3, 3, 0, 0).caseId());
    }

    @Test
    @DisplayName("잔돈 누적이 임계를 넘으면 → C8. 소액 결제마다 울리지 않는다")
    void microBucket() {
        assertEquals("C8", decide(spent(20_000L), 5, delivery(4_000L), 0, 5, 12_000L, 0).caseId());
        // 임계 미만이면 침묵
        assertTrue(decide(spent(20_000L), 5, delivery(4_000L), 0, 5, 11_999L, 0).silent());
    }

    @Test
    @DisplayName("우선순위 — 초과 확정(C14)이 예산 소진(C13)보다 먼저다")
    void priorityOrder() {
        assertEquals("C14", decide(withState(ChallengeState.EXCEEDED, 180_000L), 20,
                delivery(25_000L), 5, 5, 20_000L, 5).caseId());
    }

    // =====================================================================
    //  케이스 정의 무결성
    // =====================================================================

    @Test
    @DisplayName("케이스는 14개, 그중 침묵이 4개")
    void caseMatrixShape() {
        assertEquals(14, GuardianRules.CASES.size());
        assertEquals(4, GuardianRules.CASES.stream().filter(GuardianRules.CaseDef::silent).count());
    }

    @Test
    @DisplayName("TENTATIVE는 되돌릴 수 있는 거래성 케이스 4개뿐")
    void tentativeCases() {
        List<String> tentative = GuardianRules.CASES.stream()
                .filter(c -> c.phrasingMode() == PhrasingMode.TENTATIVE)
                .map(GuardianRules.CaseDef::id).sorted().toList();
        assertEquals(List.of("C1", "C2", "C3", "C8"), tentative);
    }

    @Test
    @DisplayName("침묵 케이스는 톤도 화법도 갖지 않는다 — 말하지 않으니까")
    void silentCasesHaveNoVoice() {
        GuardianRules.CASES.stream().filter(GuardianRules.CaseDef::silent).forEach(c -> {
            assertNull(c.tone(), c.id() + "는 침묵이므로 톤이 없어야 한다");
            assertNull(c.phrasingMode(), c.id() + "는 침묵이므로 화법이 없어야 한다");
        });
    }

    @Test
    @DisplayName("알림 예산을 무시하는 케이스는 C6 하나뿐 — 예산 초과는 미룰 수 없다")
    void onlyC6BypassesBudget() {
        List<String> bypass = GuardianRules.CASES.stream()
                .filter(GuardianRules.CaseDef::bypassBudget)
                .map(GuardianRules.CaseDef::id).toList();
        assertEquals(List.of("C6"), bypass);
    }

    // =====================================================================
    //  쿨다운
    // =====================================================================

    @Test
    @DisplayName("챌린지당 1회 케이스는 한 번 말하면 끝")
    void challengeCooldown() {
        GuardianRules.CaseDef c1 = GuardianRules.caseById("C1");
        assertTrue(GuardianRules.cooldownOk(c1, Map.of(), NOON));
        assertFalse(GuardianRules.cooldownOk(c1, Map.of("C1", List.of(NOON.minusDays(20))), NOON));
    }

    @Test
    @DisplayName("주 1회 · 주 2회 · 일 1회 쿨다운이 창 밖에서는 다시 열린다")
    void windowedCooldowns() {
        GuardianRules.CaseDef week = GuardianRules.caseById("C2");     // week
        GuardianRules.CaseDef week2 = GuardianRules.caseById("C5");    // week2
        GuardianRules.CaseDef day = GuardianRules.caseById("C8");      // day

        assertFalse(GuardianRules.cooldownOk(week, Map.of("C2", List.of(NOON.minusDays(3))), NOON));
        assertTrue(GuardianRules.cooldownOk(week, Map.of("C2", List.of(NOON.minusDays(8))), NOON));

        // week2는 창 안에 2건까지 허용
        assertTrue(GuardianRules.cooldownOk(week2, Map.of("C5", List.of(NOON.minusDays(3))), NOON));
        assertFalse(GuardianRules.cooldownOk(week2,
                Map.of("C5", List.of(NOON.minusDays(3), NOON.minusDays(1))), NOON));

        assertFalse(GuardianRules.cooldownOk(day, Map.of("C8", List.of(NOON.minusHours(5))), NOON));
        assertTrue(GuardianRules.cooldownOk(day, Map.of("C8", List.of(NOON.minusHours(25))), NOON));
    }

    @Test
    @DisplayName("쿨다운에 걸리면 케이스는 유지하되 침묵으로 떨어진다 — 사유가 기록돼야 지표가 산다")
    void cooldownFallsToSilent() {
        GuardianRules.InterventionDecision d = GuardianRules.evaluateIntervention(
                new GuardianRules.InterventionContext(spent(32_000L),
                        GuardianRules.computeSnapshot(spent(32_000L), 3), delivery(32_000L),
                        0, 1, 0, 0, Map.of("C1", List.of(NOON.minusDays(1))), NOON), PROPS);
        assertEquals("C1", d.caseId());
        assertTrue(d.silent());
        assertEquals(SuppressedReason.COOLDOWN, d.reason());
    }

    // =====================================================================
    //  야간 · 상태 전이
    // =====================================================================

    @Test
    @DisplayName("야간(22:00~08:00)은 푸시를 미룬다 — 자정을 넘는 구간도 맞아야 한다")
    void nightWindow() {
        assertTrue(GuardianRules.isNight(LocalDateTime.of(2026, 8, 3, 22, 0), PROPS));
        assertTrue(GuardianRules.isNight(LocalDateTime.of(2026, 8, 3, 23, 59), PROPS));
        assertTrue(GuardianRules.isNight(LocalDateTime.of(2026, 8, 4, 3, 0), PROPS));
        assertTrue(GuardianRules.isNight(LocalDateTime.of(2026, 8, 4, 7, 59), PROPS));
        assertFalse(GuardianRules.isNight(LocalDateTime.of(2026, 8, 4, 8, 0), PROPS));
        assertFalse(GuardianRules.isNight(LocalDateTime.of(2026, 8, 4, 21, 59), PROPS));
    }

    @Test
    @DisplayName("상태 전이 — 0.80에서 AT_RISK, 1.0 초과에서 EXCEEDED, 한번 초과하면 되돌아가지 않는다")
    void stateTransitions() {
        double atRisk = PROPS.getAtRiskRatio();
        assertEquals(ChallengeState.ACTIVE, GuardianRules.nextStateOnSpend(ChallengeState.ACTIVE, 0.79, atRisk));
        assertEquals(ChallengeState.AT_RISK, GuardianRules.nextStateOnSpend(ChallengeState.ACTIVE, 0.80, atRisk));
        assertEquals(ChallengeState.AT_RISK, GuardianRules.nextStateOnSpend(ChallengeState.ACTIVE, 1.00, atRisk));
        assertEquals(ChallengeState.EXCEEDED, GuardianRules.nextStateOnSpend(ChallengeState.AT_RISK, 1.01, atRisk));
        // 되돌리기로 지출이 줄어도 EXCEEDED는 이 함수가 풀지 않는다(호출부가 명시적으로 되돌린다)
        assertEquals(ChallengeState.EXCEEDED, GuardianRules.nextStateOnSpend(ChallengeState.EXCEEDED, 0.10, atRisk));
    }

    @Test
    @DisplayName("월말 실적 구분 — full · partial · missed (연결부 계약 G)")
    void settlementResult() {
        assertEquals("full", GuardianRules.settlementResult(100_000, 115_000, 230_000));
        assertEquals("partial", GuardianRules.settlementResult(140_000, 115_000, 230_000));
        assertEquals("missed", GuardianRules.settlementResult(230_000, 115_000, 230_000));
    }

    // =====================================================================
    //  daysElapsed 기준 (설계서가 두 번 경고하는 함정)
    // =====================================================================

    @Test
    @DisplayName("판정일 기준이 아니라 오늘 기준으로 계산하면 허용선이 하루치만큼 커진다")
    void daysElapsedMustBeVerdictDate() {
        GuardianRules.Snapshot onVerdictDate = GuardianRules.computeSnapshot(spent(60_000L), 7);
        GuardianRules.Snapshot onToday = GuardianRules.computeSnapshot(spent(60_000L), 8);

        double oneDay = 1.0 / 30.0;
        assertEquals(oneDay, onToday.allowedRatio() - onVerdictDate.allowedRatio(), 1e-9);

        // 같은 지출(60,000원)이 기준일 하루 차이로 판정이 뒤집힌다 — 그래서 기준을 못 박아야 한다.
        // 7일차 허용선 0.3733 < 지출비율 0.4 → OFF_PACE (설계서 부록 A와 일치)
        // 8일차 허용선 0.4067 > 0.4          → ON_PACE  (오늘 기준으로 계산했을 때의 잘못된 결과)
        assertEquals(DailyResult.OFF_PACE_DAY,
                GuardianRules.dailyJudgment(spent(60_000L), 7, 60_000L, 1, 0).result());
        assertEquals(DailyResult.ON_PACE_DAY,
                GuardianRules.dailyJudgment(spent(60_000L), 8, 60_000L, 1, 0).result());
    }

    // =====================================================================
    //  v1.5 — 거래 판정 종류 (스펙 §5.1)
    // =====================================================================

    private static GuardianRules.ChallengeView withSanctuary(String... sanctuary) {
        return new GuardianRules.ChallengeView(ChallengeState.ACTIVE, Set.of("DELIVERY"),
                Set.of(sanctuary), 250_000L, 100_000L, 150_000L, 0.14, 30, 0L);
    }

    @Test
    @DisplayName("kind — 줄이기로 한 카테고리만 예산을 깎는다")
    void resolveKindTarget() {
        GuardianRules.TxView tx = new GuardianRules.TxView("DELIVERY", 0.94, TxType.EXPENSE, 32_000L, false);
        assertEquals(TxKind.TARGET, GuardianRules.resolveKind(tx, BASE, 0.70));
        assertTrue(TxKind.TARGET.countsAgainstCap());
    }

    @Test
    @DisplayName("kind — 분류가 확정되지 않으면 UNKNOWN. 무엇인지 모르면 차감할 수 없다")
    void resolveKindUnknown() {
        assertEquals(TxKind.UNKNOWN, GuardianRules.resolveKind(
                new GuardianRules.TxView(null, null, TxType.EXPENSE, 32_000L, false), BASE, 0.70));
        assertEquals(TxKind.UNKNOWN, GuardianRules.resolveKind(
                new GuardianRules.TxView("DELIVERY", 0.69, TxType.EXPENSE, 32_000L, false), BASE, 0.70));
        assertFalse(TxKind.UNKNOWN.countsAgainstCap());
    }

    @Test
    @DisplayName("kind — 고정지출은 예산에서 빼지 않는다. 통신비를 줄이라고 말할 수는 없다")
    void resolveKindFixed() {
        GuardianRules.TxView tx = new GuardianRules.TxView("DELIVERY", 0.94, TxType.EXPENSE, 32_000L, true);
        assertEquals(TxKind.FIXED, GuardianRules.resolveKind(tx, BASE, 0.70));
        assertFalse(TxKind.FIXED.countsAgainstCap());
    }

    @Test
    @DisplayName("kind — 성역이 고정지출보다 먼저다. 겹쳐도 '참견 안 함' 약속이 이긴다")
    void sanctuaryBeatsFixed() {
        GuardianRules.TxView fixedAndSanct =
                new GuardianRules.TxView("DELIVERY", 0.94, TxType.EXPENSE, 32_000L, true);
        assertEquals(TxKind.SANCT, GuardianRules.resolveKind(fixedAndSanct, withSanctuary("DELIVERY"), 0.70));
    }

    @Test
    @DisplayName("kind — 챌린지 대상도 성역도 아니면 NORMAL. 다른 소비에는 참견하지 않는다")
    void resolveKindNormal() {
        GuardianRules.TxView tx = new GuardianRules.TxView("CAFE", 0.95, TxType.EXPENSE, 4_800L, false);
        assertEquals(TxKind.NORMAL, GuardianRules.resolveKind(tx, BASE, 0.70));
        assertFalse(TxKind.NORMAL.countsAgainstCap());
    }

    @Test
    @DisplayName("고정지출 결제는 개입하지 않고 침묵한다")
    void fixedExpenseIsSilent() {
        GuardianRules.InterventionDecision d = decide(BASE, 5,
                new GuardianRules.TxView("DELIVERY", 0.94, TxType.EXPENSE, 55_000L, true), 0, 1, 0, 0);
        assertEquals("C4", d.caseId());
        assertTrue(d.silent());
    }

    @Test
    @DisplayName("대상 거래인데 할 말이 없으면 C4가 아니라 NONE으로 남긴다")
    void nothingToSayIsNotC4() {
        // 대상 카테고리, 사용률 0.4, 주 2회, 총 2건, 잔돈 없음 — 아무 조건도 안 걸린다.
        GuardianRules.InterventionDecision d = decide(spent(60_000L), 7, delivery(20_000L), 2, 2, 0, 0);
        assertEquals(GuardianRules.NO_CASE, d.caseId(), "'참견 안 함'과 '할 말 없음'을 구분해 기록한다");
        assertTrue(d.silent());
    }

    // =====================================================================
    //  v1.5 — 배치 케이스 (스펙 §6.2)
    // =====================================================================

    @Test
    @DisplayName("C5는 3의 배수일 때만. 넷째 날부터 매일 울리면 칭찬이 닳는다")
    void praiseOnlyOnMultiples() {
        GuardianRules.Snapshot mid = GuardianRules.computeSnapshot(spent(30_000L), 10);
        assertNull(GuardianRules.batchCase(mid, ChallengeState.ACTIVE, 2, false, PROPS));
        assertEquals("C5", GuardianRules.batchCase(mid, ChallengeState.ACTIVE, 3, false, PROPS));
        assertNull(GuardianRules.batchCase(mid, ChallengeState.ACTIVE, 4, false, PROPS));
        assertNull(GuardianRules.batchCase(mid, ChallengeState.ACTIVE, 5, false, PROPS));
        assertEquals("C5", GuardianRules.batchCase(mid, ChallengeState.ACTIVE, 6, false, PROPS));
    }

    @Test
    @DisplayName("종료 임박 — 사용률 0.85로 격려(C10)와 사실 통보(C11)를 가른다")
    void endingSoonSplitsAt085() {
        // 28일차 = 남은 3일
        assertEquals("C10", GuardianRules.batchCase(
                GuardianRules.computeSnapshot(spent(120_000L), 28), ChallengeState.ACTIVE, 0, false, PROPS));
        // 사용률 0.867 — 아직 넘기진 않았지만 격려할 자리가 아니다
        assertEquals("C11", GuardianRules.batchCase(
                GuardianRules.computeSnapshot(spent(130_000L), 28), ChallengeState.ACTIVE, 0, false, PROPS));
    }

    @Test
    @DisplayName("C9 — 근거가 없으면 보내지 않는다. '지난 4주 중 3번'을 지어내지 않게")
    void nudgeNeedsEvidence() {
        LocalDateTime friday1830 = LocalDateTime.of(2026, 8, 7, 18, 30);   // 금요일
        // 4주 중 3회 반복 + 19시 슬롯 30분 전 → 보낸다
        assertTrue(GuardianRules.shouldNudgeAhead(DayOfWeek.FRIDAY, 19, 3, friday1830, PROPS));
        // 반복이 부족하면 안 보낸다
        assertFalse(GuardianRules.shouldNudgeAhead(DayOfWeek.FRIDAY, 19, 2, friday1830, PROPS));
        // 요일이 다르면 안 보낸다
        assertFalse(GuardianRules.shouldNudgeAhead(DayOfWeek.THURSDAY, 19, 3, friday1830, PROPS));
        // 너무 이르거나(1시간 전) 이미 시작했으면 안 보낸다
        assertFalse(GuardianRules.shouldNudgeAhead(DayOfWeek.FRIDAY, 19, 3,
                LocalDateTime.of(2026, 8, 7, 18, 0), PROPS));
        assertFalse(GuardianRules.shouldNudgeAhead(DayOfWeek.FRIDAY, 19, 3,
                LocalDateTime.of(2026, 8, 7, 19, 0), PROPS));
    }

    // =====================================================================
    //  v1.5 — 일 판정 합산 (스펙 §5.3)
    // =====================================================================

    private static GuardianRules.DailyJudgment judged(DailyResult r) {
        return new GuardianRules.DailyJudgment(r, r != DailyResult.OFF_PACE_DAY && r != DailyResult.NO_GRANT,
                null, null, new GuardianRules.VerdictSnapshot(0, 0, 0, 0));
    }

    @Test
    @DisplayName("합산 — 한 곳이라도 페이스를 넘기면 지급하지 않는다")
    void anyOffPaceBlocksGrant() {
        GuardianRules.DailyJudgment c = GuardianRules.combineDailyVerdicts(
                List.of(judged(DailyResult.NO_SPEND_DAY), judged(DailyResult.OFF_PACE_DAY)), 3);
        assertEquals(DailyResult.OFF_PACE_DAY, c.result());
        assertFalse(c.grantObject(), "배달을 아꼈다고 카페 초과가 상쇄되면 '지켰다'의 뜻이 흐려진다");
    }

    @Test
    @DisplayName("합산 — 전부 무지출이어야 무지출 날이다. 사물은 하루 1개")
    void allNoSpendGrantsStreakBonus() {
        GuardianRules.DailyJudgment c = GuardianRules.combineDailyVerdicts(
                List.of(judged(DailyResult.NO_SPEND_DAY), judged(DailyResult.NO_SPEND_DAY)), 4);
        assertEquals(DailyResult.NO_SPEND_DAY, c.result());
        assertTrue(c.grantObject());
        assertEquals("NO_SPEND_STREAK_4", c.reasonCode());
        // 챌린지가 2개여도 사물은 하나 — 안 지킬 챌린지를 늘리는 것이 이득이 되면 안 된다
        assertEquals(1.0, c.gradeWeights().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    @DisplayName("합산 — 섞여 있으면 페이스 이내로 본다")
    void mixedIsOnPace() {
        GuardianRules.DailyJudgment c = GuardianRules.combineDailyVerdicts(
                List.of(judged(DailyResult.NO_SPEND_DAY), judged(DailyResult.ON_PACE_DAY)), 2);
        assertEquals(DailyResult.ON_PACE_DAY, c.result());
        assertTrue(c.grantObject());
    }

    @Test
    @DisplayName("합산 — 판정할 챌린지가 없으면 지급하지 않는다")
    void noActiveChallengeNoGrant() {
        assertEquals(DailyResult.NO_GRANT,
                GuardianRules.combineDailyVerdicts(List.of(judged(DailyResult.NO_GRANT)), 0).result());
        assertEquals(DailyResult.NO_GRANT, GuardianRules.combineDailyVerdicts(List.of(), 0).result());
    }

    // =====================================================================
    //  v1.5 — 세리머니 자동 노출 (스펙 §5.3)
    // =====================================================================

    @Test
    @DisplayName("세리머니는 첫 주와 희귀 이상만 자동. 매일 띄우면 연출이 광고처럼 읽힌다")
    void ceremonyAutoOpen() {
        assertTrue(GuardianRules.ceremonyAutoOpen(3, Grade.COMMON, PROPS), "첫 주는 매일");
        assertTrue(GuardianRules.ceremonyAutoOpen(7, Grade.COMMON, PROPS), "7일차까지 포함");
        assertFalse(GuardianRules.ceremonyAutoOpen(8, Grade.COMMON, PROPS), "그 뒤 일반은 뱃지로만");
        assertTrue(GuardianRules.ceremonyAutoOpen(20, Grade.RARE, PROPS));
        assertTrue(GuardianRules.ceremonyAutoOpen(20, Grade.EPIC, PROPS));
        assertFalse(GuardianRules.ceremonyAutoOpen(20, null, PROPS), "못 받은 날은 띄울 것이 없다");
    }

    // =====================================================================
    //  v1.5 — 주간 미션 (스펙 §5.5)
    // =====================================================================

    @Test
    @DisplayName("미션 몫 = 30 나누기 개수. 두 개 한다고 두 배가 되면 수집형이 된다")
    void missionShareSplitsTotal() {
        assertEquals(30, GuardianRules.missionShare(1, PROPS));
        assertEquals(15, GuardianRules.missionShare(2, PROPS));
        assertEquals(10, GuardianRules.missionShare(3, PROPS));
        assertEquals(10, GuardianRules.missionShare(9, PROPS), "최대 3개로 묶는다");
        assertEquals(30, GuardianRules.missionShare(0, PROPS), "0개도 1개로 본다");
    }

    @Test
    @DisplayName("AVOID_SLOT — 지정 요일·시간대에 걸린 건수를 센다")
    void countInSlot() {
        List<GuardianRules.MissionTx> week = List.of(
                new GuardianRules.MissionTx("DELIVERY", DayOfWeek.FRIDAY, 20),   // 걸림
                new GuardianRules.MissionTx("DELIVERY", DayOfWeek.FRIDAY, 18),   // 시간 밖
                new GuardianRules.MissionTx("DELIVERY", DayOfWeek.SATURDAY, 20), // 요일 밖
                new GuardianRules.MissionTx("CAFE", DayOfWeek.FRIDAY, 20));      // 카테고리 밖
        assertEquals(1, GuardianRules.countInSlot(week, "DELIVERY", DayOfWeek.FRIDAY, 19, 22));
        assertEquals(0, GuardianRules.countInSlot(week, "DELIVERY", DayOfWeek.MONDAY, 19, 22));
        // 끝 시각은 제외 — 22시 주문은 19~22 슬롯에 들지 않는다
        assertEquals(0, GuardianRules.countInSlot(
                List.of(new GuardianRules.MissionTx("DELIVERY", DayOfWeek.FRIDAY, 22)),
                "DELIVERY", DayOfWeek.FRIDAY, 19, 22));
    }

    // =====================================================================
    //  v1.5 — 홈 한마디 (스펙 §7.3)
    // =====================================================================

    @Test
    @DisplayName("홈 한마디 우선순위 — C6 > C3 > C11 > C2 > C1 > C5")
    void onelinePriority() {
        assertEquals("C6", GuardianRules.resolveOneline(Map.of("C6", 1.2, "C3", 0.9, "C1", 0.2)));
        assertEquals("C3", GuardianRules.resolveOneline(Map.of("C3", 0.9, "C2", 0.4)));
        assertEquals("C11", GuardianRules.resolveOneline(Map.of("C11", 0.9, "C1", 0.2)));
        assertEquals("C5", GuardianRules.resolveOneline(Map.of("C5", 0.0)));
    }

    @Test
    @DisplayName("홈 한마디 — C3는 이미 넘긴 사람에게 쓰지 않는다. 뒤처진 말이 된다")
    void onelineC3OnlyBelowFull() {
        assertEquals("C2", GuardianRules.resolveOneline(Map.of("C3", 1.05, "C2", 0.5)));
    }

    @Test
    @DisplayName("홈 한마디 — 침묵이어도 자리를 비우지 않는다. 앱을 열었으면 상태는 알아야 한다")
    void onelineNeverEmpty() {
        assertEquals(GuardianRules.ONELINE_IDLE, GuardianRules.resolveOneline(Map.of()));
        assertEquals(GuardianRules.ONELINE_IDLE, GuardianRules.resolveOneline(null));
        // 우선순위에 없는 케이스만 걸려도 IDLE
        assertEquals(GuardianRules.ONELINE_IDLE, GuardianRules.resolveOneline(Map.of("C8", 0.3)));
    }
}
