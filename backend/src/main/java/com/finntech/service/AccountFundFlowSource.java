package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link FundFlowSource}의 실동 구현 — <b>잔액·급여는 마이데이터 계좌에서 직접</b> 읽어 자금 흐름 재료를 채운다.
 * ①의 (B)를 기다리지 않기로 한 결정(B안)에 따른 소스다. 소스만 이걸로 갈아끼웠고 {@link FundFlowService}·
 * {@link FundFlowInputs}(계약)는 손대지 않는다 — seam이 한 일이 정확히 이거다.
 *
 * <p><b>이중 계산 안전.</b> 계좌에서는 <b>잔액(스냅샷 숫자)과 급여만</b> 읽고, 계좌 출금 거래는 소비로 세지 않는다.
 * 소비(월평균지출)는 카드 결제({@link UserPayment})로만 집계한다 — ①이 소비를 카드 승인내역으로만 팔로우하는 것과
 * 같은 규칙(R7). 그래서 카드 22만원과 그 대금 계좌 출금 22만원을 겹쳐 세는 일이 없다.
 *
 * <p><b>지금 채우는 축</b>: L1(소득 — 급여·급여일)·L3(안정성 — 평균잔고÷월평균지출). L2·L4·L5는 아직 재료가 없어
 * {@code null}(=UNKNOWN)로 둔다. ①의 (B) `fixed_expense_summary`·`recurring_payments`·`preferential_material`가
 * 붙으면 이 소스에서 함께 채우거나 별도 소스로 합친다.
 */
@Component
public class AccountFundFlowSource implements FundFlowSource {

    private static final Logger log = LoggerFactory.getLogger(AccountFundFlowSource.class);

    /** 월평균지출 산정 창(개월). 취미보다 소비는 촘촘해 6개월이면 대표값이 안정적이다. */
    static final int WINDOW_MONTHS = 6;

    private final MyDataLinkService myDataLinkService;
    private final UserPaymentRepository paymentRepository;
    private final Clock clock;

    public AccountFundFlowSource(MyDataLinkService myDataLinkService,
                                 UserPaymentRepository paymentRepository, Clock clock) {
        this.myDataLinkService = myDataLinkService;
        this.paymentRepository = paymentRepository;
        this.clock = clock;
    }

    @Override
    public FundFlowInputs load(Long userId) {
        MyDataResponses.AccountView account;
        try {
            account = myDataLinkService.account(userId);   // 은행 미연동이면 null
        } catch (RuntimeException e) {
            // 마이데이터 조회 실패(네트워크 등)는 서비스 장애로 번지지 않게 재료 없이 진행 → 전 축 UNKNOWN.
            log.warn("자금흐름 계좌 조회 실패 userId={} — 재료 없이 진행: {}", userId, e.toString());
            return FundFlowInputs.empty();
        }
        List<UserPayment> payments = paymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        return assemble(account, payments, LocalDateTime.now(clock));
    }

    // ── 순수 조립 (테스트 진입점) ──────────────────────────────────────────────

    /**
     * 계좌·카드에서 자금 흐름 재료를 조립한다. 계좌가 없으면(은행 미연동) 빈 입력.
     * 잔액은 평균잔고의 <b>간이 대용</b>으로 현재 잔액을 쓴다(§8: 간이 vs 정통 AoM 미정).
     */
    static FundFlowInputs assemble(MyDataResponses.AccountView account, List<UserPayment> payments, LocalDateTime now) {
        if (account == null) return FundFlowInputs.empty();

        long monthlyAvgSpend = monthlyAvgSpend(payments, now.minusMonths(WINDOW_MONTHS));

        // L1 소득 — 급여 요약만 읽는다(계좌 원본 아님). 더미는 급여가 고정·정기라 salary>0이면 규칙적.
        FundFlowInputs.IncomeProfile income =
                new FundFlowInputs.IncomeProfile(account.salary() > 0, account.payday(), account.salary());
        // L3 안정성 — 잔액(스냅샷)을 평균잔고 간이 대용으로, 월평균지출은 카드에서.
        FundFlowInputs.CashBuffer cashBuffer =
                new FundFlowInputs.CashBuffer(account.balance(), monthlyAvgSpend);

        // L2 고정비·L4 큰지출·L5 우대조건은 아직 ①(B)/후속 → null(=UNKNOWN).
        return new FundFlowInputs(income, null, cashBuffer, null, null);
    }

    /**
     * 창 안(since 이후) 카드 결제 합 ÷ 실제 지출이 있던 개월 수. 소비는 <b>카드 내역만</b> 센다(이중 계산 방지).
     * 지출이 없으면 0. 데이터가 2개월치뿐인 사용자를 6으로 나눠 과소평가하지 않도록 <b>실제 개월 수</b>로 나눈다.
     */
    static long monthlyAvgSpend(List<UserPayment> payments, LocalDateTime since) {
        long total = 0;
        Set<YearMonth> months = new HashSet<>();
        for (UserPayment p : payments) {
            if (p.getPaymentDate() == null || p.getPaymentDate().isBefore(since)) continue;
            total += Math.max(p.getAmount(), 0);
            months.add(YearMonth.from(p.getPaymentDate()));
        }
        return total / Math.max(1, months.size());
    }
}
