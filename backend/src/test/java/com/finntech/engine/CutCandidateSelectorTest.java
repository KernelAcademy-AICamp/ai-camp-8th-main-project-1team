package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 절약 후보 선정(⑤) 검증 — 3등급 규칙·절감액 산정·정렬·보호 제외. */
class CutCandidateSelectorTest {

    private final AnalysisProperties.CutCandidate cut = new AnalysisProperties.CutCandidate();

    private static UserPayment tx(String cat2, int amount) {
        return new UserPayment(cat2 + "|" + amount, 1L, "S1", 9001L,
                LocalDateTime.of(2026, 2, 10, 12, 0), "생활", cat2, amount, "가맹점", 0, "1234567890");
    }

    @Test
    @DisplayName("제거가능=전액·최적화가능=중앙값초과분, 절감액 순 정렬, 보호·미분류 제외")
    void ranksBySavingWithTierRules() {
        List<UserPayment> w = List.of(
                tx("카페", 5000), tx("카페", 5000), tx("카페", 5000),     // 제거가능 15,000
                tx("배달", 10000), tx("배달", 10000),                    // 제거가능 20,000
                tx("한식", 8000), tx("한식", 12000), tx("한식", 20000),   // 최적화, median 12,000 → 초과분 8,000
                tx("공과금", 50000),                                     // 보호 → 제외
                tx("잡화미분류", 3000));                                 // 미분류 → 후보 아님

        List<CutCandidate> c = CutCandidateSelector.selectFrom(w, cut);

        assertEquals(3, c.size(), c.toString());
        assertEquals("배달", c.get(0).category2());
        assertEquals(CutCandidate.Type.REMOVABLE, c.get(0).type());
        assertEquals(20000, c.get(0).estimatedSaving());
        assertEquals("카페", c.get(1).category2());
        assertEquals(15000, c.get(1).estimatedSaving());

        CutCandidate han = c.get(2);
        assertEquals("한식", han.category2());
        assertEquals(CutCandidate.Type.OPTIMIZABLE, han.type());
        assertEquals(8000, han.estimatedSaving());
        assertEquals(40000, han.monthlySpend());

        assertTrue(c.stream().noneMatch(x -> x.category2().equals("공과금")), "보호 카테고리 제외");
        assertFalse(c.stream().anyMatch(x -> x.category2().equals("잡화미분류")), "미분류 제외");
    }
}
