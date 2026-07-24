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
    private final ReportRepository reportRepository;
    private final LocalDate referenceDate;

    public MyDataLinkService(MyDataClient myDataClient, AppUserRepository userRepository,
                             UserCardRepository userCardRepository, UserPaymentRepository userPaymentRepository,
                             ConsumptionRepository consumptionRepository, CategoryRepository categoryRepository,
                             UserCardCompanyRepository userCardCompanyRepository, ReportRepository reportRepository,
                             @Value("${finntech.mydata.reference-date:2026-07-21}") String referenceDate) {
        this.myDataClient = myDataClient;
        this.userRepository = userRepository;
        this.userCardRepository = userCardRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.userCardCompanyRepository = userCardCompanyRepository;
        this.reportRepository = reportRepository;
        this.referenceDate = LocalDate.parse(referenceDate);
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
        reportRepository.deleteByUserId(userId);   // 판정 소스(ML)·데이터가 바뀌므로 리포트 캐시 무효화

        YearMonth referenceMonth = YearMonth.from(referenceDate);
        LocalDateTime linkTime = referenceDate.atTime(23, 59, 59); // 이후 결제는 다음 동기화에서 증분 등장(W2)
        int cardCount = 0, paymentCount = 0;

        for (Long companyId : companyIds) {
            String companyName = null;
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
                    userPaymentRepository.save(new UserPayment(payment.id(), userId, card.cardId(),
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
                }
            }
            if (companyName != null) { // 카드가 있던 카드사만 연동 기록(다음 동기화 증분 기준)
                userCardCompanyRepository.save(
                        new UserCardCompany(userId, companyId, companyName, linkTime, linkTime));
            }
        }
        // 입출금 통장의 월급을 앱 사용자 월급(=예산)으로 반영(§13-11 경제 모델). 통장 없으면 기존값 유지.
        MyDataResponses.AccountView account = myDataClient.findAccount(ci);
        if (account != null && account.salary() > 0) {
            user.setMonthlyIncome(BigDecimal.valueOf(account.salary()));
            userRepository.save(user);
        }
        log.info("마이데이터 연동 완료 — userId={} 카드사 {}개, 카드 {}장, 결제 {}건 적재",
                userId, companyIds.size(), cardCount, paymentCount);
        return new LinkResult(cardCount, paymentCount);
    }

    /** 가맹점 조회(번호→주소) — 결제에 실린 사업자번호로 가맹점명·지번주소를 제공자에서 조회(프록시). 없으면 null. */
    @Transactional(readOnly = true)
    public MyDataResponses.MerchantView merchant(String businessNumber) {
        return myDataClient.findMerchant(businessNumber);
    }

    /** 입출금 통장 조회(§13-11) — 프론트 통장 화면용. 사용자의 CI로 제공자에 프록시. */
    @Transactional(readOnly = true)
    public MyDataResponses.AccountView account(Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) return null;
        return myDataClient.findAccount(ci);
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
                    if (userPaymentRepository.existsById(payment.id())) continue; // 멱등
                    userPaymentRepository.save(new UserPayment(payment.id(), userId, card.cardId(),
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
        log.info("마이데이터 증분 동기화 — userId={} 신규 결제 {}건", userId, added);
        return new SyncResult(added);
    }

    public record SyncResult(int newPayments) {}

    /** '내 카드' 화면 — 카드별 실적 진행률 + 이번달 받은 혜택. */
    @Transactional(readOnly = true)
    public List<MyCardView> myCards(Long userId) {
        YearMonth referenceMonth = YearMonth.from(referenceDate);
        List<MyCardView> views = new ArrayList<>();
        for (UserCard card : userCardRepository.findByUserIdOrderByIdAsc(userId)) {
            int earnedThisMonth = userPaymentRepository
                    .findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, card.getSerialNumber()).stream()
                    .filter(payment -> YearMonth.from(payment.getPaymentDate()).equals(referenceMonth))
                    .mapToInt(UserPayment::getReceivedBenefit).sum();
            boolean requirementMet = card.getRequirement() == 0
                    || card.getCurrentPerformance() >= card.getRequirement();
            int toRequirement = Math.max(0, card.getRequirement() - card.getCurrentPerformance());
            views.add(new MyCardView(card.getSerialNumber(), card.getCardCode(), card.getCardName(),
                    card.getCardColor(), card.getCompanyName(), card.getRequirement(),
                    card.getCurrentPerformance(), requirementMet, toRequirement, earnedThisMonth));
        }
        return views;
    }

    /** '내 카드' 상세 — 카드 결제내역(최신순). */
    @Transactional(readOnly = true)
    public List<PaymentRow> cardPayments(Long userId, String cardSerial) {
        return userPaymentRepository.findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, cardSerial).stream()
                .map(payment -> new PaymentRow(payment.getPaymentId(), payment.getPaymentDate(),
                        payment.getCategory1(), payment.getCategory2(), payment.getAmount(),
                        payment.getMerchantName(), payment.getReceivedBenefit()))
                .toList();
    }

    /**
     * '결제내역 모아보기'(§13-11) — 카드 구분 없이 최근 {@code monthsBack}개월 결제를 한 화면에 최신순으로.
     * 각 결제에 어느 카드(실카드명·색·카드사)인지 붙여, 여러 카드의 6개월치를 통합해 보여준다.
     */
    @Transactional(readOnly = true)
    public List<PaymentHistoryRow> allPayments(Long userId, int monthsBack) {
        LocalDateTime from = referenceDate.minusMonths(monthsBack).atStartOfDay();
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
                            card != null ? card.getCompanyName() : null);
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

    public record LinkResult(int cardCount, int paymentCount) {}

    public record MyCardView(String serialNumber, Long cardCode, String cardName, String cardColor,
                             String companyName, int requirement, int currentPerformance,
                             boolean requirementMet, int toRequirement, int earnedThisMonth) {}

    public record PaymentRow(String paymentId, java.time.LocalDateTime date, String category1,
                             String category2, int amount, String merchantName, int receivedBenefit) {}

    /** 결제내역 모아보기 1건 — 결제 정보 + 어느 카드(실카드명·색·카드사)인지. */
    public record PaymentHistoryRow(String paymentId, java.time.LocalDateTime date, String category1,
                                    String category2, int amount, String merchantName, int receivedBenefit,
                                    String cardName, String cardColor, String companyName) {}
}
