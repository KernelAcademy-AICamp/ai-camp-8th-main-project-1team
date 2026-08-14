package com.finntech.service;

import com.finntech.config.CardRecommendProperties;
import com.finntech.domain.CardAnnualFee;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitCap;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardCombinedCap;
import com.finntech.domain.CardExclusion;
import com.finntech.domain.CardPerformanceTier;
import com.finntech.domain.CardProduct;
import com.finntech.domain.Enums;
import com.finntech.domain.UserPayment;
import com.finntech.engine.AnalysisResult;
import com.finntech.engine.CardExclusionPolicy;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CardProductRepository;
import com.finntech.repository.UserPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 카드 추천 — <b>순위의 근거가 숫자로 설명되는지</b>를 고정한다.
 *
 * <p>이 화면이 잘못되는 방식은 크래시가 아니다. 그럴듯한 순서가 나오는데 근거가 없거나,
 * 같은 돈을 두 번 세어 절감액이 부풀거나, 연회비를 빼지 않고 "아껴요"라고 말하는 식이다.
 * 셋 다 화면만 봐서는 안 보인다.
 *
 * <p>카드가 실제 상품이 되면서 틀릴 자리가 넷 늘었다 — <b>실적 제외를 안 빼서 구간이 잘못
 * 열리는 것</b>, <b>한도를 무시하고 요율만 곱하는 것</b>, <b>개별 한도만 자르고 통합한도를
 * 잊는 것</b>, <b>브랜드와 축이 같은 돈을 두 번 가져가는 것</b>. 여기서 그 넷을 못 박는다.
 *
 * <p>업종코드는 실제 표({@code industry-mid.json})를 쓴다 — 축 이름을 시험에 박으면 표가
 * 바뀌었을 때 시험만 초록으로 남는다.
 */
class CardRecommendServiceTest {

    /** 실제 대조표를 쓴다. 축 이름을 손으로 적지 않기 위해서다. */
    private final IndustryCategoryMapper industries = new IndustryCategoryMapper(new ObjectMapper());
    private final CardExclusionPolicy exclusionPolicy = new CardExclusionPolicy(new ObjectMapper());

    /** 대중교통 축인 업종코드 — 시내버스. */
    private static final String TRANSIT = "602103";
    /** 카페/디저트 축인 업종코드를 표에서 찾아 쓴다. */
    private final String cafe = codeOfAxis("카페/디저트");
    private final String mart = codeOfAxis("마트");

    private String codeOfAxis(String axis) {
        for (int code = 100000; code < 999999; code++) {
            String s = String.valueOf(code);
            if (axis.equals(industries.cardAxisOf(s))) return s;
        }
        throw new IllegalStateException(axis + " 축인 업종코드를 표에서 못 찾았다");
    }

    // ── 재료 ────────────────────────────────────────────────────────────────

    private UserPayment payment(String ksic, String merchant, int amount) {
        UserPayment p = mock(UserPayment.class);
        when(p.getKsicCode()).thenReturn(ksic);
        when(p.getMerchantName()).thenReturn(merchant);
        when(p.getAmount()).thenReturn(amount);
        return p;
    }

    private CardProduct card(String name) {
        CardProduct c = new CardProduct("시험카드사", name, name,
                CardProduct.CardType.CREDIT, CardProduct.Status.ACTIVE,
                CardProduct.BenefitStyle.DISCOUNT_POINT);
        c.grade(CardProduct.Grade.PRECISE, null);
        return c;
    }

    private CardBenefit benefit(String group, String rate, CardPerformanceTier requires) {
        CardBenefit b = new CardBenefit(group, CardBenefit.Kind.DISCOUNT,
                CardBenefit.Scope.BRAND, 0);
        b.rate(new BigDecimal(rate), null, null, null);
        b.conditions(requires, null, "원", null, true, null, true, false, null, null, "결제일 할인");
        return b;
    }

    private CardRecommendService service(List<CardProduct> catalog, List<UserPayment> lastMonth) {
        CardProductRepository cards = mock(CardProductRepository.class);
        when(cards.findRecommendable()).thenReturn(catalog);
        UserPaymentRepository payments = mock(UserPaymentRepository.class);
        when(payments.findInPeriod(any(), any(), any())).thenReturn(lastMonth);
        return new CardRecommendService(new CardRecommendProperties(), cards, payments,
                industries, exclusionPolicy);
    }

    /** 카페 월 50,000 · 대중교통 월 300,000 (관측 1개월). */
    private AnalysisResult analysis() {
        Map<String, AnalysisResult.CategoryStat> stats = new TreeMap<>();
        stats.put("카페/간식", stat("카페/간식", 50_000, 20));
        stats.put("교통/자동차", stat("교통/자동차", 300_000, 30));
        Map<String, BigDecimal> monthly = new TreeMap<>();
        monthly.put("2026-06", new BigDecimal("350000"));
        return new AnalysisResult(1L, stats, new BigDecimal("350000"), List.of(),
                List.of("교통/자동차", "카페/간식"), 0.0, false, List.of(), monthly,
                BigDecimal.ZERO, Enums.DataSourceMode.CONFIRMED, 0, null);
    }

    private AnalysisResult.CategoryStat stat(String code, long amount, long count) {
        return new AnalysisResult.CategoryStat(code, code, BigDecimal.valueOf(amount),
                0.0, count, true, 1, 30);
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 15, 10, 0);

    // ── 시험 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("실적 제외를 빼고 구간을 연다 — 안 빼면 못 채운 구간이 열린다")
    void subtractsPerformanceExclusions() {
        // 전달 소비 35만 = 카페 5만 + 대중교통 30만. 실적 조건은 30만이다.
        //   대중교통을 빼는 카드  → 실적 5만  → 구간 안 열림 → 혜택 0
        //   안 빼는 카드         → 실적 35만 → 구간 열림   → 카페 5만 × 10% = 5,000/월
        CardProduct excludes = card("교통제외");
        CardPerformanceTier t1 = new CardPerformanceTier(1, 300_000);
        excludes.add(t1);
        excludes.add(new CardExclusion(CardExclusion.Axis.PERFORMANCE, "TRANSIT", "대중교통"));
        CardBenefit b1 = benefit("카페", "10", t1);
        b1.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        excludes.add(b1);

        CardProduct keeps = card("교통포함");
        CardPerformanceTier t2 = new CardPerformanceTier(1, 300_000);
        keeps.add(t2);
        CardBenefit b2 = benefit("카페", "10", t2);
        b2.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        keeps.add(b2);

        List<UserPayment> spend = List.of(
                payment(cafe, "스타벅스 강남점", 50_000),
                payment(TRANSIT, "서울버스", 300_000));

        var offers = service(List.of(excludes, keeps), spend).recommend(analysis(), NOW).offers();

        assertThat(offers).extracting(CardRecommendService.Offer::name)
                .as("실적 제외를 뺀 카드는 구간이 안 열려 절감액이 0 이다")
                .containsExactly("교통포함", "교통제외");
        assertThat(offers.get(0).yearlySaving()).isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(offers.get(1).yearlySaving()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("실적 충족은 한도를 여는 것이지 한도를 받는 게 아니다")
    void capOpensButDoesNotPay() {
        // 커피 20,000 × 7% = 1,400. 한도가 5,000이어도 받는 건 1,400이다.
        CardProduct c = card("한도카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b = benefit("카페", "7", tier);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        b.add(new CardBenefitCap(tier, 5_000));
        c.add(b);

        var offer = service(List.of(c), List.of(payment(cafe, "스타벅스 강남점", 20_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("1,400 × 12 = 16,800")
                .isEqualByComparingTo(new BigDecimal("16800"));
    }

    @Test
    @DisplayName("한도를 넘으면 한도까지만 — 요율만 곱하면 부풀어 오른다")
    void truncatesAtMonthlyCap() {
        // 커피 200,000 × 7% = 14,000 인데 한도가 5,000이다.
        CardProduct c = card("한도카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b = benefit("카페", "7", tier);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        b.add(new CardBenefitCap(tier, 5_000));
        c.add(b);

        var offer = service(List.of(c), List.of(payment(cafe, "스타벅스 강남점", 200_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("5,000 × 12 = 60,000")
                .isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    @DisplayName("통합한도가 개별 한도 합보다 작으면 통합한도가 이긴다 — 페이북 실측 구조")
    void truncatesAtCombinedCap() {
        // 두 묶음이 각 5,000 한도인데 통합은 8,000. 개별로만 자르면 10,000 이 된다.
        CardProduct c = card("통합한도카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        for (String[] pair : new String[][]{{"종합몰", "쿠팡"}, {"패션몰", "무신사"}}) {
            CardBenefit b = new CardBenefit(pair[0], CardBenefit.Kind.POINT, CardBenefit.Scope.BRAND, 0);
            b.rate(new BigDecimal("10"), null, null, null);
            b.conditions(tier, "특별적립", "원", null, true, null, true, false, null, null, null);
            b.add(new CardBenefitTarget(pair[0], CardBenefitTarget.Kind.BRAND, pair[1], null, null, null));
            b.add(new CardBenefitCap(tier, 5_000));
            c.add(b);
        }
        c.add(new CardCombinedCap("특별적립", tier, 8_000));

        var offer = service(List.of(c), List.of(
                payment(mart, "쿠팡", 100_000), payment(mart, "무신사", 100_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("통합 8,000 × 12 = 96,000 (개별 합 10,000 이 아니다)")
                .isEqualByComparingTo(new BigDecimal("96000"));
    }

    @Test
    @DisplayName("브랜드로 걸린 소비를 축에서 또 세지 않는다")
    void doesNotDoubleCountBrandAndAxis() {
        // 카페 소비 100,000 이 전부 스타벅스다. 브랜드 10% + 축 10% 를 둘 다 주면 20,000 이 된다.
        CardProduct c = card("겹침카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit brand = benefit("브랜드", "10", tier);
        brand.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(brand);
        CardBenefit axis = benefit("업종", "10", tier);
        axis.add(new CardBenefitTarget("카페", CardBenefitTarget.Kind.AXIS, "카페/디저트", null, null, null));
        c.add(axis);

        var offer = service(List.of(c), List.of(payment(cafe, "스타벅스 강남점", 100_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("10,000 × 12 — 같은 돈을 두 번 아끼지 않는다")
                .isEqualByComparingTo(new BigDecimal("120000"));
    }

    @Test
    @DisplayName("업종코드를 몰라도 브랜드는 걸린다 — 축 실패가 브랜드를 죽이면 안 된다")
    void unknownIndustryStillMatchesBrand() {
        // 2026-08-13 실측 사고. 승인내역의 업종코드가 축 표와 자릿수가 안 맞아 전건이 null 이 됐고,
        // fold() 가 그 결제를 통째로 버려 **브랜드 매칭까지 같이 죽었다**. 결제 248건이 있는데도
        // 추천이 전부 0 원으로 나갔다. 브랜드는 가맹점명으로 거는 것이라 업종코드와 무관해야 한다.
        CardProduct c = card("브랜드카드");
        CardBenefit b = benefit("커피", "10", null);      // 실적 조건 없음
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);

        String unknownKsic = "9999";                     // 축 표에 없는 코드(자릿수부터 다르다)
        assertThat(industries.cardAxisOf(unknownKsic)).as("표에 없는 코드여야 시험이 성립한다").isNull();

        var offer = service(List.of(c), List.of(payment(unknownKsic, "스타벅스 강남점", 100_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("100,000 × 10% × 12 — 축을 몰라도 브랜드로 걸린다")
                .isEqualByComparingTo(new BigDecimal("120000"));
    }

    @Test
    @DisplayName("연회비를 뺀 값을 '아껴요'라고 말한다")
    void subtractsAnnualFee() {
        CardProduct c = card("연회비카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b = benefit("카페", "10", tier);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);
        c.add(new CardAnnualFee(CardAnnualFee.Scope.DOMESTIC, "BC", 15_000, 10_000, 5_000));
        c.add(new CardAnnualFee(CardAnnualFee.Scope.GLOBAL, "Mastercard", 25_000, null, null));

        var offer = service(List.of(c), List.of(payment(cafe, "스타벅스 강남점", 100_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("120,000 − 국내전용 연회비 15,000 (해외겸용 25,000 이 아니다)")
                .isEqualByComparingTo(new BigDecimal("105000"));
    }

    @Test
    @DisplayName("셀 수 없는 혜택은 절감액에 안 넣는다 — 무이자할부·해외·간편결제")
    void skipsUncountableBenefits() {
        CardProduct c = card("셀수없는카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b = new CardBenefit("해외", CardBenefit.Kind.POINT, CardBenefit.Scope.ALL, 0);
        b.rate(new BigDecimal("50"), null, null, null);
        b.conditions(tier, null, "원", null, true, null, false, false, null, null, null);
        b.add(new CardBenefitTarget("해외", CardBenefitTarget.Kind.AXIS, "카페/디저트", null, null, null));
        c.add(b);

        var offer = service(List.of(c), List.of(payment(cafe, "스타벅스 강남점", 100_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.yearlySaving()).as("countable=false 는 표시만 하고 계산에서 뺀다")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("공시 기준일을 응답에 싣는다 — 혜택 개정 추적이 없어 이것이 유일한 방어다")
    void carriesAsOf() {
        CardProduct c = card("기준일카드");
        c.describe(null, false, java.time.LocalDate.of(2025, 11, 7), "심의필 제2025호",
                null, null, null, null);

        var offer = service(List.of(c), List.of()).recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.asOf()).isEqualTo("2025-11-07");
    }

    @Test
    @DisplayName("근거가 되는 소비 요약을 같은 응답에 싣는다")
    void carriesEvidence() {
        var result = service(List.of(card("아무카드")), List.of()).recommend(analysis(), NOW);

        // 순위와 근거가 한 화면에 있어야 검증이 된다 — 근거 없는 순위는 광고다.
        assertThat(result.summary()).extracting(CardRecommendService.Summary::displayName)
                .containsExactly("교통/자동차", "카페/간식");
        assertThat(result.periodLabel()).isEqualTo("2026.06 ~ 2026.06");
        assertThat(result.performanceMonth()).as("어느 달을 실적으로 셌는지").isEqualTo("2026.06");
    }

    @Test
    @DisplayName("소비가 없어도 터지지 않는다 — 빈 요약과 0원")
    void emptyAnalysis() {
        AnalysisResult empty = new AnalysisResult(1L, new TreeMap<>(), BigDecimal.ZERO, List.of(),
                List.of(), 0.0, false, List.of(), new TreeMap<>(), BigDecimal.ZERO,
                Enums.DataSourceMode.ESTIMATED, 0, null);

        var result = service(List.of(card("아무카드")), new ArrayList<>()).recommend(empty, NOW);

        assertThat(result.summary()).isEmpty();
        assertThat(result.periodLabel()).isEmpty();
        assertThat(result.offers().get(0).yearlySaving()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
