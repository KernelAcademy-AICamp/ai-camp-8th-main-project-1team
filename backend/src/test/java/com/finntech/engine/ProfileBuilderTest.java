package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 이상소비지수(④) 조립·해석가능 분해 검증. */
class ProfileBuilderTest {

    private final AnalysisProperties.Profile profile = new AnalysisProperties.Profile();       // 0.4/0.25/0.15/0.2
    private final AnalysisProperties.CutCandidate cut = new AnalysisProperties.CutCandidate();  // removable: 카페·배달·…
    private final AnalysisProperties.Volatility vol = new AnalysisProperties.Volatility();      // minMonths 3
    private final AnalysisProperties.Daypart daypart = new AnalysisProperties.Daypart();

    private static UserPayment tx(LocalDateTime at, String cat1, String cat2, int amount) {
        return new UserPayment(at + "-" + cat2 + "-" + amount, 1L, "S1", 9001L, at, cat1, cat2, amount, "가맹점", 0, "1234567890");
    }

    @Test
    @DisplayName("지수 = 성분별 기여 합, 낭비비율·집중도·최대카테고리 정확")
    void indexIsSumOfContributions() {
        List<UserPayment> w = new ArrayList<>();
        for (int i = 0; i < 4; i++) w.add(tx(LocalDateTime.of(2026, 2, 3 + i, 8, 0), "식비", "카페", 5000));   // removable 20,000, 아침
        w.add(tx(LocalDateTime.of(2026, 2, 10, 12, 0), "식비", "한식", 10000));
        w.add(tx(LocalDateTime.of(2026, 2, 11, 12, 0), "식비", "한식", 10000));                                 // 식비 합 40,000
        w.add(tx(LocalDateTime.of(2026, 2, 12, 9, 0), "고정비", "통신비", 60000));                              // 고정비 60,000 → 최대
        // 총 100,000. 낭비 20,000(카페)→0.2, 집중 60,000/100,000→0.6, 변동 0(단월), 심야 0
        SpendingPattern pattern = PatternAnalyzer.analyzeFrom(
                w, LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 0, 0), daypart);

        UserProfile p = ProfileBuilder.buildFrom(w, List.of(), pattern, profile, cut, vol);

        assertEquals(100000, p.totalSpend());
        assertEquals("고정비", p.topCategory1());
        assertEquals(0.2, p.wasteRatio(), 1e-9);
        assertEquals(0.6, p.concentrationRatio(), 1e-9);
        assertEquals(0.0, p.volatility(), 1e-9);            // 단월 → 판단 보류
        assertEquals(8, p.contributionPoints().get("낭비"));   // 100×0.4×0.2
        assertEquals(15, p.contributionPoints().get("집중"));  // 100×0.25×0.6
        assertEquals(0, p.contributionPoints().get("변동"));
        assertEquals(0, p.contributionPoints().get("심야충동"));
        assertEquals(23, p.abnormalityIndex());              // 8+15
        assertEquals(p.contributionPoints().values().stream().mapToInt(Integer::intValue).sum(), p.abnormalityIndex());
    }

    @Test
    @DisplayName("심야 지출 + 루틴형 낭비가 심야충동 성분에 반영(clamp 1.0)")
    void nightImpulseCapturesNightAndRoutineWaste() {
        List<UserPayment> w = List.of(
                tx(LocalDateTime.of(2026, 2, 5, 23, 0), "식비", "배달", 10000),   // 심야, removable
                tx(LocalDateTime.of(2026, 2, 6, 23, 0), "식비", "배달", 10000));  // 심야, removable  → 총 20,000
        SpendingPattern pattern = PatternAnalyzer.analyzeFrom(
                w, LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 0, 0), daypart);
        // 루틴형 배달(대표 10,000 × 등장 2일 = 20,000) → 심야(20,000)+루틴낭비(20,000) = 40,000 / 20,000 = 2.0 → clamp 1.0
        RecurringPayment routine = new RecurringPayment(
                RecurringPayment.Type.ROUTINE, "배달", null, null, "심야", 10000, null, null, 2, 0.5);

        UserProfile p = ProfileBuilder.buildFrom(w, List.of(routine), pattern, profile, cut, vol);

        assertEquals(1.0, p.nightImpulseRatio(), 1e-9);
        assertEquals(20, p.contributionPoints().get("심야충동")); // 100×0.2×1.0
        assertEquals(1, p.routineCount());
        assertEquals(0, p.fixedCount());
    }
}
