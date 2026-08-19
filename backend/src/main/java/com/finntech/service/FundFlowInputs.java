package com.finntech.service;

import java.util.Set;

/**
 * 자금 흐름 축(L1~L5)의 입력 재료 — ①의 (B) 스키마와 1:1로 대응하는 <b>계약</b>이다
 * (`07_취향분석및추천_Agent_설계.md` §4.1).
 *
 * <p><b>왜 계약을 따로 두나(seam).</b> 5축 재료는 전부 계좌·소득을 봐야 알 수 있어 R7에 따라 ①이 (B)로
 * 넘겨야 한다 — ③은 계좌 원본을 직접 만지지 않는다. 현재는 {@link FundFlowSource}의 구현
 * {@link AccountFundFlowSource}(B안)가 잔액·급여를 마이데이터 계좌에서 직접 읽어 L1·L3를 채우고, L2·L4·L5는
 * ①의 (B) 대기라 {@code null}(=UNKNOWN)이다. ①(B)가 나오면 소스 구현만 갈아끼우고 이 계약과
 * {@link FundFlowService}는 그대로 둔다. 의존을 구현이 아니라 계약에 두는 것(DIP).
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
     * 실제 상품별 우대조건과의 대조(M1~M10)는 FP-01 매칭에서 (D)와 함께 판정한다.
     *
     * <p><b>금융사별로 쪼개서 함께 준다</b>(2026-08-11 신설). 실측상 우대조건의 28%가
     * `우리은행 입출식 계좌에서…`처럼 <b>당행 한정</b>이라, 앞의 두 불리언(어느 금융사든 하나라도 충족)만으로
     * 판정하면 과대 표시가 된다. 뒤의 두 집합이 있으면 M6이 그 금융사 것으로 좁혀 볼 수 있다(§4.5 M6 ④).
     *
     * @param cardPerformanceMet 아무 카드사에서든 전월 실적 임계를 넘겼는가(당행 한정이 아닌 조건용).
     * @param salaryTransferMet  아무 계좌로든 급여가 들어오는가.
     * @param cardPerformanceCompanies 전월 실적 임계를 넘긴 <b>카드사명</b>. 당행 한정 조건은 이걸로 본다.
     * @param salaryBanks              급여가 실제로 들어오는 <b>은행명</b>.
     */
    public record Preferential(boolean cardPerformanceMet, boolean salaryTransferMet,
                               Set<String> cardPerformanceCompanies, Set<String> salaryBanks) {

        public Preferential {
            cardPerformanceCompanies = cardPerformanceCompanies == null
                    ? Set.of() : Set.copyOf(cardPerformanceCompanies);
            salaryBanks = salaryBanks == null ? Set.of() : Set.copyOf(salaryBanks);
        }

        /** 금융사별 재료가 없던 시절의 형태. 당행 한정 조건은 판정 불가로 떨어진다. */
        public Preferential(boolean cardPerformanceMet, boolean salaryTransferMet) {
            this(cardPerformanceMet, salaryTransferMet, Set.of(), Set.of());
        }
    }

    /** 소스 미연결(또는 재료 없음) — 모든 축이 UNKNOWN으로 나오게 하는 빈 입력. */
    public static FundFlowInputs empty() {
        return new FundFlowInputs(null, null, null, null, null);
    }
}
