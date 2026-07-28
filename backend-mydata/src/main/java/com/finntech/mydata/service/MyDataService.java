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
    private final MyDataMerchantRepository merchantRepository;
    private final String nowSetting;
    private final LocalDate referenceDate;
    /** 전체 조회 하한(W4-3): 0=무제한(현행), N>0이면 최근 N개월만 반환해 대량 사용자 응답 폭주를 막는다. */
    private final int monthsFloor;

    public MyDataService(MyDataUserRepository userRepository, MyDataCardRepository cardRepository,
                         MyDataPaymentRepository paymentRepository, CardCompanyRepository companyRepository,
                         MyDataAccountRepository accountRepository, MyDataMerchantRepository merchantRepository,
                         @Value("${mydata.now:reference}") String nowSetting,
                         @Value("${mydata.seed.reference-date:2026-07-21}") String referenceDate,
                         @Value("${mydata.query.months-floor:0}") int monthsFloor) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        // 빈 문자열은 '미설정'으로 본다. env(MYDATA_NOW=)가 비어 있으면 Spring은 yml의 기본값이 아니라
        // 빈 문자열을 넘긴다 — 이걸 그대로 parse하면 기동 후 조회에서 DateTimeParseException으로 터진다.
        this.nowSetting = (nowSetting == null || nowSetting.isBlank()) ? "system" : nowSetting.trim();
        this.referenceDate = LocalDate.parse(
                (referenceDate == null || referenceDate.isBlank()) ? "2026-07-21" : referenceDate.trim());
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
            List<AccountTxnView> interest = interestAndTax(a, userId, now);
            long net = interest.stream()
                    .mapToLong(t -> "DEPOSIT".equals(t.type()) ? t.amount() : -t.amount()).sum();
            long balance = a.getInitialBalance() + (long) a.getSalary() * deposits.size() + net - withdrawn;

            // 입출금 내역 = 월급 입금 + 이자·이자소득세 + 최근 카드 출금 40건. 입금이 잦은 출금에 밀려 잘리지
            // 않도록 앞의 둘은 보존해 최신순 정렬(프론트가 입금·출금을 함께 보여준다).
            List<AccountTxnView> txns = new java.util.ArrayList<>(deposits);
            txns.addAll(interest);
            for (MyDataPayment p : paymentRepository.findByUserUpTo(
                    userId, now, org.springframework.data.domain.PageRequest.of(0, 40))) {
                txns.add(new AccountTxnView(p.getPaymentDate(), "WITHDRAWAL", p.getAmount(), p.getMerchantName()));
            }
            txns.sort(java.util.Comparator.comparing(AccountTxnView::date).reversed());
            return new AccountView(a.getAccountNumber(), a.getBank(), a.getProduct(), a.getSalaryPayer(),
                    a.getSalary(), a.getPayday(), balance, txns);
        });
    }

    /** 이자소득세 15.4% — 소득세 14% + 지방소득세 1.4%. 원천징수라 입금 직후 빠진다. */
    private static final double INTEREST_TAX_RATE = 0.154;
    /** 연이율 범위. 입출금 통장이라 낮다(정기예금이 아니다). */
    private static final double RATE_MIN = 0.001, RATE_MAX = 0.020;

    /**
     * 계좌별 연이율 — 계좌번호 해시에서 0.1~2.0% 사이로 뽑는다.
     *
     * <p>난수를 그때그때 뽑지 않는 이유는 재현성이다(마스터 §4 원칙 3). 같은 계좌는 언제 조회해도
     * 같은 이율이어야 잔액이 흔들리지 않는다. 계좌번호는 불변이므로 시드로 적당하다.
     */
    private static double annualRate(MyDataAccount a) {
        int h = a.getAccountNumber().hashCode();
        double unit = (h & 0x7fffffff) / (double) Integer.MAX_VALUE;   // [0,1)
        return RATE_MIN + unit * (RATE_MAX - RATE_MIN);
    }

    /**
     * 매달 이자 입금과 그 직후의 이자소득세 출금을 만든다(≤now).
     *
     * <p><b>저장하지 않고 계산한다.</b> 월급 입금과 같은 방식이다 — 통장 거래는 행으로 쌓아둔 것이
     * 아니라 개설일·월급·결제내역에서 유도된다. 그래서 이자를 넣는 데 마이데이터 재생성이 필요 없다.
     *
     * <p>이자는 <b>그 시점 실잔액</b>에 붙는다. 많이 쓴 달은 잔액이 낮아 이자도 적다 — "안 쓰면
     * 더 붙는다"가 숫자로 드러나야 소비 조언 앱의 서사가 산다. 그래서 월별 출금을 한 번에 받아
     * 시간순으로 걸으며 잔액을 굴린다(매달 합계를 따로 묻지 않는다).
     */
    private List<AccountTxnView> interestAndTax(MyDataAccount a, String userId, LocalDateTime now) {
        java.util.Map<java.time.YearMonth, Long> outByMonth = new java.util.HashMap<>();
        for (Object[] row : paymentRepository.sumByUserPerMonth(userId, now)) {
            outByMonth.merge(java.time.YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue()),
                    ((Number) row[2]).longValue(), Long::sum);
        }

        double rate = annualRate(a);
        // 이자일은 개설일의 '일'을 따른다(말일 보정). 월급날과 겹치지 않게 시각만 다르게 둔다.
        int day = Math.min(a.getOpenedDate().getDayOfMonth(), 28);
        LocalDate d = a.getOpenedDate().withDayOfMonth(day);
        if (!d.isAfter(a.getOpenedDate())) d = d.plusMonths(1);   // 개설 당월은 이자 없음

        long balance = a.getInitialBalance();
        LocalDate salaryDay = a.getOpenedDate().withDayOfMonth(Math.min(a.getPayday(), 28));
        if (salaryDay.isBefore(a.getOpenedDate())) salaryDay = salaryDay.plusMonths(1);

        List<AccountTxnView> out = new java.util.ArrayList<>();
        for (; !d.atTime(0, 5).isAfter(now); d = d.plusMonths(1)) {
            java.time.YearMonth ym = java.time.YearMonth.from(d);
            // 이 달의 이자일 이전에 들어온 월급과 나간 카드결제를 잔액에 반영한다.
            for (; !salaryDay.isAfter(d); salaryDay = salaryDay.plusMonths(1)) balance += a.getSalary();
            balance -= outByMonth.getOrDefault(ym.minusMonths(1), 0L);

            long interest = Math.max(0, (long) (Math.max(0, balance) * rate / 12.0));
            if (interest <= 0) continue;
            long tax = (long) (interest * INTEREST_TAX_RATE);
            out.add(new AccountTxnView(d.atTime(0, 5), "DEPOSIT", interest,
                    String.format("이자 (연 %.2f%%)", rate * 100)));
            if (tax > 0) out.add(new AccountTxnView(d.atTime(0, 6), "WITHDRAWAL", tax, "이자소득세"));
            balance += interest - tax;
        }
        return out;
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

    /** 가맹점 조회(번호→주소) — 사용자가 결제의 사업자번호로 가맹점명·지번주소를 조회한다. 없으면 empty. */
    @Transactional(readOnly = true)
    public java.util.Optional<MerchantView> findMerchant(String businessNumber) {
        return merchantRepository.findById(businessNumber).map(m ->
                new MerchantView(m.getBusinessNumber(), m.getMerchantName(), m.getAddress(),
                        m.getLat(), m.getLng(), m.isOnline()));
    }

    /** 연동 가능 은행 목록(자산연결 화면용). id는 이름순 순번이라 조회마다 같다. */
    @Transactional(readOnly = true)
    public List<BankView> findBanks() {
        List<String> names = accountRepository.findDistinctBanks();
        List<BankView> out = new java.util.ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) out.add(new BankView((long) (i + 1), names.get(i)));
        return out;
    }

    /**
     * 고른 은행들에 있는 사용자의 계좌. 실제 마이데이터처럼 <b>있는 것만</b> 내려준다 —
     * 계좌가 없는 은행을 골랐다면 빈 목록이다(그것이 정확한 답이다).
     */
    @Transactional(readOnly = true)
    public List<AccountView> findAccountsByBanks(String userId, List<Long> bankIds) {
        List<String> all = accountRepository.findDistinctBanks();
        List<String> picked = bankIds.stream()
                .filter(id -> id != null && id >= 1 && id <= all.size())
                .map(id -> all.get((int) (id - 1))).toList();
        if (picked.isEmpty()) return List.of();
        // 사용자당 계좌가 1개라 목록이라도 0~1건이다. 상세(잔액·내역)는 findAccount가 계산한다.
        return accountRepository.findByUserAndBanks(userId, picked).isEmpty()
                ? List.of()
                : findAccount(userId).map(List::of).orElse(List.of());
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
                payment.getReceivedBenefitAmount(), cardCode, payment.getBusinessNumber());
    }
}
