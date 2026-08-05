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
    private final MyDataAccountTxnRepository accountTxnRepository;
    private final String nowSetting;
    private final LocalDate referenceDate;
    /** 전체 조회 하한(W4-3): 0=무제한(현행), N>0이면 최근 N개월만 반환해 대량 사용자 응답 폭주를 막는다. */
    private final int monthsFloor;

    public MyDataService(MyDataUserRepository userRepository, MyDataCardRepository cardRepository,
                         MyDataPaymentRepository paymentRepository, CardCompanyRepository companyRepository,
                         MyDataAccountRepository accountRepository, MyDataMerchantRepository merchantRepository,
                         MyDataAccountTxnRepository accountTxnRepository,
                         @Value("${mydata.now:reference}") String nowSetting,
                         @Value("${mydata.seed.reference-date:2026-07-21}") String referenceDate,
                         @Value("${mydata.query.months-floor:0}") int monthsFloor) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.accountTxnRepository = accountTxnRepository;
        // 빈 문자열은 '미설정'으로 본다. env(MYDATA_NOW=)가 비어 있으면 Spring은 yml의 기본값이 아니라
        // 빈 문자열을 넘긴다 — 이걸 그대로 parse하면 기동 후 조회에서 DateTimeParseException으로 터진다.
        this.nowSetting = (nowSetting == null || nowSetting.isBlank()) ? "system" : nowSetting.trim();
        this.referenceDate = LocalDate.parse(
                (referenceDate == null || referenceDate.isBlank()) ? "2026-07-21" : referenceDate.trim());
        this.monthsFloor = monthsFloor;
    }

    /**
     * 입출금 통장 조회(§13-11 경제 모델).
     *
     * <p><b>거래는 생성 시점에 적재된다</b>({@code AccountTxnGenerator} → {@code mydata_account_txn}).
     * 예전에는 조회할 때마다 개설일부터 지금까지의 이체를 다시 계산했는데, 그 계산이
     * "지금 이후는 건너뛴다"로 잘리면서 <b>조회 시점이 지난달 입금 총액을 바꿨다</b> —
     * 어제 본 통장과 오늘 본 통장의 지난주가 다르면 그건 통장이 아니다.
     *
     * <p><b>잔액만은 저장하지 않는다.</b> 거래가 하나 늘면 그 뒤 모든 행의 잔액이 낡기 때문이다.
     * 구간 시작 잔액(= 초기잔액 + 그 이전 순증감)에 시간순으로 누적해 굴린다.
     *
     * @param months 최근 N개월(당월 포함). 1이면 이번 달, 7이면 이번 달 + 이전 6개월.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AccountView> findAccount(String userId, int months) {
        return accountRepository.findByUser_Id(userId).map(a -> {
            LocalDateTime now = cutoff();
            int m = Math.max(1, months);
            LocalDateTime from = java.time.YearMonth.from(now).minusMonths(m - 1L).atDay(1).atStartOfDay();
            if (from.isBefore(a.getOpenedDate().atStartOfDay())) from = a.getOpenedDate().atStartOfDay();

            String acc = a.getAccountNumber();
            long running = a.getInitialBalance() + accountTxnRepository.netBefore(acc, from);

            List<MyDataAccountTxn> rows = accountTxnRepository.findByAccountBetween(acc, from, now);
            List<AccountTxnView> txns = new java.util.ArrayList<>(rows.size());
            for (MyDataAccountTxn t : rows) {
                running += "DEPOSIT".equals(t.getType()) ? t.getAmount() : -t.getAmount();
                txns.add(new AccountTxnView(t.getDate(), t.getType(), t.getAmount(),
                        t.getDescription(), t.getNote(), running));
            }
            long balance = running;   // 구간 끝 = 지금 잔액(구간은 항상 now까지다)
            java.util.Collections.reverse(txns);   // 화면은 최신순

            return new AccountView(a.getAccountNumber(), a.getBank(), a.getProduct(), a.getSalaryPayer(),
                    a.getSalary(), a.getPayday(), balance, txns);
        });
    }

    /** 기본 조회 — 이번 달. */
    @Transactional(readOnly = true)
    public java.util.Optional<AccountView> findAccount(String userId) {
        return findAccount(userId, 1);
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

    /**
     * 신원 대조 — 본인인증이 <b>어느 항목이 틀렸는지</b> 가려내도록 조회 사실만 돌려준다.
     *
     * <p>CI 하나로는 "안 맞는다"까지만 알 수 있다(해시라 어느 항목이 틀렸는지 되짚을 수 없다).
     * 그래서 번호로 한 번, 이름+주민번호로 한 번 찾아 <b>무엇이 맞고 무엇이 틀렸는지</b>를 준다.
     * 판정과 문구 선택은 본체 몫이다.
     */
    @Transactional(readOnly = true)
    public IdentityMatchView matchIdentity(String name, String social7, String phone) {
        String normalized = normalizePhone(phone);
        var byPhone = userRepository.findByPhoneNumber(normalized);
        var byPerson = userRepository.findByNameAndSocial7(name, social7);
        boolean phoneNameOk = byPhone.map(u -> u.getName().equals(name)).orElse(false);
        boolean phoneSocialOk = byPhone
                .map(u -> u.getSocialNumber().length() >= 7
                        && u.getSocialNumber().substring(0, 7).equals(social7))
                .orElse(false);
        boolean exists = byPhone.isPresent() && phoneNameOk && phoneSocialOk;
        return new IdentityMatchView(exists, byPhone.isPresent(), phoneNameOk, phoneSocialOk,
                byPerson.isPresent());
    }

    /** 저장 형식은 `010-1234-5678`이다. 입력이 하이픈 없이 와도 같은 사람을 찾게 맞춘다. */
    /** 저장 표기와 <b>같은 규칙</b>을 쓴다 — 갈리면 있는 사람을 못 찾는다({@link Msisdn#format}). */
    private static String normalizePhone(String phone) {
        return com.finntech.mydata.util.Msisdn.format(phone);
    }

    /** 가맹점 조회(번호→주소) — 사용자가 결제의 사업자번호로 가맹점명·지번주소를 조회한다. 없으면 empty. */
    @Transactional(readOnly = true)
    public java.util.Optional<MerchantView> findMerchant(String businessNumber) {
        return merchantRepository.findById(businessNumber).map(m ->
                new MerchantView(m.getKsicCode(), m.getBusinessNumber(), m.getMerchantName(), m.getAddress(),
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
        return new BenefitView(benefit.getMidCategory(), benefit.getDiscountPercent(),
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
        return new PaymentView(payment.getId(), payment.getPaymentDate(), payment.getKsicCode(),
                payment.getAmount(), payment.getMerchantName(),
                payment.getReceivedBenefitAmount(), cardCode, payment.getBusinessNumber());
    }
}
