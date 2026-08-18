package com.finntech.service;

import com.finntech.domain.CardAnnualFee;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitCap;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardCombinedCap;
import com.finntech.domain.CardExclusion;
import com.finntech.domain.CardPerformanceTier;
import com.finntech.domain.CardProduct;
import com.finntech.domain.SpendingLedger;
import com.finntech.engine.CardExclusionPolicy;
import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 절감액 계산 — <b>화면이 아니라 채점을 위한 자(尺)를 고정한다.</b>
 *
 * <p>화면은 금액을 말하지 않는다(`09_카드추천_판정.md` §1.1). 그런데도 이 시험들이 남아 있는
 * 이유는, 계산이 두 가지를 재는 유일한 방법이기 때문이다.
 *
 * <pre>
 *   파싱이 맞았나    월 최대 혜택을 비교 서비스 표시값과 대조한다
 *   추천이 이득인가   겹침 수로 세운 순위가 금액 순과 얼마나 어긋나는지 잰다
 * </pre>
 *
 * <p>여기서 못 박는 것은 공시 해석의 네 자리다 — <b>실적 제외를 안 빼서 구간이 잘못 열리는 것</b>,
 * <b>한도를 무시하고 요율만 곱하는 것</b>, <b>개별 한도만 자르고 통합한도를 잊는 것</b>,
 * <b>브랜드와 축이 같은 돈을 두 번 가져가는 것</b>. 넷 다 화면만 봐서는 안 보인다.
 *
 * <p>업종코드는 실제 표({@code industry-mid.json})를 쓴다 — 축 이름을 시험에 박으면 표가
 * 바뀌었을 때 시험만 초록으로 남는다.
 */
class CardBenefitEstimatorTest {

    private final IndustryCategoryMapper industries = new IndustryCategoryMapper(new ObjectMapper());
    private final CardExclusionPolicy exclusionPolicy = new CardExclusionPolicy(new ObjectMapper());
    private final CardBenefitEstimator estimator =
            new CardBenefitEstimator(new CardMatcher(), exclusionPolicy);

    /** 대중교통 축인 업종코드 — 시내버스. */
    private static final String TRANSIT = "602103";
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

    /**
     * 원장 한 줄. <b>③ 은 이제 소비 원장을 읽는다</b>(09 §2.2) — 브랜드·결제대행사 여부·
     * 국세청 업종코드를 ① 이 이미 붙여 둔다.
     *
     * @param ntsCode 국세청 업종코드. 축을 못 찾는 값을 넣으면 브랜드만으로 걸린다
     */
    private SpendingLedger payment(String ntsCode, String merchant, int amount) {
        return payment(ntsCode, merchant, amount, LocalDate.of(2026, 6, 1));
    }

    /** 같은 곳을 여러 날 간 것을 만들 때 쓴다 — 겹침은 날짜 수로 센다. */
    private SpendingLedger payment(String ntsCode, String merchant, int amount, LocalDate on) {
        SpendingLedger row = mock(SpendingLedger.class);
        when(row.getNtsIndustryCode()).thenReturn(ntsCode);
        when(row.getMerchantName()).thenReturn(merchant);
        when(row.getAmount()).thenReturn(amount);
        when(row.getPaidOn()).thenReturn(on);
        return row;
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

    /** 연 절감액. 화면에는 안 나가지만 채점의 기준값이다. */
    private BigDecimal saving(CardProduct card, List<SpendingLedger> spend) {
        return estimator.estimate(card, CardSpend.fold(spend, industries::cardAxisOf)).yearlySaving();
    }

    // ── 시험 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("실적 제외를 빼고 구간을 연다 — 안 빼면 못 채운 구간이 열린다")
    void subtractsPerformanceExclusions() {
        // 소비 35만 = 카페 5만 + 대중교통 30만. 실적 조건은 30만이다.
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

        List<SpendingLedger> spend = List.of(
                payment(cafe, "스타벅스 강남점", 50_000),
                payment(TRANSIT, "서울버스", 300_000));

        assertThat(saving(keeps, spend)).as("구간이 열려 5,000 × 12")
                .isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(saving(excludes, spend)).as("실적 제외를 뺀 카드는 구간이 안 열린다")
                .isEqualByComparingTo(BigDecimal.ZERO);
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

        assertThat(saving(c, List.of(payment(cafe, "스타벅스 강남점", 20_000))))
                .as("1,400 × 12 = 16,800").isEqualByComparingTo(new BigDecimal("16800"));
    }

    @Test
    @DisplayName("한도를 넘으면 한도까지만 — 요율만 곱하면 부풀어 오른다")
    void truncatesAtMonthlyCap() {
        CardProduct c = card("한도넘김카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b = benefit("카페", "7", tier);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        b.add(new CardBenefitCap(tier, 5_000));
        c.add(b);

        assertThat(saving(c, List.of(payment(cafe, "스타벅스 강남점", 200_000))))
                .as("14,000 이 아니라 한도 5,000 × 12")
                .isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    @DisplayName("통합한도가 개별 한도 합보다 작으면 통합한도가 이긴다 — 페이북 실측 구조")
    void truncatesAtCombinedCap() {
        // 개별 5,000 + 5,000 = 10,000 인데 통합한도는 8,000 이다.
        CardProduct c = card("통합한도카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b1 = benefit("종합몰", "10", tier);
        b1.conditions(tier, "쇼핑", "원", null, true, null, true, false, null, null, null);
        b1.add(new CardBenefitTarget("쇼핑", CardBenefitTarget.Kind.BRAND, "쿠팡", null, null, null));
        b1.add(new CardBenefitCap(tier, 5_000));
        CardBenefit b2 = benefit("패션몰", "10", tier);
        b2.conditions(tier, "쇼핑", "원", null, true, null, true, false, null, null, null);
        b2.add(new CardBenefitTarget("쇼핑", CardBenefitTarget.Kind.BRAND, "무신사", null, null, null));
        b2.add(new CardBenefitCap(tier, 5_000));
        c.add(b1);
        c.add(b2);
        c.add(new CardCombinedCap("쇼핑", tier, 8_000));

        assertThat(saving(c, List.of(payment(mart, "쿠팡", 100_000), payment(mart, "무신사", 100_000))))
                .as("10,000 이 아니라 통합한도 8,000 × 12")
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

        assertThat(saving(c, List.of(payment(cafe, "스타벅스 강남점", 100_000))))
                .as("10,000 × 12 — 같은 돈을 두 번 아끼지 않는다")
                .isEqualByComparingTo(new BigDecimal("120000"));
    }

    @Test
    @DisplayName("업종코드를 몰라도 브랜드는 걸린다 — 축 실패가 브랜드를 죽이면 안 된다")
    void unknownIndustryStillMatchesBrand() {
        // 2026-08-13 실측 사고. 승인내역의 업종코드가 축 표와 자릿수가 안 맞아 전건이 null 이 됐고,
        // fold() 가 그 결제를 통째로 버려 **브랜드 매칭까지 같이 죽었다**. 결제 248건이 있는데도
        // 추천이 전부 0 원으로 나갔다. 브랜드는 가맹점명으로 거는 것이라 업종코드와 무관해야 한다.
        CardProduct c = card("브랜드카드");
        CardBenefit b = benefit("커피", "10", null);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);

        String unknownKsic = "9999";
        assertThat(industries.cardAxisOf(unknownKsic)).as("표에 없는 코드여야 시험이 성립한다").isNull();

        assertThat(saving(c, List.of(payment(unknownKsic, "스타벅스 강남점", 100_000))))
                .as("100,000 × 10% × 12 — 축을 몰라도 브랜드로 걸린다")
                .isEqualByComparingTo(new BigDecimal("120000"));
    }

    @Test
    @DisplayName("띄어쓰기·기호가 달라도 같은 브랜드로 건다")
    void brandMatchIgnoresSpacingAndSymbols() {
        // 공시와 승인내역이 같은 브랜드를 다르게 적는다. 카드는 '투썸 플레이스'라 쓰고
        // 승인내역은 '투썸플레이스'로 찍힌다. 글자 그대로 비교하면 한 건도 안 걸린다.
        CardProduct c = card("카페카드");
        CardBenefit b = benefit("커피", "10", null);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "투썸 플레이스", null, null, null));
        c.add(b);

        assertThat(saving(c, List.of(payment(cafe, "투썸플레이스 강남점", 100_000))))
                .as("100,000 × 10% × 12 — 띄어쓰기만 다르다")
                .isEqualByComparingTo(new BigDecimal("120000"));
    }

    @Test
    @DisplayName("접어도 쿠팡과 쿠팡이츠는 다른 브랜드다 — 긴 이름이 먼저 가져간다")
    void foldingDoesNotMergeSiblingBrands() {
        // 카드사가 실제로 둘을 다른 묶음에 넣는다(BC 바로 ZONE: 쿠팡=LIFE, 쿠팡이츠=EAT).
        // 접기가 이 둘을 한 값으로 만들면 배달 결제가 쇼핑 한도를 갉아먹는다.
        CardProduct c = card("쿠팡카드");
        CardBenefit shopping = benefit("쇼핑", "1", null);
        shopping.add(new CardBenefitTarget("쇼핑", CardBenefitTarget.Kind.BRAND, "쿠팡", null, null, null));
        CardBenefit delivery = benefit("배달", "10", null);
        delivery.add(new CardBenefitTarget("배달", CardBenefitTarget.Kind.BRAND, "쿠팡이츠", null, null, null));
        c.add(shopping);
        c.add(delivery);

        assertThat(saving(c, List.of(payment(cafe, "쿠팡이츠", 100_000))))
                .as("쿠팡이츠 10% 로 걸려야 한다 — 쿠팡 1% 가 아니다")
                .isEqualByComparingTo(new BigDecimal("120000"));
    }

    @Test
    @DisplayName("연회비는 국내전용 중 최저를 뺀다 — 채점은 순액으로 한다")
    void subtractsAnnualFee() {
        // 화면은 연회비를 빼지 않고 나란히 보여준다(§4.5). 여기서 빼는 것은 순액 채점용이다.
        CardProduct c = card("연회비카드");
        CardPerformanceTier tier = new CardPerformanceTier(1, 10_000);
        c.add(tier);
        CardBenefit b = benefit("카페", "10", tier);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);
        c.add(new CardAnnualFee(CardAnnualFee.Scope.DOMESTIC, "BC", 15_000, 10_000, 5_000));
        c.add(new CardAnnualFee(CardAnnualFee.Scope.GLOBAL, "Mastercard", 25_000, null, null));

        assertThat(saving(c, List.of(payment(cafe, "스타벅스 강남점", 100_000))))
                .as("120,000 − 국내전용 연회비 15,000 (해외겸용 25,000 이 아니다)")
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

        assertThat(saving(c, List.of(payment(cafe, "스타벅스 강남점", 100_000))))
                .as("countable=false 는 표시만 하고 계산에서 뺀다")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
