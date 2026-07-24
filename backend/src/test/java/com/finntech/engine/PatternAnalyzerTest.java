package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 소비 패턴(③) 집계 검증 — 최대 지출 칸·창 필터·빈 입력. */
class PatternAnalyzerTest {

    private final AnalysisProperties.Daypart daypart = new AnalysisProperties.Daypart();

    private static UserPayment tx(LocalDateTime at, int amount) {
        return new UserPayment(at + "-" + amount, 1L, "S1", 9001L, at, "생활", "기타", amount, "가맹점", 0, "1234567890");
    }

    @Test
    @DisplayName("지출 최대 칸이 peak — 창 밖 결제는 제외")
    void peakIsHighestAmountCellAndWindowFilters() {
        LocalDateTime big = LocalDateTime.of(2026, 2, 13, 19, 0); // 저녁
        List<UserPayment> t = List.of(
                tx(big, 200000),
                tx(LocalDateTime.of(2026, 2, 15, 8, 0), 5000),   // 아침
                tx(LocalDateTime.of(2026, 2, 16, 13, 0), 6000),  // 점심
                tx(LocalDateTime.of(2026, 1, 1, 19, 0), 100000)); // 창 밖(제외)

        SpendingPattern p = PatternAnalyzer.analyzeFrom(
                t, LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 12, 0), daypart);

        assertNotNull(p.peak());
        assertEquals(big.getDayOfWeek(), p.peak().dayOfWeek());
        assertEquals("저녁", p.peak().daypart());
        assertEquals(200000, p.peak().amount());
        // 창 밖 10만원은 집계에서 빠져야 한다
        assertEquals(211000, p.amountByDayOfWeek().values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    @DisplayName("거래가 없으면 peak=null, 집계 비어 있음")
    void emptyWhenNoTxns() {
        SpendingPattern p = PatternAnalyzer.analyzeFrom(
                List.of(), LocalDateTime.of(2026, 2, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 0, 0), daypart);
        assertNull(p.peak());
        assertTrue(p.amountByDayOfWeek().isEmpty());
        assertTrue(p.countByCell().isEmpty());
    }
}
