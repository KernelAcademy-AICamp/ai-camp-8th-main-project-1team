package com.finntech.service;

import com.finntech.domain.*;
import com.finntech.repository.*;
import com.finntech.service.MyDataResponses.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 마이데이터 연동 (§13-3). 본인인증(가상 CI) 후 카드사를 연결하면 마이데이터 서버에서 카드·결제내역을 당겨와
 * <b>UserCard/UserPayment로 영속화</b>하고, 동시에 <b>Consumption(source=MYDATA)로 투영</b>해 기존 엔진(소비건전성·리포트·절약통·FDS)이
 * 재계산 없이 재사용하게 한다. 재연동은 전체 동기화(기존 MYDATA 데이터 교체)로 처리한다.
 */
@Service
public class MyDataLinkService {

    private static final Logger log = LoggerFactory.getLogger(MyDataLinkService.class);

    private final MyDataClient myDataClient;
    private final AppUserRepository userRepository;
    private final UserCardRepository userCardRepository;
    private final UserPaymentRepository userPaymentRepository;
    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;
    private final UserCardCompanyRepository userCardCompanyRepository;
    private final UserBankRepository userBankRepository;
    private final ReportRepository reportRepository;
    private final java.time.Clock clock;
    /** 고정 기준일. null이면 {@link #referenceDate()}가 주입된 시계를 따른다. */
    private final LocalDate fixedReferenceDate;

    public MyDataLinkService(MyDataClient myDataClient, AppUserRepository userRepository,
                             UserCardRepository userCardRepository, UserPaymentRepository userPaymentRepository,
                             ConsumptionRepository consumptionRepository, CategoryRepository categoryRepository,
                             UserCardCompanyRepository userCardCompanyRepository,
                             UserBankRepository userBankRepository, ReportRepository reportRepository,
                             java.time.Clock clock,
                             @Value("${finntech.mydata.reference-date:}") String referenceDate) {
        this.myDataClient = myDataClient;
        this.userRepository = userRepository;
        this.userCardRepository = userCardRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.userCardCompanyRepository = userCardCompanyRepository;
        this.userBankRepository = userBankRepository;
        this.reportRepository = reportRepository;
        this.clock = clock;
        this.fixedReferenceDate = (referenceDate == null || referenceDate.isBlank())
                ? null : LocalDate.parse(referenceDate.trim());
    }

    /**
     * 링크·월집계의 기준이 되는 '오늘'.
     *
     * <p>비워 두면 주입된 {@link java.time.Clock}(= 시스템 단일 시간 출처, {@code finntech.demo.today}로
     * 고정 가능)을 따른다. 예전에는 이 값이 상수로 박혀 있어, 분석·지킴이는 실시간으로 앞서가는데
     * 링크 기준일만 과거에 멈춰 있었다 — 오늘 시작한 챌린지에 넣을 소비가 0건이 되는 원인이었다.
     * 값을 넣으면 그 날짜로 고정되지만, 그때는 {@code mydata.now}도 같이 고정해야 한다(짝이다).
     */
    private LocalDate referenceDate() {
        return fixedReferenceDate != null ? fixedReferenceDate : LocalDate.now(clock);
    }

    /** 카드사 목록(연동 기관 선택용). */
    public List<CompanyView> companies() {
        return myDataClient.findCompanies();
    }

    /**
     * 카드사 연결 → 마이데이터에서 카드·결제 전체 조회 → UserCard/UserPayment 적재 + Consumption(MYDATA) 투영.
     * 전체 동기화: 기존 MYDATA 데이터(카드·결제·투영 소비)를 지우고 새로 적재한다.
     */
    @Transactional
    public LinkResult linkCardCompanies(Long userId, List<Long> companyIds) {
        return linkCardCompanies(userId, companyIds, List.of());
    }

    /**
     * 카드사와 은행을 함께 연동한다(§13 자산연결).
     *
     * <p>은행 쪽은 <b>고른 은행에 계좌가 있을 때만</b> 연동 기록을 남긴다 — 실제 마이데이터가
     * 그렇게 동작한다(체크한 기관에 자산이 없으면 아무것도 내려오지 않는다). 계좌의 잔액·내역은
     * 저장하지 않는다. 조회 시점에 계산되는 값이라 저장하면 즉시 낡는다.
     */
    @Transactional
    public LinkResult linkCardCompanies(Long userId, List<Long> companyIds, List<Long> bankIds) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) {
            throw new IllegalStateException("본인인증(가상 CI)이 먼저 필요합니다");
        }
        // 개인정보 수집 동의가 없으면 연동(=카드·결제 수집·투영) 불가 — 소비 입력의 403 패턴과 동일(W7-5c).
        if (!user.isConsentGiven()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "개인정보 수집 동의가 필요합니다");
        }

        userCardRepository.deleteByUserId(userId);
        userPaymentRepository.deleteByUserId(userId);
        consumptionRepository.deleteByUserIdAndSource(userId, Enums.DataSource.MYDATA);
        userCardCompanyRepository.deleteByUserId(userId);
        userBankRepository.deleteByUserId(userId);
        reportRepository.deleteByUserId(userId);   // 판정 소스(ML)·데이터가 바뀌므로 리포트 캐시 무효화

        LocalDate today = referenceDate();
        YearMonth referenceMonth = YearMonth.from(today);
        LocalDateTime linkTime = LocalDateTime.now(clock);
        int cardCount = 0, paymentCount = 0;

        for (Long companyId : companyIds) {
            String companyName = null;
            // 이 카드사에서 실제로 받아온 마지막 결제 시각. 다음 증분의 기준선이 된다.
            LocalDateTime lastPayment = null;
            for (CardView card : myDataClient.findCards(companyId, ci)) {
                companyName = card.cardProduct().company().name();
                int requirement = requirementOf(card);
                int currentPerformance = card.payments().stream()
                        .filter(payment -> YearMonth.from(payment.date()).equals(referenceMonth))
                        .mapToInt(PaymentView::amount).sum();
                userCardRepository.save(new UserCard(userId, card.cardId(), card.cardProduct().code(),
                        card.cardProduct().name(), card.cardProduct().color(),
                        card.cardProduct().company().name(), card.prevMonthAmount(),
                        currentPerformance, requirement));
                cardCount++;

                for (PaymentView payment : card.payments()) {
                    userPaymentRepository.save(new UserPayment(
                            UserPayment.rowId(userId, payment.id()), userId, card.cardId(),
                            payment.cardCode(), payment.date(), payment.category1(), payment.category2(),
                            payment.amount(), payment.merchantName(), payment.receivedBenefitAmount(),
                            payment.businessNumber()));
                    // 기존 엔진 재사용을 위한 투영 — category1(대분류)을 카테고리 코드로 그대로 쓴다(온디맨드 생성, 원칙 4).
                    Category category = categoryRepository.findByCode(payment.category1())
                            .orElseGet(() -> categoryRepository.save(
                                    new Category(payment.category1(), payment.category1())));
                    consumptionRepository.save(new Consumption(userId, category,
                            BigDecimal.valueOf(payment.amount()), payment.date(), false,
                            Enums.DataSource.MYDATA));
                    paymentCount++;
                    if (lastPayment == null || payment.date().isAfter(lastPayment)) lastPayment = payment.date();
                }
            }
            if (companyName != null) { // 카드가 있던 카드사만 연동 기록(다음 동기화 증분 기준)
                // 증분 기준선은 '실제로 받아온 마지막 결제 시각'이어야 한다.
                // 예전에는 연동한 날의 23:59:59로 앞질러 찍었는데, 마이데이터 서버는 커트오프(지금)까지만
                // 주므로 연동 시점부터 자정까지의 결제가 기준선 뒤로 밀려 영영 안 들어왔다.
                // 오전에 연동하면 그날 낮 결제가 통째로 사라지고, 지킴이 차감·판정도 그만큼 비었다.
                // 결제가 하나도 없던 카드사는 넉넉히 과거로 잡아 다음 증분이 무엇이든 집어오게 한다(멱등).
                LocalDateTime since = lastPayment != null ? lastPayment : today.minusMonths(12).atStartOfDay();
                userCardCompanyRepository.save(
                        new UserCardCompany(userId, companyId, companyName, linkTime, since));
            }
        }
        // 은행 — 고른 은행에 계좌가 있을 때만 연동 기록을 남긴다. 없으면 아무것도 남기지 않는 것이
        // 정확한 답이다(실제 마이데이터도 자산이 없는 기관에서는 아무것도 내려주지 않는다).
        int bankCount = 0;
        if (bankIds != null && !bankIds.isEmpty()) {
            List<BankView> banks = myDataClient.findBanks();
            for (MyDataResponses.AccountView acc : myDataClient.findAccountsByBanks(ci, bankIds)) {
                Long bankId = banks.stream()
                        .filter(b -> b.name().equals(acc.bank())).map(BankView::id).findFirst().orElse(null);
                if (bankId == null) continue;   // 제공자 목록에 없는 은행 — 남길 id가 없다
                userBankRepository.save(new UserBank(userId, bankId, acc.bank(), linkTime));
                bankCount++;
            }
        }

        // 입출금 통장의 월급을 앱 사용자 월급(=예산)으로 반영(§13-11 경제 모델). 통장 없으면 기존값 유지.
        MyDataResponses.AccountView account = myDataClient.findAccount(ci);
        if (account != null && account.salary() > 0) {
            user.setMonthlyIncome(BigDecimal.valueOf(account.salary()));
            userRepository.save(user);
        }
        log.info("마이데이터 연동 완료 — userId={} 카드사 {}개, 카드 {}장, 결제 {}건, 은행 {}곳 적재",
                userId, companyIds.size(), cardCount, paymentCount, bankCount);
        return new LinkResult(cardCount, paymentCount, bankCount);
    }

    /** 가맹점 조회(번호→주소) — 결제에 실린 사업자번호로 가맹점명·지번주소를 제공자에서 조회(프록시). 없으면 null. */
    @Transactional(readOnly = true)
    public MyDataResponses.MerchantView merchant(String businessNumber) {
        return myDataClient.findMerchant(businessNumber);
    }

    /**
     * 입출금 통장 조회(§13-11) — 프론트 통장 화면용. 사용자의 CI로 제공자에 프록시한다.
     *
     * <p><b>은행을 연동한 사용자에게만 준다.</b> 연동하지 않았는데 통장이 보이면 연결이라는 절차가
     * 의미를 잃는다(카드만 고른 사람에게 통장이 딸려 나오면 안 된다). 잔액·내역은 저장하지 않고
     * 매번 계산해 받는다 — 결제가 들어올 때마다 값이 바뀌므로 저장하면 즉시 낡는다.
     */
    @Transactional(readOnly = true)
    public MyDataResponses.AccountView account(Long userId) {
        return account(userId, 1);
    }

    /** @param months 최근 N개월(당월 포함) — 화면의 '이전 6개월 보기'가 7을 보낸다. */
    @Transactional(readOnly = true)
    public MyDataResponses.AccountView account(Long userId, int months) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));
        if (!userBankRepository.existsByUserId(userId)) return null;
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) return null;
        return myDataClient.findAccount(ci, months);
    }

    /**
     * 실시간 증분 동기화(§13-11, W2) — 카드사별 lastRenewalTime 이후의 새 결제만 당겨와 append한다.
     * 마이데이터 커트오프(mydata.now)가 전진하면 미리 생성해둔 미래 결제가 등장한다. 멱등(이미 있는 결제 skip).
     */
    @Transactional
    public SyncResult renew(Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) throw new IllegalStateException("본인인증(가상 CI)이 먼저 필요합니다");
        if (!user.isConsentGiven()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "개인정보 수집 동의가 필요합니다");
        }
        int added = 0;
        for (UserCardCompany link : userCardCompanyRepository.findByUserIdOrderByCompanyIdAsc(userId)) {
            LocalDateTime since = link.getLastRenewalTime();
            LocalDateTime maxDate = since;
            for (CardView card : myDataClient.findCardsSince(link.getCompanyId(), ci, since)) {
                for (PaymentView payment : card.payments()) {
                    // 멱등 — 계정별 키로 확인한다. 제공자 id로 보면 남의 행을 내 것으로 착각한다.
                    if (userPaymentRepository.existsById(UserPayment.rowId(userId, payment.id()))) continue;
                    userPaymentRepository.save(new UserPayment(
                            UserPayment.rowId(userId, payment.id()), userId, card.cardId(),
                            payment.cardCode(), payment.date(), payment.category1(), payment.category2(),
                            payment.amount(), payment.merchantName(), payment.receivedBenefitAmount(),
                            payment.businessNumber()));
                    Category category = categoryRepository.findByCode(payment.category1())
                            .orElseGet(() -> categoryRepository.save(
                                    new Category(payment.category1(), payment.category1())));
                    consumptionRepository.save(new Consumption(userId, category,
                            BigDecimal.valueOf(payment.amount()), payment.date(), false, Enums.DataSource.MYDATA));
                    added++;
                    if (payment.date().isAfter(maxDate)) maxDate = payment.date();
                }
            }
            link.setLastRenewalTime(maxDate);      // 다음 증분 기준 전진
            userCardCompanyRepository.save(link);
        }
        if (added > 0) reportRepository.deleteByUserId(userId); // 새 결제 반영 위해 리포트 캐시 무효화
        // 자동 동기화 배치가 5분마다 이 메서드를 부른다. 대부분 0건이라 INFO로 남기면 로그가 그것만으로 찬다.
        if (added > 0) log.info("마이데이터 증분 동기화 — userId={} 신규 결제 {}건", userId, added);
        else log.debug("마이데이터 증분 동기화 — userId={} 신규 결제 없음", userId);
        return new SyncResult(added);
    }

    public record SyncResult(int newPayments) {}

    /**
     * '내 카드' 화면 — 카드별 실적 진행률 + 이번달 받은 혜택.
     *
     * <p><b>실적도 조회 시점에 다시 센다.</b> 예전에는 연동하던 순간에 계산해 {@code UserCard}에
     * 저장한 값을 그대로 보여줬고, 갱신하는 곳이 어디에도 없었다({@code setCurrentPerformance}
     * 호출부 0건 — 증분 동기화도 건드리지 않았다). 그래서 6월에 연동하고 7월에 이 화면을 열면
     * "이번 달 사용"에는 <b>6월 금액</b>이, 바로 옆 "받은 혜택"에는 7월 금액이 나란히 떴다.
     * 한 줄 안에서 두 값의 기간이 달랐고, 전월실적 진행바도 7월 내내 움직이지 않았다.
     */
    @Transactional(readOnly = true)
    public List<MyCardView> myCards(Long userId) {
        YearMonth referenceMonth = YearMonth.from(referenceDate());
        List<MyCardView> views = new ArrayList<>();
        for (UserCard card : userCardRepository.findByUserIdOrderByIdAsc(userId)) {
            List<UserPayment> thisMonth = userPaymentRepository
                    .findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, card.getSerialNumber()).stream()
                    .filter(payment -> YearMonth.from(payment.getPaymentDate()).equals(referenceMonth))
                    .toList();
            int earnedThisMonth = thisMonth.stream().mapToInt(UserPayment::getReceivedBenefit).sum();
            int currentPerformance = thisMonth.stream().mapToInt(UserPayment::getAmount).sum();

            boolean requirementMet = card.getRequirement() == 0
                    || currentPerformance >= card.getRequirement();
            int toRequirement = Math.max(0, card.getRequirement() - currentPerformance);
            views.add(new MyCardView(card.getSerialNumber(), card.getCardCode(), card.getCardName(),
                    card.getCardColor(), card.getCompanyName(), card.getRequirement(),
                    currentPerformance, requirementMet, toRequirement, earnedThisMonth));
        }
        return views;
    }

    /** '내 카드' 상세 — 카드 결제내역(최신순). */
    @Transactional(readOnly = true)
    public List<PaymentRow> cardPayments(Long userId, String cardSerial) {
        return userPaymentRepository.findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, cardSerial).stream()
                .map(payment -> new PaymentRow(payment.getPaymentId(), payment.getPaymentDate(),
                        payment.getCategory1(), payment.getCategory2(), payment.getAmount(),
                        payment.getMerchantName(), payment.getReceivedBenefit(), payment.getBusinessNumber()))
                .toList();
    }

    /**
     * '결제내역 모아보기'(§13-11) — 카드 구분 없이 최근 {@code monthsBack}개월 결제를 한 화면에 최신순으로.
     * 각 결제에 어느 카드(실카드명·색·카드사)인지 붙여, 여러 카드의 6개월치를 통합해 보여준다.
     */
    @Transactional(readOnly = true)
    public List<PaymentHistoryRow> allPayments(Long userId, int monthsBack) {
        LocalDateTime from = referenceDate().minusMonths(monthsBack).atStartOfDay();
        Map<String, UserCard> bySerial = userCardRepository.findByUserIdOrderByIdAsc(userId).stream()
                .collect(Collectors.toMap(UserCard::getSerialNumber, c -> c, (a, b) -> a));
        return userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(payment -> !payment.getPaymentDate().isBefore(from))
                .map(payment -> {
                    UserCard card = bySerial.get(payment.getCardSerial());
                    return new PaymentHistoryRow(payment.getPaymentId(), payment.getPaymentDate(),
                            payment.getCategory1(), payment.getCategory2(), payment.getAmount(),
                            payment.getMerchantName(), payment.getReceivedBenefit(),
                            card != null ? card.getCardName() : null,
                            card != null ? card.getCardColor() : null,
                            card != null ? card.getCompanyName() : null,
                            payment.getBusinessNumber());
                })
                .toList();
    }

    /** 실적 요건 = 카드 혜택 구간의 하한 중 최솟값(양수). 없으면 0(조건 없음). */
    private static int requirementOf(CardView card) {
        return card.cardProduct().benefits().stream()
                .map(BenefitView::performanceStart)
                .filter(start -> start > 0)
                .min(Integer::compareTo)
                .orElse(0);
    }

    /** 연동 가능 은행 목록(프록시) — 자산연결 화면용. */
    @Transactional(readOnly = true)
    public List<BankView> banks() {
        return myDataClient.findBanks();
    }

    /** 연동한 은행 목록 — '연결 관리' 화면이 카드사와 함께 보여준다. */
    @Transactional(readOnly = true)
    public List<UserBank> linkedBanks(Long userId) {
        return userBankRepository.findByUserIdOrderByBankIdAsc(userId);
    }

    public record LinkResult(int cardCount, int paymentCount, int bankCount) {}

    public record MyCardView(String serialNumber, Long cardCode, String cardName, String cardColor,
                             String companyName, int requirement, int currentPerformance,
                             boolean requirementMet, int toRequirement, int earnedThisMonth) {}

    public record PaymentRow(String paymentId, java.time.LocalDateTime date, String category1,
                             String category2, int amount, String merchantName, int receivedBenefit,
                             String businessNumber) {}

    /** 결제내역 모아보기 1건 — 결제 정보 + 어느 카드(실카드명·색·카드사)인지 + 가맹점 사업자번호. */
    public record PaymentHistoryRow(String paymentId, java.time.LocalDateTime date, String category1,
                                    String category2, int amount, String merchantName, int receivedBenefit,
                                    String cardName, String cardColor, String companyName,
                                    String businessNumber) {}
}
