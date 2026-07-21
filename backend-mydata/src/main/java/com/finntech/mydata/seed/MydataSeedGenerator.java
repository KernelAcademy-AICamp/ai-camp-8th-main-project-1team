package com.finntech.mydata.seed;

import com.finntech.mydata.domain.*;
import com.finntech.mydata.repository.*;
import com.finntech.mydata.util.Ci;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 결정론 더미 생성기 (마스터 §13-5, 규칙 3 재현성).
 * 시드+기준일이 같으면 항상 같은 사용자·카드·결제내역을 만든다. now()·Math.random() 미사용.
 * Faker류 무작위 생성을 결정론 Java로 구현한다(무시드·now()는 규칙 3 위반이라 금지).
 */
@Component
@Order(1)
public class MydataSeedGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MydataSeedGenerator.class);

    /** 결정론 이름 풀. */
    private static final List<String> NAMES = List.of(
            "김민준", "이서연", "박도윤", "최지우", "정하은", "강시우",
            "조수아", "윤예준", "임지호", "한서윤", "오지훈", "신유진");

    private final MyDataUserRepository userRepo;
    private final MyDataCardRepository cardRepo;
    private final MyDataPaymentRepository paymentRepo;
    private final CardCompanyRepository companyRepo;
    private final CardProductRepository productRepo;

    private final boolean enabled;
    private final long seed;
    private final LocalDate referenceDate;
    private final int windowDays;
    private final int users;
    private final int cardsMin, cardsMax, paymentsMin, paymentsMax;

    public MydataSeedGenerator(MyDataUserRepository userRepo, MyDataCardRepository cardRepo,
                               MyDataPaymentRepository paymentRepo, CardCompanyRepository companyRepo,
                               CardProductRepository productRepo,
                               @Value("${mydata.seed.enabled:true}") boolean enabled,
                               @Value("${mydata.seed.seed:20260721}") long seed,
                               @Value("${mydata.seed.reference-date:2026-07-21}") String referenceDate,
                               @Value("${mydata.seed.payment-window-days:120}") int windowDays,
                               @Value("${mydata.seed.users:12}") int users,
                               @Value("${mydata.seed.cards-per-user-min:3}") int cardsMin,
                               @Value("${mydata.seed.cards-per-user-max:6}") int cardsMax,
                               @Value("${mydata.seed.payments-per-card-min:20}") int paymentsMin,
                               @Value("${mydata.seed.payments-per-card-max:60}") int paymentsMax) {
        this.userRepo = userRepo;
        this.cardRepo = cardRepo;
        this.paymentRepo = paymentRepo;
        this.companyRepo = companyRepo;
        this.productRepo = productRepo;
        this.enabled = enabled;
        this.seed = seed;
        this.referenceDate = LocalDate.parse(referenceDate);
        this.windowDays = windowDays;
        this.users = users;
        this.cardsMin = cardsMin;
        this.cardsMax = cardsMax;
        this.paymentsMin = paymentsMin;
        this.paymentsMax = paymentsMax;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) return;
        if (companyRepo.count() > 0) {
            log.info("마이데이터 시드 존재 — 생성 건너뜀 (users={})", userRepo.count());
            return;
        }
        Random rnd = new Random(seed);

        // 1) 카드사 + 카드 상품 + 혜택
        Map<String, CardCompany> companies = new LinkedHashMap<>();
        for (String name : Catalog.COMPANIES) {
            companies.put(name, companyRepo.save(new CardCompany(name, "/img/company/" + name + ".png")));
        }
        List<CardProduct> products = new ArrayList<>();
        for (Catalog.CardDef cardDef : Catalog.CARD_DEFS) {
            CardProduct product = new CardProduct(cardDef.name(), "/img/card/" + cardDef.name() + ".png",
                    cardDef.color(), companies.get(cardDef.company()));
            for (Catalog.BenefitDef benefitDef : cardDef.benefits()) {
                product.addBenefit(new CardBenefit(product, benefitDef.category1(), benefitDef.percent(),
                        benefitDef.perfStart(), benefitDef.perfEnd(), benefitDef.monthlyLimit()));
            }
            products.add(productRepo.save(product));
        }

        // 2) 사용자 → 카드 → 결제내역
        int paymentCounter = 0;
        List<String> demoIdentities = new ArrayList<>();
        for (int userIndex = 0; userIndex < users; userIndex++) {
            String name = NAMES.get(userIndex % NAMES.size())
                    + (userIndex >= NAMES.size() ? String.valueOf(userIndex) : "");
            String social7 = generateBirth7(rnd);
            String phoneNumber = generatePhone(rnd);
            String ci = Ci.of(name, social7, phoneNumber);
            String fullSocial = social7 + generateDigits(rnd, 6);
            MyDataUser user = userRepo.save(new MyDataUser(ci, name, fullSocial, phoneNumber));
            if (userIndex < 3) {
                demoIdentities.add(name + " / " + social7 + " / " + phoneNumber
                        + " → CI " + ci.substring(0, 12) + "…");
            }

            int cardCount = cardsMin + rnd.nextInt(Math.max(1, cardsMax - cardsMin + 1));
            List<CardProduct> shuffled = new ArrayList<>(products);
            Collections.shuffle(shuffled, rnd);
            for (int cardIndex = 0; cardIndex < cardCount && cardIndex < shuffled.size(); cardIndex++) {
                CardProduct product = shuffled.get(cardIndex);
                String serialNumber = generateSerial(rnd);
                LocalDate expiration = referenceDate.plusYears(2 + rnd.nextInt(3)).withDayOfMonth(1);
                int prevMonthAmount = rnd.nextInt(650000); // 0~65만, 30/40만 실적 구간을 걸치게
                MyDataCard card = cardRepo.save(
                        new MyDataCard(serialNumber, user, product, expiration, prevMonthAmount));

                int paymentCount = paymentsMin + rnd.nextInt(Math.max(1, paymentsMax - paymentsMin + 1));
                for (int paymentIndex = 0; paymentIndex < paymentCount; paymentIndex++) {
                    String category1 = Catalog.CATEGORY1.get(rnd.nextInt(Catalog.CATEGORY1.size()));
                    List<String> subCategories = Catalog.CATEGORY_TREE.get(category1);
                    String category2 = subCategories.get(rnd.nextInt(subCategories.size()));
                    List<String> merchants = Catalog.MERCHANTS.get(category2);
                    String merchant = merchants.get(rnd.nextInt(merchants.size()));
                    int amount = (3 + rnd.nextInt(48)) * 1000; // 3천~50천
                    LocalDateTime paidAt = referenceDate.atStartOfDay()
                            .minusDays(rnd.nextInt(windowDays))
                            .plusHours(8 + rnd.nextInt(14))
                            .plusMinutes(rnd.nextInt(60));
                    int benefit = calculateBenefit(product, category1, amount, prevMonthAmount);
                    paymentRepo.save(new MyDataPayment("pay-" + (paymentCounter++), card, paidAt,
                            category1, category2, amount, merchant, benefit));
                }
            }
        }
        log.info("마이데이터 시드 생성 완료 — 카드사 {}, 카드상품 {}, 사용자 {}, 결제 {}건",
                companies.size(), products.size(), users, paymentCounter);
        log.info("데모용 신원(앞 3명, 본체 본인인증 입력용): {}", demoIdentities);
    }

    /** 카드 상품의 혜택에서 결제 1건의 받은 혜택 계산(실적구간 대조). */
    private static int calculateBenefit(CardProduct product, String category1, int amount, int prevMonthAmount) {
        for (CardBenefit benefit : product.getBenefits()) {
            if (benefit.getCategory1Name().equals(category1) && benefit.coversPerformance(prevMonthAmount)) {
                int raw = (int) ((long) amount * benefit.getDiscountPercent() / 100);
                return Math.min(raw, benefit.getMonthlyLimit());
            }
        }
        return 0;
    }

    /** 주민번호 앞 7자리(YYMMDD + 성별세대) 생성. 결정론. */
    private static String generateBirth7(Random rnd) {
        int year = 1970 + rnd.nextInt(36);   // 1970~2005
        int month = 1 + rnd.nextInt(12);
        int day = 1 + rnd.nextInt(28);
        int gender = isYear2000s(year) ? (rnd.nextBoolean() ? 3 : 4) : (rnd.nextBoolean() ? 1 : 2);
        return String.format("%02d%02d%02d%d", year % 100, month, day, gender);
    }

    private static boolean isYear2000s(int year) { return year >= 2000; }

    private static String generatePhone(Random rnd) {
        return String.format("010%04d%04d", rnd.nextInt(10000), rnd.nextInt(10000));
    }

    private static String generateSerial(Random rnd) {
        return String.format("%04d-%04d-%04d-%04d",
                rnd.nextInt(10000), rnd.nextInt(10000), rnd.nextInt(10000), rnd.nextInt(10000));
    }

    private static String generateDigits(Random rnd, int count) {
        StringBuilder buffer = new StringBuilder();
        for (int index = 0; index < count; index++) buffer.append(rnd.nextInt(10));
        return buffer.toString();
    }
}
