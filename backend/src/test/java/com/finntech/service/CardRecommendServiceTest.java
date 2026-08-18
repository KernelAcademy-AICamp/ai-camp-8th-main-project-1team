package com.finntech.service;

import com.finntech.config.CardRecommendProperties;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitTarget;
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

    private CardRecommendService service(List<CardProduct> catalog, List<UserPayment> lastMonth) {
        CardProductRepository cards = mock(CardProductRepository.class);
        when(cards.findRecommendable()).thenReturn(catalog);
        UserPaymentRepository payments = mock(UserPaymentRepository.class);
        when(payments.findInPeriod(any(), any(), any())).thenReturn(lastMonth);
        return new CardRecommendService(new CardRecommendProperties(), cards, payments,
                industries, new CardBenefitEstimator(new CardMatcher(), exclusionPolicy),
                new CardMatcher());
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
    @DisplayName("겹친 곳을 세어 이름과 함께 싣는다 — 우리만 할 수 있는 말이다")
    void carriesOverlap() {
        // 카드 비교 서비스는 "이 카드는 커피 5%"까지만 말한다. 우리는 마이데이터가 있어
        // "회원님이 자주 가는 스타벅스가 그 대상입니다"를 말할 수 있다 — 그것이 3층이다.
        CardProduct c = card("겹침카드");
        CardBenefit b = new CardBenefit("커피", CardBenefit.Kind.DISCOUNT, CardBenefit.Scope.BRAND, 0);
        b.rate(new java.math.BigDecimal("5"), null, null, null);
        b.conditions(null, null, "원", null, true, null, true, false, null, null, null);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        b.add(new CardBenefitTarget("편의점", CardBenefitTarget.Kind.BRAND, "CU", null, null, null));
        b.add(new CardBenefitTarget("영화", CardBenefitTarget.Kind.BRAND, "CGV", null, null, null));
        c.add(b);

        // 겹침은 "자주 가는 곳"이라 방문 문턱(기본 2회)을 넘어야 센다 — 아래 두 시험 참고.
        var offer = service(List.of(c), List.of(
                payment("999999", "스타벅스 강남점", 30_000),
                payment("999999", "스타벅스 역삼점", 30_000),
                payment("999999", "CU 역삼점", 10_000),
                payment("999999", "CU 역삼점", 10_000))).recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.matched()).as("걸린 이름을 그대로 보여줘 사용자가 반박할 수 있게 한다")
                .containsExactly("스타벅스", "CU");
        assertThat(offer.matchCount()).as("CGV 는 안 갔으므로 세지 않는다").isEqualTo(2);
    }

    @Test
    @DisplayName("요율이 없어도 겹침은 센다 — 금액을 안 세니 요율을 물을 이유가 없다")
    void overlapDoesNotNeedRate() {
        // 하나카드처럼 대상마다 요율이 다른 표는 묶음 요율이 비어 있다(10 §8.1).
        // 절감액은 못 세지만 "그 브랜드가 대상이다"는 참이다.
        CardProduct c = card("요율없는카드");
        CardBenefit b = new CardBenefit("하나머니 적립", CardBenefit.Kind.POINT, CardBenefit.Scope.BRAND, 0);
        b.conditions(null, null, "원", null, true, null, true, false, null, null, null);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);

        var offer = service(List.of(c), List.of(
                payment("999999", "스타벅스 강남점", 30_000),
                payment("999999", "스타벅스 강남점", 30_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.matched()).as("요율이 없어도 '그 브랜드가 대상이다'는 참이다")
                .containsExactly("스타벅스");
    }

    @Test
    @DisplayName("한 번 들른 곳은 겹침이 아니다 — '자주 가는 곳'을 묻는 값이다")
    void oneOffVisitIsNotAnOverlap() {
        // 2026-08-14 실측(3개월 153건): 한 번이라도 갔으면 세던 방식에서 겹침 16 으로 1위였던
        // 카드가 **대부분 한 번씩만 간 곳**이었고, 2회 문턱을 걸자 순위 밖으로 밀렸다.
        // 창을 넓히는 것만으로는 겹침 수만 늘어난다 — 문턱과 짝이라야 순위가 달라진다.
        CardProduct c = card("브랜드카드");
        CardBenefit b = new CardBenefit("커피", CardBenefit.Kind.DISCOUNT, CardBenefit.Scope.BRAND, 0);
        b.rate(new BigDecimal("10"), null, null, null);
        b.conditions(null, null, "원", null, true, null, true, false, null, null, null);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);

        var once = service(List.of(c), List.of(payment("999999", "스타벅스 강남점", 30_000)))
                .recommend(analysis(), NOW).offers().get(0);
        assertThat(once.matchCount()).as("한 번 들른 곳은 습관인지 어쩌다인지 알 수 없다").isZero();

        var twice = service(List.of(c), List.of(
                payment("999999", "스타벅스 강남점", 30_000),
                payment("999999", "스타벅스 강남점", 30_000)))
                .recommend(analysis(), NOW).offers().get(0);
        assertThat(twice.matchCount()).as("두 번부터 '계속 가는 곳'으로 본다").isEqualTo(1);
    }

    @Test
    @DisplayName("겹침은 최근 3개월에서 센다 — 반복은 여러 달에 걸쳐야 보인다")
    void overlapCountsAcrossTheWholeWindow() {
        CardProduct c = card("브랜드카드");
        CardBenefit b = new CardBenefit("커피", CardBenefit.Kind.DISCOUNT, CardBenefit.Scope.BRAND, 0);
        b.rate(new BigDecimal("10"), null, null, null);
        b.conditions(null, null, "원", null, true, null, true, false, null, null, null);
        b.add(new CardBenefitTarget("커피", CardBenefitTarget.Kind.BRAND, "스타벅스", null, null, null));
        c.add(b);

        // 창이 한 달이면 전달의 1회만 보여 문턱을 못 넘는다. 3개월이라야 두 달 치가 합쳐진다.
        var offer = service(List.of(c), List.of(
                payment("999999", "스타벅스 강남점", 30_000),
                payment("999999", "스타벅스 강남점", 30_000)))
                .recommend(analysis(), NOW).offers().get(0);

        assertThat(offer.matchCount()).isEqualTo(1);
        // 조회 구간이 3개월인지는 응답의 창 표기로 확인한다.
        var result = service(List.of(c), List.of()).recommend(analysis(), NOW);
        assertThat(result.spendWindow()).isEqualTo("2026.04 ~ 2026.06");
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
        assertThat(result.spendWindow()).as("겹침을 어느 구간에서 셌는지 — 실적은 판정하지 않는다")
                .isEqualTo("2026.04 ~ 2026.06");
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
        assertThat(result.offers().get(0).matchCount()).as("겹칠 소비가 없으면 0곳").isZero();
        assertThat(result.offers().get(0).matched()).isEmpty();
    }
}
