package com.finntech.service;

import com.finntech.domain.UserCard;
import com.finntech.domain.UserPayment;
import com.finntech.engine.RecurringPayment;
import com.finntech.service.AccountFundFlowSource.Thresholds;
import com.finntech.service.FundFlowService.IncomeRegularity;
import com.finntech.service.FundFlowService.LiquidityNeed;
import com.finntech.service.FundFlowService.StabilityLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자금흐름 재료 조립·집계만 검증한다(마이데이터 클라이언트 없이). 소비는 카드로만 세는지도 여기서 확인한다.
 */
class AccountFundFlowSourceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 12, 0);
    /** 큰지출=월평균의 50% 이상 · 간격 CV 0.35 이하면 예측가능 · 카드실적 30만원. */
    private static final Thresholds TH = new Thresholds(0.5, 0.35, 300_000);

    private static UserPayment card(LocalDateTime date, int amount) {
        return card(date, amount, "가맹점");
    }

    private static UserPayment card(LocalDateTime date, int amount, String merchant) {
        return new UserPayment("id-" + date + "-" + amount + "-" + merchant, 1L, "card", 1L,
                date, "대분류", "category2", amount, merchant, 0, null);
    }

    private static MyDataResponses.AccountView account(int salary, int payday, long balance) {
        return account(salary, payday, balance, List.of());
    }

    private static MyDataResponses.AccountView account(int salary, int payday, long balance,
                                                       List<MyDataResponses.AccountTxnView> txns) {
        return new MyDataResponses.AccountView("110-1234", "카카오뱅크", "월급통장", "회사",
                salary, payday, balance, txns);
    }

    /** 계좌 거래 1건 — 평균잔고에 쓰는 건 시각과 `거래 직후 잔액`뿐이다. */
    private static MyDataResponses.AccountTxnView txn(LocalDateTime date, long balanceAfter) {
        return new MyDataResponses.AccountTxnView(date, "WITHDRAW", 0, "적요", "비고", balanceAfter);
    }

    private static UserCard userCard(String company, int prevPerformance) {
        return new UserCard(1L, "1111-2222-3333-" + prevPerformance, 9101L, company + "카드",
                "BLUE", company, prevPerformance, 0, 300_000);
    }

    /** 고정형 반복 결제 1건 — 월 주기(30일)가 기본. */
    private static RecurringPayment fixed(String merchant, long amount, int periodDays, int nextDay) {
        return new RecurringPayment(RecurringPayment.Type.FIXED, "category2", merchant, null, null,
                amount, periodDays, LocalDate.of(2026, 8, nextDay), 3, 7.0 / periodDays);
    }

    private static FundFlowInputs assemble(MyDataResponses.AccountView account, List<UserPayment> payments,
                                           List<UserCard> cards, List<RecurringPayment> recurring) {
        return AccountFundFlowSource.assemble(account, payments, cards, recurring, NOW, TH);
    }

    // ── 월평균지출 (소비는 카드로만) ─────────────────────────────

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
        FundFlowInputs in = assemble(null, List.of(), List.of(), List.of());
        assertThat(in.incomeProfile()).isNull();
        assertThat(in.cashBuffer()).isNull();
        assertThat(in.fixedExpense()).isNull();
        assertThat(in.largeExpense()).isNull();
        assertThat(in.preferential()).isNull();
    }

    @Test
    void 계좌에서_L1소득과_L3잔액을_채운다() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 10, 9, 0), 250_000),
                card(LocalDateTime.of(2026, 6, 10, 9, 0), 250_000));   // 500,000 ÷ 2개월 = 250,000

        FundFlowInputs in = assemble(account(3_000_000, 25, 4_500_000), payments, List.of(), List.of());

        assertThat(in.incomeProfile().regular()).isTrue();
        assertThat(in.incomeProfile().day()).isEqualTo(25);
        assertThat(in.incomeProfile().monthly()).isEqualTo(3_000_000);
        assertThat(in.cashBuffer().avgBalance()).isEqualTo(4_500_000);
        assertThat(in.cashBuffer().monthlyAvgSpend()).isEqualTo(250_000);
    }

    // ── L3 평균잔고 (기간 가중) ──────────────────────────────────

    /** 잔고 300만으로 20일 + 100만으로 10일 → (300×20 + 100×10) ÷ 30 = 233만. */
    @Test
    void L3_평균잔고는_잔고가_머문_기간으로_가중한다() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 31, 0, 0);
        var acct = account(3_000_000, 25, 1_000_000, List.of(
                txn(LocalDateTime.of(2026, 7, 1, 0, 0), 3_000_000),
                txn(LocalDateTime.of(2026, 7, 21, 0, 0), 1_000_000)));

        assertThat(AccountFundFlowSource.averageBalance(acct, end)).isEqualTo(2_333_333);
    }

    /** 같은 통장을 월급 직후에 보느냐 직전에 보느냐로 판정이 뒤집히던 문제. */
    @Test
    void L3_조회시각이_달라도_평균잔고는_같다() {
        List<MyDataResponses.AccountTxnView> history = List.of(
                txn(LocalDateTime.of(2026, 7, 1, 0, 0), 3_000_000),
                txn(LocalDateTime.of(2026, 7, 21, 0, 0), 1_000_000));

        long avg = AccountFundFlowSource.averageBalance(
                account(3_000_000, 25, 1_000_000, history), LocalDateTime.of(2026, 7, 31, 0, 0));

        // 스냅샷(현재 잔액 100만)만 보면 얇아 보이지만, 실제로 머문 평균은 그보다 두껍다
        assertThat(avg).isGreaterThan(1_000_000);
    }

    /** 제공자는 최신순으로 준다 — 정렬해서 계산하지 않으면 구간이 뒤집힌다. */
    @Test
    void L3_거래가_최신순으로_와도_같은_값이_나온다() {
        LocalDateTime end = LocalDateTime.of(2026, 7, 31, 0, 0);
        var oldest = txn(LocalDateTime.of(2026, 7, 1, 0, 0), 3_000_000);
        var newest = txn(LocalDateTime.of(2026, 7, 21, 0, 0), 1_000_000);

        assertThat(AccountFundFlowSource.averageBalance(account(3_000_000, 25, 1_000_000,
                List.of(newest, oldest)), end))
                .isEqualTo(AccountFundFlowSource.averageBalance(account(3_000_000, 25, 1_000_000,
                        List.of(oldest, newest)), end));
    }

    @Test
    void L3_거래내역이_없으면_현재_잔액으로_물러난다() {
        assertThat(AccountFundFlowSource.averageBalance(account(3_000_000, 25, 4_500_000), NOW))
                .isEqualTo(4_500_000);
    }

    @Test
    void L3_거래가_전부_같은_시각이면_기간가중을_못내_스냅샷을_쓴다() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 10, 9, 0);
        var acct = account(3_000_000, 25, 777_000, List.of(txn(at, 1_000_000), txn(at, 900_000)));

        assertThat(AccountFundFlowSource.averageBalance(acct, at)).isEqualTo(777_000);
    }

    @Test
    void L3_평균잔고가_AoM에_실제로_반영된다() {
        var acct = account(3_000_000, 25, 1_000_000, List.of(
                txn(LocalDateTime.of(2026, 7, 1, 0, 0), 3_000_000),
                txn(LocalDateTime.of(2026, 7, 21, 0, 0), 1_000_000)));
        List<UserPayment> payments = List.of(card(LocalDateTime.of(2026, 7, 15, 9, 0), 1_000_000));

        FundFlowInputs in = AccountFundFlowSource.assemble(acct, payments, List.of(), List.of(),
                LocalDateTime.of(2026, 7, 31, 0, 0), TH);

        // 스냅샷이었다면 1,000,000이 들어갔을 자리
        assertThat(in.cashBuffer().avgBalance()).isEqualTo(2_333_333);
    }

    // ── L2 고정비 ────────────────────────────────────────────────

    @Test
    void L2_고정형_반복결제를_월환산해_더한다() {
        // 월 5만(30일) + 주 1만(7일 → 월 42,857)
        var fixedExpense = AccountFundFlowSource.fixedExpense(List.of(
                fixed("통신사", 50_000, 30, 5),
                fixed("헬스장", 10_000, 7, 20)));

        assertThat(fixedExpense.total()).isEqualTo(50_000 + Math.round(10_000 * (30.0 / 7)));
        assertThat(fixedExpense.withdrawalDaySpread()).isEqualTo(15);   // 5일 ~ 20일
    }

    /** 루틴형(아침 커피 같은 습관)은 고정비가 아니다 — 끊을 수 있는 소비다. */
    @Test
    void L2_루틴형은_고정비에서_제외한다() {
        RecurringPayment routine = new RecurringPayment(RecurringPayment.Type.ROUTINE, "카페",
                null, null, "아침", 5_000, null, null, 12, 3.0);
        assertThat(AccountFundFlowSource.fixedExpense(List.of(routine))).isNull();
    }

    /** "고정비 0원"과 "고정비를 모른다"는 다르다. */
    @Test
    void L2_고정형을_못찾으면_null이고_0원이_아니다() {
        assertThat(AccountFundFlowSource.fixedExpense(List.of())).isNull();
        assertThat(AccountFundFlowSource.fixedExpense(null)).isNull();
    }

    @Test
    void L2_고정비가_한건뿐이면_인출일_흩어짐은_낼_수_없다() {
        var fixedExpense = AccountFundFlowSource.fixedExpense(List.of(fixed("통신사", 50_000, 30, 5)));
        assertThat(fixedExpense.total()).isEqualTo(50_000);
        assertThat(fixedExpense.withdrawalDaySpread()).isNull();
    }

    // ── L4 큰 1회성 지출 ─────────────────────────────────────────

    @Test
    void L4_큰지출_기준은_그_사람의_월평균지출_대비_비율이다() {
        // 월평균 1,000,000 → 임계 500,000. 60만은 크고 30만은 아니다.
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 1, 9, 0), 600_000, "가전"),
                card(LocalDateTime.of(2026, 5, 1, 9, 0), 600_000, "여행"),
                card(LocalDateTime.of(2026, 6, 1, 9, 0), 300_000, "옷"));

        var large = AccountFundFlowSource.largeExpense(payments, List.of(), 1_000_000,
                NOW.minusMonths(6), TH);

        assertThat(large.cycleMonths()).isEqualTo(2);   // 5/1 → 7/1 = 61일 ≈ 2개월
        assertThat(large.predictable()).isTrue();       // 간격이 하나뿐이라 변동 0
    }

    /** 씀씀이가 크면 같은 60만원도 큰 지출이 아니다. */
    @Test
    void L4_월평균지출이_크면_같은_금액도_큰지출이_아니다() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 1, 9, 0), 600_000, "가전"),
                card(LocalDateTime.of(2026, 5, 1, 9, 0), 600_000, "여행"));

        var large = AccountFundFlowSource.largeExpense(payments, List.of(), 4_000_000,
                NOW.minusMonths(6), TH);   // 임계 200만 → 아무것도 안 걸림

        assertThat(large.cycleMonths()).isNull();   // 큰 지출 없음 → SMOOTH
    }

    /** 월세·통신비를 여기서 또 세면 L2와 겹쳐 "목돈이 매달 나간다"는 잘못된 신호가 된다. */
    @Test
    void L4_고정형_반복결제_가맹점은_큰지출에서_뺀다() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 1, 9, 0), 600_000, "월세"),
                card(LocalDateTime.of(2026, 6, 1, 9, 0), 600_000, "월세"),
                card(LocalDateTime.of(2026, 5, 1, 9, 0), 600_000, "월세"));

        var large = AccountFundFlowSource.largeExpense(payments,
                List.of(fixed("월세", 600_000, 30, 1)), 1_000_000, NOW.minusMonths(6), TH);

        assertThat(large.cycleMonths()).isNull();   // 전부 반복으로 제외 → 큰 지출 없음
    }

    @Test
    void L4_간격이_들쭉날쭉하면_예측불가() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 3, 1, 9, 0), 600_000, "A"),
                card(LocalDateTime.of(2026, 3, 5, 9, 0), 600_000, "B"),   // 4일 간격
                card(LocalDateTime.of(2026, 7, 1, 9, 0), 600_000, "C"));  // 118일 간격

        var large = AccountFundFlowSource.largeExpense(payments, List.of(), 1_000_000,
                NOW.minusMonths(6), TH);

        assertThat(large.predictable()).isFalse();
    }

    /** 카드 이력이 없으면 "큰"의 기준 자체를 못 세운다 — 없다고 말하지 않는다. */
    @Test
    void L4_월평균지출을_모르면_UNKNOWN이다() {
        assertThat(AccountFundFlowSource.largeExpense(List.of(), List.of(), 0, NOW.minusMonths(6), TH))
                .isNull();
    }

    @Test
    void L4_큰지출이_한건뿐이면_주기를_낼_수_없다() {
        List<UserPayment> payments = List.of(card(LocalDateTime.of(2026, 7, 1, 9, 0), 600_000, "가전"));
        var large = AccountFundFlowSource.largeExpense(payments, List.of(), 1_000_000,
                NOW.minusMonths(6), TH);
        assertThat(large.cycleMonths()).isNull();
    }

    // ── L5 우대조건 ──────────────────────────────────────────────

    @Test
    void L5_카드사별_전월실적_최대값과_급여수신으로_판정한다() {
        var met = AccountFundFlowSource.preferential(account(3_000_000, 25, 1_000_000),
                List.of(userCard("신한", 100_000), userCard("국민", 350_000)), 300_000);

        assertThat(met.cardPerformanceMet()).isTrue();    // 최대 35만 ≥ 30만
        assertThat(met.salaryTransferMet()).isTrue();
    }

    /** 카드사별 실적은 합산하지 않는다 — 실제 우대조건은 보통 한 카드사 기준이다. */
    @Test
    void L5_카드사별_실적을_합산하지_않는다() {
        var out = AccountFundFlowSource.preferential(account(3_000_000, 25, 1_000_000),
                List.of(userCard("신한", 200_000), userCard("국민", 200_000)), 300_000);

        assertThat(out.cardPerformanceMet()).isFalse();   // 합치면 40만이지만 각각은 20만
    }

    @Test
    void L5_급여가_없으면_급여이체_미충족() {
        var out = AccountFundFlowSource.preferential(account(0, 0, 1_000_000),
                List.of(userCard("신한", 500_000)), 300_000);

        assertThat(out.cardPerformanceMet()).isTrue();
        assertThat(out.salaryTransferMet()).isFalse();
    }

    @Test
    void L5_계좌가_없으면_판정재료가_없다() {
        assertThat(AccountFundFlowSource.preferential(null, List.of(userCard("신한", 500_000)), 300_000))
                .isNull();
    }

    // ── 통합: 다섯 축이 실제로 켜진다 ────────────────────────────

    @Test
    void 재료가_다_있으면_다섯_축이_모두_UNKNOWN을_벗어난다() {
        List<UserPayment> payments = List.of(
                card(LocalDateTime.of(2026, 7, 10, 9, 0), 250_000),
                card(LocalDateTime.of(2026, 6, 10, 9, 0), 250_000),
                card(LocalDateTime.of(2026, 7, 1, 9, 0), 200_000, "가전"),   // 월평균 350,000의 50%↑
                card(LocalDateTime.of(2026, 5, 1, 9, 0), 200_000, "여행"));

        FundFlowInputs in = assemble(account(3_000_000, 25, 4_500_000), payments,
                List.of(userCard("신한", 400_000)), List.of(fixed("통신사", 50_000, 30, 5)));

        var profile = new FundFlowService(userId -> in, 1.0, 3.0, 0.20, 0.40).analyze(1L);

        assertThat(profile.l1Income()).isEqualTo(IncomeRegularity.REGULAR);
        assertThat(profile.l2FixedCost()).isNotEqualTo(FundFlowService.FixedCostLevel.UNKNOWN);
        assertThat(profile.l3Stability().level()).isNotEqualTo(StabilityLevel.UNKNOWN);
        assertThat(profile.l4Liquidity().level()).isNotEqualTo(LiquidityNeed.UNKNOWN);
        assertThat(profile.l5Preferential().known()).isTrue();
    }

    @Test
    void 계좌만_있고_카드_반복결제가_없으면_L2_L4는_여전히_UNKNOWN() {
        FundFlowInputs in = assemble(account(3_000_000, 25, 4_500_000), List.of(), List.of(), List.of());
        var profile = new FundFlowService(userId -> in, 1.0, 3.0, 0.20, 0.40).analyze(1L);

        assertThat(profile.l1Income()).isEqualTo(IncomeRegularity.REGULAR);
        assertThat(profile.l2FixedCost()).isEqualTo(FundFlowService.FixedCostLevel.UNKNOWN);
        assertThat(profile.l4Liquidity().level()).isEqualTo(LiquidityNeed.UNKNOWN);
        // 계좌가 있으면 급여이체는 판정 가능하다
        assertThat(profile.l5Preferential().known()).isTrue();
        assertThat(profile.l5Preferential().salaryTransferMet()).isTrue();
    }
}
