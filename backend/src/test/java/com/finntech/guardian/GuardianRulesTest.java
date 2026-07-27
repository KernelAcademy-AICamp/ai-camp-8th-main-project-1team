package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    /** 설계서 부록 A의 배달 챌린지: 기준 250,000 · 지킬 돈 100,000 · 한도 150,000 · 30일 · 버퍼 0.14 */
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
    @DisplayName("버퍼는 min(0.20, 평균 결제액/한도) — 단가가 큰 카테고리일수록 여유가 넓다")
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
    @DisplayName("참는 날은 언제나 보상받는다 — 한도를 초과해도 무지출이면 지급")
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
    @DisplayName("한도 80% 도달 → C3")
    void atRiskWarning() {
        assertEquals("C3", decide(spent(125_000L), 9, delivery(30_000L), 0, 1, 0, 0).caseId());
    }

    @Test
    @DisplayName("한도 초과 → C6")
    void exceededWarning() {
        assertEquals("C6", decide(spent(160_000L), 12, delivery(30_000L), 0, 1, 0, 0).caseId());
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
    @DisplayName("환불 → C12 침묵. 한도 복원은 조용히 한다")
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
    @DisplayName("알림 예산을 무시하는 케이스는 C6 하나뿐 — 한도 초과는 미룰 수 없다")
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
}
