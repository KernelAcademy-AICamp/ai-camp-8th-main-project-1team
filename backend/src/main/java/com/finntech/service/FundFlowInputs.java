package com.finntech.service;

/**
 * 자금 흐름 축(L1~L5)의 입력 재료 — ①의 (B) 스키마와 1:1로 대응하는 <b>계약</b>이다
 * (`12_리포트_라이프스타일_설계.md` §4.1).
 *
 * <p><b>왜 계약을 따로 두나(seam).</b> 5축 재료는 전부 계좌·소득을 봐야 알 수 있어 R7에 따라 ①이 (B)로
 * 넘겨야 한다 — ③은 계좌 원본을 직접 만지지 않는다. 지금은 ①의 (B)가 아직 구현 전이라 {@link FundFlowSource}가
 * 이 계약을 stub으로 채운다. ①(B)가 나오거나(정공법) 잔액만 account에서 끌어오기로 합의되면(B안), 소스 구현만
 * 갈아끼우고 이 계약과 {@link FundFlowService}는 그대로 둔다. 의존을 구현이 아니라 계약에 두는 것(DIP).
 *
 * <p>필드는 {@code (B)}의 material 이름을 그대로 따른다. 값을 아직 못 받는 축은 해당 서브레코드를 {@code null}로
 * 두면 그 축은 {@code UNKNOWN}으로 분류된다({@link FundFlowService}).
 */
public record FundFlowInputs(
        IncomeProfile incomeProfile,       // L1 ← (B) income_profile
        FixedExpenseSummary fixedExpense,  // L2 ← (B) fixed_expense_summary
        CashBuffer cashBuffer,             // L3 ← (B) cash_buffer   ← 잔액 seam이 채우는 자리
        LargeExpense largeExpense,         // L4 ← (B) recurring_payments + 큰 1회성 지출
        Preferential preferential          // L5 ← (B) preferential_material ×(D)
) {

    /** L1. regular=급여 규칙성, day=급여일(1~28), monthly=월 급여액(원). */
    public record IncomeProfile(boolean regular, int day, long monthly) {}

    /** L2. total=월 고정비 합(원), withdrawalDaySpread=인출일 분산(일, 없으면 null). 고정비는 카드에서 탐지(R7). */
    public record FixedExpenseSummary(long total, Integer withdrawalDaySpread) {}

    /**
     * L3. avgBalance=평균잔고(원, 계좌 스냅샷), monthlyAvgSpend=월평균지출(원, 카드=소비 단일 출처).
     * AoM(Age of Money)=avgBalance÷monthlyAvgSpend. 두 값 모두 소비 이중 계산과 무관하다(잔액은 스냅샷, 지출은 카드).
     */
    public record CashBuffer(long avgBalance, long monthlyAvgSpend) {}

    /** L4. cycleMonths=큰 1회성 지출 주기(개월, 없으면 null), predictable=예측 가능 여부. */
    public record LargeExpense(Integer cycleMonths, boolean predictable) {}

    /**
     * L5. 우대조건 충족 재료 — 카드 실적·급여이체 둘만 본다(자동이체는 더미에 거래가 없어 제외, R7).
     * 실제 상품별 우대조건과의 대조(M1~M9)는 FP-01 매칭에서 (D)와 함께 판정한다.
     */
    public record Preferential(boolean cardPerformanceMet, boolean salaryTransferMet) {}

    /** 소스 미연결(또는 재료 없음) — 모든 축이 UNKNOWN으로 나오게 하는 빈 입력. */
    public static FundFlowInputs empty() {
        return new FundFlowInputs(null, null, null, null, null);
    }
}
