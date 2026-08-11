package com.finntech.service;

import com.finntech.service.FundFlowInputs.CashBuffer;
import com.finntech.service.FundFlowInputs.FixedExpenseSummary;
import com.finntech.service.FundFlowInputs.IncomeProfile;
import com.finntech.service.FundFlowInputs.LargeExpense;
import com.finntech.service.FundFlowService.FixedCostLevel;
import com.finntech.service.FundFlowService.FundFlowProfile;
import com.finntech.service.FundFlowService.IncomeRegularity;
import com.finntech.service.FundFlowService.LiquidityNeed;
import com.finntech.service.FundFlowService.Stability;
import com.finntech.service.FundFlowService.StabilityLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자금 흐름 5축 분류의 순수 함수만 검증한다(seam·DB 없음). 임계치는 테스트가 명시적으로 주입한다.
 */
class FundFlowServiceTest {

    private static final double THIN = 1.0, THICK = 3.0, LOW = 0.20, HIGH = 0.40;

    /** 테스트용 소스 — 준 입력을 그대로 돌려준다. */
    private static FundFlowService serviceReturning(FundFlowInputs inputs) {
        return new FundFlowService(userId -> inputs, THIN, THICK, LOW, HIGH);
    }

    // ── L1 소득 ──────────────────────────────────────────────
    @Test
    void L1_소득규칙성() {
        assertThat(FundFlowService.classifyIncome(null)).isEqualTo(IncomeRegularity.UNKNOWN);
        assertThat(FundFlowService.classifyIncome(new IncomeProfile(true, 25, 3_000_000)))
                .isEqualTo(IncomeRegularity.REGULAR);
        assertThat(FundFlowService.classifyIncome(new IncomeProfile(false, 0, 0)))
                .isEqualTo(IncomeRegularity.IRREGULAR);
    }

    // ── L2 고정비 ────────────────────────────────────────────
    @Test
    void L2_고정비는_소득대비_비율로_분류되고_소득없으면_UNKNOWN() {
        IncomeProfile inc = new IncomeProfile(true, 25, 1_000_000);
        assertThat(FundFlowService.classifyFixedCost(new FixedExpenseSummary(100_000, null), inc, LOW, HIGH))
                .isEqualTo(FixedCostLevel.LOW);      // 0.10
        assertThat(FundFlowService.classifyFixedCost(new FixedExpenseSummary(300_000, null), inc, LOW, HIGH))
                .isEqualTo(FixedCostLevel.MODERATE); // 0.30
        assertThat(FundFlowService.classifyFixedCost(new FixedExpenseSummary(500_000, null), inc, LOW, HIGH))
                .isEqualTo(FixedCostLevel.HIGH);     // 0.50
        // 소득 재료 없음 → 비율 계산 불가
        assertThat(FundFlowService.classifyFixedCost(new FixedExpenseSummary(300_000, null), null, LOW, HIGH))
                .isEqualTo(FixedCostLevel.UNKNOWN);
    }

    // ── L3 안정성(AoM) ───────────────────────────────────────
    @Test
    void L3_AoM은_평균잔고나누기_월평균지출이고_구간으로_분류된다() {
        assertThat(FundFlowService.aomMonths(new CashBuffer(3_000_000, 1_000_000))).isEqualTo(3.0);
        // THIN(<1) / MODERATE([1,3)) / THICK(>=3)
        assertThat(FundFlowService.classifyStability(new CashBuffer(500_000, 1_000_000), THIN, THICK).level())
                .isEqualTo(StabilityLevel.THIN);
        assertThat(FundFlowService.classifyStability(new CashBuffer(2_000_000, 1_000_000), THIN, THICK).level())
                .isEqualTo(StabilityLevel.MODERATE);
        assertThat(FundFlowService.classifyStability(new CashBuffer(3_000_000, 1_000_000), THIN, THICK).level())
                .isEqualTo(StabilityLevel.THICK);
    }

    @Test
    void L3_월평균지출이_0이면_계산불가_UNKNOWN() {
        Stability s = FundFlowService.classifyStability(new CashBuffer(1_000_000, 0), THIN, THICK);
        assertThat(s.aomMonths()).isNull();
        assertThat(s.level()).isEqualTo(StabilityLevel.UNKNOWN);
        assertThat(FundFlowService.classifyStability(null, THIN, THICK).level()).isEqualTo(StabilityLevel.UNKNOWN);
    }

    // ── L4 유동성 ────────────────────────────────────────────
    @Test
    void L4_큰지출_유무와_예측성으로_분류() {
        assertThat(FundFlowService.classifyLiquidity(null).level()).isEqualTo(LiquidityNeed.UNKNOWN);
        assertThat(FundFlowService.classifyLiquidity(new LargeExpense(null, false)).level())
                .isEqualTo(LiquidityNeed.SMOOTH);
        assertThat(FundFlowService.classifyLiquidity(new LargeExpense(3, true)).level())
                .isEqualTo(LiquidityNeed.PREDICTABLE_LUMPY);
        assertThat(FundFlowService.classifyLiquidity(new LargeExpense(3, false)).level())
                .isEqualTo(LiquidityNeed.UNPREDICTABLE_LUMPY);
    }

    /** 주기(원값)는 구간과 함께 남아야 FP-01의 M2가 `주기 짧음`을 판정할 수 있다. */
    @Test
    void L4_주기_원값이_구간과_함께_남는다() {
        assertThat(FundFlowService.classifyLiquidity(new LargeExpense(3, true)).cycleMonths()).isEqualTo(3);
        // 큰 지출이 없으면(SMOOTH) 주기도 없다 — 0이 아니라 null이다.
        assertThat(FundFlowService.classifyLiquidity(new LargeExpense(null, false)).cycleMonths()).isNull();
        assertThat(FundFlowService.classifyLiquidity(null).cycleMonths()).isNull();
    }

    // ── L5 우대조건 ──────────────────────────────────────────
    @Test
    void L5_재료없으면_known_false_있으면_플래그() {
        assertThat(FundFlowService.preferential(null).known()).isFalse();
        FundFlowService.Preferential p = FundFlowService.preferential(
                new FundFlowInputs.Preferential(true, false));
        assertThat(p.known()).isTrue();
        assertThat(p.cardPerformanceMet()).isTrue();
        assertThat(p.salaryTransferMet()).isFalse();
    }

    // ── 통합 (seam 경유) ─────────────────────────────────────
    @Test
    void 빈_입력이면_모든_축이_UNKNOWN이다_stub_기본상태() {
        FundFlowProfile out = serviceReturning(FundFlowInputs.empty()).analyze(1L);
        assertThat(out.l1Income()).isEqualTo(IncomeRegularity.UNKNOWN);
        assertThat(out.l2FixedCost()).isEqualTo(FixedCostLevel.UNKNOWN);
        assertThat(out.l3Stability().level()).isEqualTo(StabilityLevel.UNKNOWN);
        assertThat(out.l4Liquidity().level()).isEqualTo(LiquidityNeed.UNKNOWN);
        assertThat(out.l5Preferential().known()).isFalse();
    }

    @Test
    void 완전한_입력이면_다섯_축이_모두_분류된다() {
        FundFlowInputs in = new FundFlowInputs(
                new IncomeProfile(true, 25, 3_000_000),      // L1 REGULAR
                new FixedExpenseSummary(300_000, 5),          // L2 0.10 → LOW
                new CashBuffer(9_000_000, 2_000_000),         // L3 aom 4.5 → THICK
                new LargeExpense(6, true),                    // L4 PREDICTABLE_LUMPY
                new FundFlowInputs.Preferential(true, true)); // L5 known
        FundFlowProfile out = serviceReturning(in).analyze(7L);

        assertThat(out.userId()).isEqualTo(7L);
        assertThat(out.l1Income()).isEqualTo(IncomeRegularity.REGULAR);
        assertThat(out.l2FixedCost()).isEqualTo(FixedCostLevel.LOW);
        assertThat(out.l3Stability().aomMonths()).isEqualTo(4.5);
        assertThat(out.l3Stability().level()).isEqualTo(StabilityLevel.THICK);
        assertThat(out.l4Liquidity().level()).isEqualTo(LiquidityNeed.PREDICTABLE_LUMPY);
        assertThat(out.l4Liquidity().cycleMonths()).isEqualTo(6);
        assertThat(out.l5Preferential().known()).isTrue();
    }
}
