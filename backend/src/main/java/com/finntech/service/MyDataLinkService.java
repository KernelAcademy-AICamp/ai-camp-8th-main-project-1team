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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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
    private final LocalDate referenceDate;

    public MyDataLinkService(MyDataClient myDataClient, AppUserRepository userRepository,
                             UserCardRepository userCardRepository, UserPaymentRepository userPaymentRepository,
                             ConsumptionRepository consumptionRepository, CategoryRepository categoryRepository,
                             @Value("${finntech.mydata.reference-date:2026-07-21}") String referenceDate) {
        this.myDataClient = myDataClient;
        this.userRepository = userRepository;
        this.userCardRepository = userCardRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
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

        userCardRepository.deleteByUserId(userId);
        userPaymentRepository.deleteByUserId(userId);
        consumptionRepository.deleteByUserIdAndSource(userId, Enums.DataSource.MYDATA);

        YearMonth referenceMonth = YearMonth.from(referenceDate);
        int cardCount = 0, paymentCount = 0;

        for (Long companyId : companyIds) {
            for (CardView card : myDataClient.findCards(companyId, ci)) {
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
                            payment.amount(), payment.merchantName(), payment.receivedBenefitAmount()));
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
        }
        log.info("마이데이터 연동 완료 — userId={} 카드사 {}개, 카드 {}장, 결제 {}건 적재",
                userId, companyIds.size(), cardCount, paymentCount);
        return new LinkResult(cardCount, paymentCount);
    }

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
}
