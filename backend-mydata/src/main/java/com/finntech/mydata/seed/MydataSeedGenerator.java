package com.finntech.mydata.seed;

import com.finntech.mydata.domain.*;
import com.finntech.mydata.repository.*;
import com.finntech.mydata.util.Ci;
import com.finntech.mydata.util.Msisdn;
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

    /**
     * 이름은 {@link com.finntech.mydata.util.KoreanName} 이 만든다.
     *
     * <p>예전에는 고정 12개를 여기 적어 뒀다. 그러면 ① 사용자를 12명 넘게 만들면 이름 뒤에
     * 번호가 붙어({@code 김민준12}) 사람 이름이 아니게 되고 ② 성별세대코드와 이름이 따로 놀아
     * 「김민준(여)」이 나왔다. 이제 주민등록번호를 <b>먼저</b> 뽑고 그 성별로 이름을 만든다.
     */
    private static final com.finntech.mydata.util.KoreanName NAMES =
            com.finntech.mydata.util.KoreanName.instance();

    private final MyDataUserRepository userRepo;
    private final MyDataCardRepository cardRepo;
    private final MyDataPaymentRepository paymentRepo;
    private final CardCompanyRepository companyRepo;
    private final CardProductRepository productRepo;
    /** 업종코드 → 중분류. 혜택이 중분류에 걸려 있어 결제(업종코드)와 맞추려면 필요하다. */
    private final com.finntech.mydata.generation.IndustryCategoryMap ksicToMid;

    private final boolean enabled;
    private final String mode;
    private final boolean generationEnabled;
    private final long seed;
    private final LocalDate referenceDate;
    private final int windowDays;
    private final int users;
    private final int cardsMin, cardsMax, paymentsMin, paymentsMax;

    public MydataSeedGenerator(MyDataUserRepository userRepo, MyDataCardRepository cardRepo,
                               MyDataPaymentRepository paymentRepo, CardCompanyRepository companyRepo,
                               CardProductRepository productRepo,
                               com.finntech.mydata.generation.IndustryCategoryMap ksicToMid,
                               @Value("${mydata.seed.enabled:true}") boolean enabled,
                               @Value("${mydata.seed.mode:keep}") String mode,
                               @Value("${mydata.generation.enabled:false}") boolean generationEnabled,
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
        this.ksicToMid = ksicToMid;
        this.enabled = enabled;
        this.mode = mode;
        this.generationEnabled = generationEnabled;
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
        // 향후 페르소나 대량 생성(§13-11)이 켜지면 내장 시드는 데이터를 만들지 않는다(외부 파이프라인이 소유).
        if (generationEnabled) {
            log.info("페르소나 생성 모드(mydata.generation.enabled=true) — 내장 시드 건너뜀");
            return;
        }
        Random rnd = new Random(seed);

        // 1) 카탈로그(카드사·카드상품·혜택) — 없을 때만 생성. 페르소나를 바꿔도 카탈로그는 재사용.
        if (companyRepo.count() == 0) {
            Map<String, CardCompany> companies = new LinkedHashMap<>();
            for (String name : Catalog.COMPANIES) {
                companies.put(name, companyRepo.save(new CardCompany(name, "/img/company/" + name + ".png")));
            }
            for (Catalog.CardDef cardDef : Catalog.CARD_DEFS) {
                CardProduct product = new CardProduct(cardDef.name(), "/img/card/" + cardDef.name() + ".png",
                        cardDef.color(), companies.get(cardDef.company()));
                for (Catalog.BenefitDef benefitDef : cardDef.benefits()) {
                    product.addBenefit(new CardBenefit(product, benefitDef.midCategory(), benefitDef.percent(),
                            benefitDef.perfStart(), benefitDef.perfEnd(), benefitDef.monthlyLimit()));
                }
                productRepo.save(product);
            }
        }
        List<CardProduct> products = productRepo.findAllByOrderByCodeAsc();

        // 2) 사용자 데이터 — replace면 기존을 지우고 새로(페르소나 교체 대비), keep이고 이미 있으면 건너뜀.
        if ("replace".equalsIgnoreCase(mode)) {
            paymentRepo.deleteAllInBatch();
            cardRepo.deleteAllInBatch();
            userRepo.deleteAllInBatch();
            log.info("replace 모드 — 기존 마이데이터 사용자·카드·결제 삭제 후 재생성");
        } else if (userRepo.count() > 0) {
            log.info("마이데이터 시드 존재 — 생성 건너뜀 (users={})", userRepo.count());
            return;
        }

        // 3) 사용자 → 카드 → 결제내역
        int paymentCounter = 0;
        List<String> demoIdentities = new ArrayList<>();
        for (int userIndex = 0; userIndex < users; userIndex++) {
            // 주민등록번호를 먼저 뽑는다 — 7번째 자리가 이름의 성별을 정한다.
            String social7 = generateBirth7(rnd);
            String name = NAMES.full(rnd, social7.charAt(6));
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
                    String industryCode = Catalog.INDUSTRY_CODES.get(rnd.nextInt(Catalog.INDUSTRY_CODES.size()));
                    List<String> subCategories = Catalog.CONTEXTS_BY_INDUSTRY.get(industryCode);
                    String category2 = subCategories.get(rnd.nextInt(subCategories.size()));
                    List<String> merchants = Catalog.MERCHANTS.get(category2);
                    String merchant = merchants.get(rnd.nextInt(merchants.size()));
                    int amount = (3 + rnd.nextInt(48)) * 1000; // 3천~50천
                    LocalDateTime paidAt = referenceDate.atStartOfDay()
                            .minusDays(rnd.nextInt(windowDays))
                            .plusHours(8 + rnd.nextInt(14))
                            .plusMinutes(rnd.nextInt(60));
                    int benefit = calculateBenefit(product, industryCode, amount, prevMonthAmount);
                    paymentRepo.save(new MyDataPayment("pay-" + (paymentCounter++), card, paidAt,
                            industryCode, category2, amount, merchant, benefit));
                }
            }
        }
        log.info("마이데이터 시드 생성 완료 — 카드사 {}, 카드상품 {}, 사용자 {}, 결제 {}건",
                companyRepo.count(), products.size(), users, paymentCounter);
        log.info("데모용 신원(앞 3명, 본체 본인인증 입력용): {}", demoIdentities);
    }

    /**
     * 카드 상품의 혜택에서 결제 1건의 받은 혜택 계산(실적구간 대조).
     *
     * <p>혜택은 <b>우리 중분류</b>에 걸려 있고 결제는 <b>업종코드</b>를 갖는다.
     * 그래서 대조표(industry-mid.json)를 한 번 거친다 — 카드사가 502개 업종코드를 각각
     * 정의하지 않아도 되고, 실제 카드 혜택도 소비자가 아는 묶음 단위로 준다.
     */
    private int calculateBenefit(CardProduct product, String industryCode, int amount, int prevMonthAmount) {
        String mid = ksicToMid.midOf(industryCode);
        if (mid == null) return 0;
        for (CardBenefit benefit : product.getBenefits()) {
            if (benefit.getMidCategory().equals(mid) && benefit.coversPerformance(prevMonthAmount)) {
                int raw = (int) ((long) amount * benefit.getDiscountPercent() / 100);
                return Math.min(raw, benefit.getMonthlyLimit());
            }
        }
        return 0;
    }

    /** 주민번호 앞 7자리(YYMMDD + 성별세대) 생성. 결정론. */
    private static String generateBirth7(Random rnd) {
        int year = 1987 + rnd.nextInt(20);   // 1987~2006 — regen-mydata-identity.py 와 같은 범위
        int month = 1 + rnd.nextInt(12);
        int day = 1 + rnd.nextInt(28);
        int gender = isYear2000s(year) ? (rnd.nextBoolean() ? 3 : 4) : (rnd.nextBoolean() ? 1 : 2);
        return String.format("%02d%02d%02d%d", year % 100, month, day, gender);
    }

    private static boolean isYear2000s(int year) { return year >= 2000; }

    /**
     * 국번은 <b>실제로 배정된 대역에서만</b> 뽑는다({@link Msisdn}). 예전에는 난수 4자리라
     * 0xxx·1xxx 같은 존재하지 않는 번호가 26%나 섞였고, 온보딩이 국번을 검증하면서
     * 그 신원은 로그인 자체가 막혔다.
     */
    private static String generatePhone(Random rnd) {
        return String.format("010%04d%04d", Msisdn.randomAssigned(rnd), rnd.nextInt(10000));
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
