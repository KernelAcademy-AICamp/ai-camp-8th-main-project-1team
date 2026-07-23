package com.finntech.mydata.service;

import com.finntech.mydata.domain.*;
import com.finntech.mydata.dto.MyDataDtos.*;
import com.finntech.mydata.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 조회 서비스 — 본체가 요청한 사용자(CI)+카드사의 카드·결제내역을 DTO로 조립한다.
 * 인증은 없다(내부 서버-투-서버 신뢰).
 *
 * <p><b>현재시각 커트오프(§13-11)</b>: 조회는 {@code 결제일 ≤ now}만 반환한다. 미래 날짜로 미리 생성해둔 결제는
 * now가 그 시점을 지나면 자동으로 등장해 '실시간 연동'처럼 보인다. now는 {@code mydata.now}로 정한다
 * (기본 {@code reference}=시드 기준일 끝 → 현재 데이터 전부 노출·결정론적, {@code system}=실시간, 또는 ISO datetime).
 */
@Service
public class MyDataService {

    private final MyDataUserRepository userRepository;
    private final MyDataCardRepository cardRepository;
    private final MyDataPaymentRepository paymentRepository;
    private final CardCompanyRepository companyRepository;
    private final MyDataAccountRepository accountRepository;
    private final String nowSetting;
    private final LocalDate referenceDate;
    /** 전체 조회 하한(W4-3): 0=무제한(현행), N>0이면 최근 N개월만 반환해 대량 사용자 응답 폭주를 막는다. */
    private final int monthsFloor;

    public MyDataService(MyDataUserRepository userRepository, MyDataCardRepository cardRepository,
                         MyDataPaymentRepository paymentRepository, CardCompanyRepository companyRepository,
                         MyDataAccountRepository accountRepository,
                         @Value("${mydata.now:reference}") String nowSetting,
                         @Value("${mydata.seed.reference-date:2026-07-21}") String referenceDate,
                         @Value("${mydata.query.months-floor:0}") int monthsFloor) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.nowSetting = nowSetting;
        this.referenceDate = LocalDate.parse(referenceDate);
        this.monthsFloor = monthsFloor;
    }

    /**
     * 입출금 통장 조회(§13-11 경제 모델). 잔액은 저장하지 않고 계산한다:
     *   잔액 = 초기잔액 + 월급 × (개설~now 월급날 수) − Σ(카드결제 ≤ now).
     * 입출금 내역 = 월급 입금(월급날) + 최근 카드 출금, 최신순 상위 40.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AccountView> findAccount(String userId) {
        return accountRepository.findByUser_Id(userId).map(a -> {
            LocalDateTime now = cutoff();
            long withdrawn = paymentRepository.sumByUserUpTo(userId, now);
            List<AccountTxnView> deposits = salaryDeposits(a, now);
            long balance = a.getInitialBalance() + (long) a.getSalary() * deposits.size() - withdrawn;

            // 입출금 내역 = 월급 입금 전부(월 1회라 적음) + 최근 카드 출금 40건. 입금이 잦은 출금에 밀려 잘리지
            // 않도록 둘 다 보존해 최신순 정렬(프론트가 입금·출금을 함께 보여준다).
            List<AccountTxnView> txns = new java.util.ArrayList<>(deposits);
            for (MyDataPayment p : paymentRepository.findByUserUpTo(
                    userId, now, org.springframework.data.domain.PageRequest.of(0, 40))) {
                txns.add(new AccountTxnView(p.getPaymentDate(), "WITHDRAWAL", p.getAmount(), p.getMerchantName()));
            }
            txns.sort(java.util.Comparator.comparing(AccountTxnView::date).reversed());
            return new AccountView(a.getAccountNumber(), a.getBank(), a.getProduct(), a.getSalaryPayer(),
                    a.getSalary(), a.getPayday(), balance, txns);
        });
    }

    /** 개설일 이후 매달 월급날(payday≤28)에 입금된 월급 내역(≤now). 잔액 계산과 내역 표시에 공용. */
    private List<AccountTxnView> salaryDeposits(MyDataAccount a, LocalDateTime now) {
        List<AccountTxnView> out = new java.util.ArrayList<>();
        LocalDate d = a.getOpenedDate().withDayOfMonth(a.getPayday());
        if (d.isBefore(a.getOpenedDate())) d = d.plusMonths(1);
        String desc = a.getSalaryPayer() + " 급여";
        for (; !d.atTime(9, 0).isAfter(now); d = d.plusMonths(1)) {
            out.add(new AccountTxnView(d.atTime(9, 0), "DEPOSIT", a.getSalary(), desc));
        }
        return out;
    }

    /**
     * 조회 커트오프 시각. {@code reference}=시드 기준일의 하루 끝(현재 데이터 전부 노출),
     * {@code system}=실시간, 그 외는 ISO datetime으로 파싱(데모 시간 고정).
     */
    private LocalDateTime cutoff() {
        if ("system".equalsIgnoreCase(nowSetting)) return LocalDateTime.now();
        if ("reference".equalsIgnoreCase(nowSetting)) return referenceDate.atTime(23, 59, 59);
        return LocalDateTime.parse(nowSetting);
    }

    /** 존재 확인 — 본인인증 후 본체가 "이 CI가 마이데이터에 있는 회원인가"를 묻는다. */
    @Transactional(readOnly = true)
    public boolean userExists(String ci) {
        return userRepository.existsById(ci);
    }

    /** 카드사 목록(연동 기관 선택용). */
    @Transactional(readOnly = true)
    public List<CompanyView> findCompanies() {
        return companyRepository.findAllByOrderByIdAsc().stream().map(this::toCompanyView).toList();
    }

    /** 전체 조회 — 사용자의 특정 카드사 카드 + (현재시각까지의) 결제내역. */
    @Transactional(readOnly = true)
    public List<CardView> findCards(Long companyId, String userId) {
        LocalDateTime now = cutoff();
        return cardRepository.findByUserAndCompany(userId, companyId).stream()
                .map(card -> toCardView(card, paymentsUpTo(card.getId(), now)))
                .toList();
    }

    /** 전체 조회의 결제 fetch — 하한(months-floor) 설정 시 최근 N개월만, 아니면 전체(현행). */
    private List<MyDataPayment> paymentsUpTo(String cardId, LocalDateTime now) {
        return monthsFloor > 0
                ? paymentRepository.findByCardBetween(cardId, now.minusMonths(monthsFloor), now)
                : paymentRepository.findByCardUpTo(cardId, now);
    }

    /** 증분 조회 — 마지막 동기화 이후 ~ 현재시각까지의 결제만. */
    @Transactional(readOnly = true)
    public List<CardView> findCardsSince(Long companyId, String userId, LocalDateTime lastRenewalTime) {
        LocalDateTime now = cutoff();
        return cardRepository.findByUserAndCompany(userId, companyId).stream()
                .map(card -> toCardView(card, paymentRepository.findByCardBetween(card.getId(), lastRenewalTime, now)))
                .toList();
    }

    private CardView toCardView(MyDataCard card, List<MyDataPayment> payments) {
        CardProduct product = card.getCardProduct();
        List<PaymentView> paymentViews = payments.stream()
                .map(payment -> toPaymentView(payment, product.getCode())).toList();
        return new CardView(
                card.getId(), card.getExpirationDate(), card.getPrevMonthAmount(),
                toProductView(product), toUserView(card.getUser()), paymentViews);
    }

    private CardProductView toProductView(CardProduct product) {
        List<BenefitView> benefits = product.getBenefits().stream().map(this::toBenefitView).toList();
        return new CardProductView(product.getCode(), product.getName(), product.getImgUrl(),
                product.getColor(), toCompanyView(product.getCardCompany()), benefits);
    }

    private BenefitView toBenefitView(CardBenefit benefit) {
        return new BenefitView(benefit.getCategory1Name(), benefit.getDiscountPercent(),
                benefit.getPerformanceStart(), benefit.getPerformanceEnd(), benefit.getMonthlyLimit());
    }

    private CompanyView toCompanyView(CardCompany company) {
        return new CompanyView(company.getId(), company.getName(), company.getImgUrl());
    }

    private UserView toUserView(MyDataUser user) {
        // 주민번호·전화번호는 서빙 응답에 싣지 않는다(데이터 최소화, W7-2). 저장은 하되 노출만 차단.
        return new UserView(user.getId(), user.getName());
    }

    private PaymentView toPaymentView(MyDataPayment payment, Long cardCode) {
        return new PaymentView(payment.getId(), payment.getPaymentDate(), payment.getCategory1(),
                payment.getCategory2(), payment.getAmount(), payment.getMerchantName(),
                payment.getReceivedBenefitAmount(), cardCode);
    }
}
