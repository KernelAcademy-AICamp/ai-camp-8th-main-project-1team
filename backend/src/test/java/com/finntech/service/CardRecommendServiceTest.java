package com.finntech.service;

import com.finntech.config.CardRecommendProperties;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 추천 — <b>순위의 근거가 숫자로 설명되는지</b>를 고정한다.
 *
 * <p>이 화면이 잘못되는 방식은 크래시가 아니다. 그럴듯한 순서가 나오는데 근거가 없거나,
 * 같은 돈을 두 번 세어 절감액이 부풀거나, 연회비를 빼지 않고 "아껴요"라고 말하는 식이다.
 * 셋 다 화면만 봐서는 안 보인다.
 */
class CardRecommendServiceTest {

    /** 식비 월 100,000 · 카페 월 50,000 · 쇼핑 월 200,000 (관측 1개월). */
    private AnalysisResult analysis() {
        Map<String, AnalysisResult.CategoryStat> stats = new TreeMap<>();
        stats.put("식비", stat("식비", 100_000, 10));
        stats.put("카페", stat("카페", 50_000, 20));
        stats.put("쇼핑", stat("쇼핑", 200_000, 5));
        Map<String, BigDecimal> monthly = new TreeMap<>();
        monthly.put("2026-06", new BigDecimal("350000"));
        return new AnalysisResult(1L, stats, new BigDecimal("350000"), List.of(),
                List.of("쇼핑", "식비", "카페"), 0.0, false, List.of(), monthly,
                BigDecimal.ZERO, Enums.DataSourceMode.CONFIRMED, 0, null);
    }

    private AnalysisResult.CategoryStat stat(String code, long amount, long count) {
        return new AnalysisResult.CategoryStat(code, code, BigDecimal.valueOf(amount),
                0.0, count, true, 1, 30);
    }

    private CardRecommendProperties.Card card(String name, BigDecimal baseRate, BigDecimal annualFee,
                                              BigDecimal cap, List<CardRecommendProperties.Benefit> bs) {
        CardRecommendProperties.Card c = new CardRecommendProperties.Card();
        c.setName(name);
        c.setTagline("t");
        c.setBaseRate(baseRate);
        c.setAnnualFee(annualFee);
        c.setYearlyCap(cap);
        c.setBenefits(new ArrayList<>(bs));
        return c;
    }

    private CardRecommendProperties.Benefit benefit(String cat, String rate) {
        CardRecommendProperties.Benefit b = new CardRecommendProperties.Benefit();
        b.setCategory(cat);
        b.setRate(new BigDecimal(rate));
        return b;
    }

    private CardRecommendProperties props(CardRecommendProperties.Card... cards) {
        CardRecommendProperties p = new CardRecommendProperties();
        p.setCards(new ArrayList<>(List.of(cards)));
        return p;
    }

    @Test
    @DisplayName("혜택 카테고리를 기본 요율로 또 세지 않는다")
    void doesNotDoubleCountCoveredCategories() {
        // 식비 10% 카드. 연 지출 420만(=35만×12) 중 식비 120만은 10%, 나머지 300만은 기본 1%.
        //   맞는 값 = 120,000 + 30,000 = 150,000
        //   두 번 세면 = 120,000 + 42,000 = 162,000  ← 같은 돈을 두 번 아낀 셈
        CardRecommendService s = new CardRecommendService(props(
                card("[더미] A", new BigDecimal("1.0"), BigDecimal.ZERO, BigDecimal.ZERO,
                        List.of(benefit("식비", "10.0")))));

        BigDecimal saving = s.recommend(analysis()).offers().get(0).yearlySaving();
        assertThat(saving).as("식비를 두 번 세지 않은 절감액")
                .isEqualByComparingTo(new BigDecimal("150000"));
    }

    @Test
    @DisplayName("연회비를 뺀 값을 '아껴요'라고 말한다")
    void subtractsAnnualFee() {
        CardRecommendService s = new CardRecommendService(props(
                card("[더미] B", BigDecimal.ZERO, new BigDecimal("15000"), BigDecimal.ZERO,
                        List.of(benefit("식비", "10.0")))));

        assertThat(s.recommend(analysis()).offers().get(0).yearlySaving())
                .as("120,000 − 연회비 15,000")
                .isEqualByComparingTo(new BigDecimal("105000"));
    }

    @Test
    @DisplayName("연 한도에 걸리면 그 사실을 숨기지 않는다")
    void reportsCap() {
        CardRecommendService s = new CardRecommendService(props(
                card("[더미] C", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50000"),
                        List.of(benefit("식비", "10.0")))));

        CardRecommendService.Offer o = s.recommend(analysis()).offers().get(0);
        assertThat(o.yearlySaving()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(o.cappedAt()).as("한도를 숨기면 '더 쓰면 더 아낀다'로 잘못 읽힌다")
                .isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("순서는 절감액 내림차순 — 광고비 순이 아니다")
    void sortsBySaving() {
        CardRecommendService s = new CardRecommendService(props(
                card("[더미] 적게", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        List.of(benefit("카페", "5.0"))),          // 60만 × 5% = 30,000
                card("[더미] 많이", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        List.of(benefit("쇼핑", "5.0")))));        // 240만 × 5% = 120,000

        assertThat(s.recommend(analysis()).offers())
                .extracting(CardRecommendService.Offer::name)
                .containsExactly("[더미] 많이", "[더미] 적게");
    }

    @Test
    @DisplayName("근거가 되는 소비 요약을 같은 응답에 싣는다")
    void carriesEvidence() {
        CardRecommendService s = new CardRecommendService(props(
                card("[더미] D", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of())));

        CardRecommendService.Result r = s.recommend(analysis());
        // 순위와 근거가 한 화면에 있어야 검증이 된다 — 근거 없는 순위는 광고다.
        assertThat(r.summary()).extracting(CardRecommendService.Summary::displayName)
                .containsExactly("쇼핑", "식비", "카페");
        assertThat(r.summary().get(0).count()).isEqualTo(5);
        assertThat(r.periodLabel()).isEqualTo("2026.06 ~ 2026.06");
    }

    @Test
    @DisplayName("소비가 없어도 터지지 않는다 — 빈 요약과 0원")
    void emptyAnalysis() {
        CardRecommendService s = new CardRecommendService(props(
                card("[더미] E", new BigDecimal("1.0"), BigDecimal.ZERO, BigDecimal.ZERO, List.of())));
        AnalysisResult empty = new AnalysisResult(1L, new TreeMap<>(), BigDecimal.ZERO, List.of(),
                List.of(), 0.0, false, List.of(), new TreeMap<>(), BigDecimal.ZERO,
                Enums.DataSourceMode.ESTIMATED, 0, null);

        CardRecommendService.Result r = s.recommend(empty);
        assertThat(r.summary()).isEmpty();
        assertThat(r.periodLabel()).isEmpty();
        assertThat(r.offers().get(0).yearlySaving()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
