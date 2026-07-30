package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.service.FundFlowService.IncomeRegularity;
import com.finntech.service.FundFlowService.StabilityLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잔액 소스의 순수 조립·집계만 검증한다(마이데이터 클라이언트 없이). 소비는 카드로만 세는지도 여기서 확인한다.
 */
class AccountFundFlowSourceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 12, 0);

    private static UserPayment card(LocalDateTime date, int amount) {
        return new UserPayment("id-" + date + "-" + amount, 1L, "card", 1L,
                date, "대분류", "category2", amount, "가맹점", 0, null);
    }

    private static MyDataResponses.AccountView account(int salary, int payday, long balance) {
        return new MyDataResponses.AccountView("110-1234", "카카오뱅크", "월급통장", "회사",
                salary, payday, balance, List.of());
    }

    @Test
    void 월평균지출은_창안_카드합_나누기_실제개월수이고_창밖은_제외() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 10, 9, 0), 300_000),
                card(LocalDateTime.of(2026, 7, 20, 9, 0), 200_000),
                card(LocalDateTime.of(2026, 6, 15, 9, 0), 400_000),
                card(LocalDateTime.of(2025, 12, 1, 9, 0), 999_999));   // 6개월 창 밖 → 제외

        // 창 안 합 900,000 ÷ 실제 2개월(7·6월) = 450,000
        long avg = AccountFundFlowSource.monthlyAvgSpend(payments, NOW.minusMonths(6));
        assertThat(avg).isEqualTo(450_000);
    }

    @Test
    void 지출이_없으면_월평균지출은_0() {
        assertThat(AccountFundFlowSource.monthlyAvgSpend(List.of(), NOW.minusMonths(6))).isZero();
    }

    @Test
    void 계좌미연동이면_빈입력_모든_서브레코드_null() {
        FundFlowInputs in = AccountFundFlowSource.assemble(null, List.of(), NOW);
        assertThat(in.incomeProfile()).isNull();
        assertThat(in.cashBuffer()).isNull();
        assertThat(in.fixedExpense()).isNull();
        assertThat(in.largeExpense()).isNull();
        assertThat(in.preferential()).isNull();
    }

    @Test
    void 계좌에서_L1소득과_L3잔액을_채우고_나머지는_null() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 10, 9, 0), 250_000),
                card(LocalDateTime.of(2026, 6, 10, 9, 0), 250_000));   // 500,000 ÷ 2개월 = 250,000

        FundFlowInputs in = AccountFundFlowSource.assemble(account(3_000_000, 25, 4_500_000), payments, NOW);

        // L1 급여 요약
        assertThat(in.incomeProfile().regular()).isTrue();
        assertThat(in.incomeProfile().day()).isEqualTo(25);
        assertThat(in.incomeProfile().monthly()).isEqualTo(3_000_000);
        // L3 잔액(간이 평균잔고) + 카드 기반 월평균지출
        assertThat(in.cashBuffer().avgBalance()).isEqualTo(4_500_000);
        assertThat(in.cashBuffer().monthlyAvgSpend()).isEqualTo(250_000);
        // 아직 재료 없는 축
        assertThat(in.fixedExpense()).isNull();
        assertThat(in.largeExpense()).isNull();
        assertThat(in.preferential()).isNull();
    }

    @Test
    void 계좌를_꽂으면_판정기계에서_L1_L3가_실제로_켜진다() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 10, 9, 0), 450_000));   // 1개월 450,000
        FundFlowInputs in = AccountFundFlowSource.assemble(account(3_000_000, 25, 4_500_000), payments, NOW);

        // 소스를 이 입력으로 고정한 판정기계(임계 THIN=1, THICK=3)
        var profile = new FundFlowService(userId -> in, 1.0, 3.0, 0.20, 0.40).analyze(1L);

        assertThat(profile.l1Income()).isEqualTo(IncomeRegularity.REGULAR);
        assertThat(profile.l3Stability().aomMonths()).isEqualTo(10.0);        // 4,500,000 ÷ 450,000
        assertThat(profile.l3Stability().level()).isEqualTo(StabilityLevel.THICK);
        // 재료 없는 축은 여전히 UNKNOWN — "없음"을 숨기지 않는다
        assertThat(profile.l2FixedCost()).isEqualTo(FundFlowService.FixedCostLevel.UNKNOWN);
        assertThat(profile.l5Preferential().known()).isFalse();
    }
}
