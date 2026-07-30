package com.finntech.service;

import com.finntech.service.FundFlowInputs.CashBuffer;
import com.finntech.service.FundFlowInputs.FixedExpenseSummary;
import com.finntech.service.FundFlowInputs.IncomeProfile;
import com.finntech.service.FundFlowInputs.LargeExpense;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 자금 흐름 축(L1~L5) 분석 (③ 취향·추천) — `13_취향 분석 및 추천_Agent_설계.md` §4.1.
 *
 * <p>재료({@link FundFlowInputs})는 {@link FundFlowSource}(seam)가 실어 오고, 이 서비스는 <b>판정만</b> 한다
 * (판단은 코드가·표현은 AI가, 마스터 §4). 저축 상품 매칭(FP-01, M1~M9)은 이 프로필을 <b>입력</b>으로 받는 별도
 * 단계이며 여기서 하지 않는다(SRP) — 여기 산출물은 "이 사람의 자금 흐름이 어떤 모양인가"까지다.
 *
 * <p><b>임계치는 전부 설정값</b>(설계원칙 4). AoM 버퍼 구간(θ_buffer)은 문서 §8 미정이라 기본값은 잠정이다.
 *
 * <p>재료를 못 받은 축은 {@code UNKNOWN}으로 낸다 — 현재 {@link AccountFundFlowSource}가 L1(소득)·L3(안정성)를
 * 채우고, L2·L4·L5는 재료(①의 (B)) 대기라 UNKNOWN이다. "재료 없음"을 숨기지 않는다.
 */
@Service
public class FundFlowService {

    private final FundFlowSource source;
    /** L3 AoM 버퍼(개월): 이 미만이면 THIN. §8 미정 — 잠정 1.0. */
    private final double aomThinMaxMonths;
    /** L3 AoM 버퍼(개월): 이 이상이면 THICK. §8 미정 — 잠정 3.0. */
    private final double aomThickMinMonths;
    /** L2 고정비/소득 비율이 이 이하이면 LOW. 잠정 0.20. */
    private final double fixedCostLowRatio;
    /** L2 고정비/소득 비율이 이 이상이면 HIGH. 잠정 0.40. */
    private final double fixedCostHighRatio;

    public FundFlowService(
            FundFlowSource source,
            @Value("${finntech.fund-flow.aom-thin-max-months:1.0}") double aomThinMaxMonths,
            @Value("${finntech.fund-flow.aom-thick-min-months:3.0}") double aomThickMinMonths,
            @Value("${finntech.fund-flow.fixed-cost-low-ratio:0.20}") double fixedCostLowRatio,
            @Value("${finntech.fund-flow.fixed-cost-high-ratio:0.40}") double fixedCostHighRatio) {
        this.source = source;
        this.aomThinMaxMonths = aomThinMaxMonths;
        this.aomThickMinMonths = aomThickMinMonths;
        this.fixedCostLowRatio = fixedCostLowRatio;
        this.fixedCostHighRatio = fixedCostHighRatio;
    }

    /** 사용자의 자금 흐름 프로필. 재료는 seam에서 온다. */
    public FundFlowProfile analyze(Long userId) {
        return compute(userId, source.load(userId));
    }

    /** 순수 판정 — 입력만 보고 5축을 분류한다(테스트 진입점). */
    FundFlowProfile compute(Long userId, FundFlowInputs in) {
        IncomeProfile income = in == null ? null : in.incomeProfile();
        return new FundFlowProfile(
                userId,
                classifyIncome(income),
                classifyFixedCost(in == null ? null : in.fixedExpense(), income, fixedCostLowRatio, fixedCostHighRatio),
                classifyStability(in == null ? null : in.cashBuffer(), aomThinMaxMonths, aomThickMinMonths),
                classifyLiquidity(in == null ? null : in.largeExpense()),
                preferential(in == null ? null : in.preferential()));
    }

    // ── 순수 분류기 (설계원칙 4: 임계치는 인자로 주입, 코드에 박지 않음) ──────────────

    /** L1 소득 규칙성. 더미는 급여가 고정이라 사실상 전원 REGULAR이지만(변별력 낮음) 축은 그대로 계산한다. */
    static IncomeRegularity classifyIncome(IncomeProfile p) {
        if (p == null) return IncomeRegularity.UNKNOWN;
        return p.regular() ? IncomeRegularity.REGULAR : IncomeRegularity.IRREGULAR;
    }

    /** L2 고정비 수준 = 월 고정비 ÷ 월 소득. 소득을 모르면(재료 없음/0) 비율을 못 내므로 UNKNOWN. */
    static FixedCostLevel classifyFixedCost(FixedExpenseSummary f, IncomeProfile income,
                                            double lowRatio, double highRatio) {
        if (f == null || income == null || income.monthly() <= 0) return FixedCostLevel.UNKNOWN;
        double ratio = (double) f.total() / income.monthly();
        if (ratio <= lowRatio) return FixedCostLevel.LOW;
        if (ratio >= highRatio) return FixedCostLevel.HIGH;
        return FixedCostLevel.MODERATE;
    }

    /** L3 AoM(개월) = 평균잔고 ÷ 월평균지출. 지출이 0이거나 재료가 없으면 계산 불가(null). */
    static Double aomMonths(CashBuffer c) {
        if (c == null || c.monthlyAvgSpend() <= 0) return null;
        return (double) c.avgBalance() / c.monthlyAvgSpend();
    }

    /** L3 안정성 — AoM 버퍼 구간으로 분류. */
    static Stability classifyStability(CashBuffer c, double thinMax, double thickMin) {
        Double aom = aomMonths(c);
        if (aom == null) return new Stability(null, StabilityLevel.UNKNOWN);
        StabilityLevel level = aom < thinMax ? StabilityLevel.THIN
                : aom >= thickMin ? StabilityLevel.THICK
                : StabilityLevel.MODERATE;
        return new Stability(aom, level);
    }

    /** L4 유동성 필요 — 큰 1회성 지출의 유무·예측성. 큰 지출이 없으면 SMOOTH. */
    static LiquidityNeed classifyLiquidity(LargeExpense l) {
        if (l == null) return LiquidityNeed.UNKNOWN;
        if (l.cycleMonths() == null) return LiquidityNeed.SMOOTH;
        return l.predictable() ? LiquidityNeed.PREDICTABLE_LUMPY : LiquidityNeed.UNPREDICTABLE_LUMPY;
    }

    /** L5 우대조건 충족 재료 — 재료가 없으면 known=false. */
    static Preferential preferential(FundFlowInputs.Preferential p) {
        if (p == null) return new Preferential(false, false, false);
        return new Preferential(p.cardPerformanceMet(), p.salaryTransferMet(), true);
    }

    // ── 산출 타입 ────────────────────────────────────────────────────────────

    public enum IncomeRegularity { REGULAR, IRREGULAR, UNKNOWN }
    public enum FixedCostLevel { LOW, MODERATE, HIGH, UNKNOWN }
    public enum StabilityLevel { THIN, MODERATE, THICK, UNKNOWN }
    public enum LiquidityNeed { SMOOTH, PREDICTABLE_LUMPY, UNPREDICTABLE_LUMPY, UNKNOWN }

    /** L3 산출 — aomMonths=버퍼 개월수(계산 불가면 null), level=구간. */
    public record Stability(Double aomMonths, StabilityLevel level) {}

    /** L5 산출 — 충족 플래그 + known(재료 수신 여부). known=false면 두 플래그는 의미 없음. */
    public record Preferential(boolean cardPerformanceMet, boolean salaryTransferMet, boolean known) {}

    /** 자금 흐름 프로필 — 5축 분류 결과. FP-01(M1~M9) 매칭의 입력이 된다. */
    public record FundFlowProfile(
            Long userId,
            IncomeRegularity l1Income,
            FixedCostLevel l2FixedCost,
            Stability l3Stability,
            LiquidityNeed l4Liquidity,
            Preferential l5Preferential) {}
}
