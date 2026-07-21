package com.finntech.service;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.AppUser;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoreServiceTest {

    @Test
    void scoresAreComputedAndGradedForMeasuredData() {
        AnalysisProperties props = new AnalysisProperties();
        props.getScore().setSavingsWeight(0.4);
        props.getScore().setStabilityWeight(0.3);
        props.getScore().setPlannedWeight(0.3);
        props.getVolatility().setCvCap(0.6);
        ScoreService service = new ScoreService(props);

        AppUser user = new AppUser("alice", new BigDecimal("5000"), new BigDecimal("12000"), 12);

        AnalysisResult analysis = new AnalysisResult(
                1L,
                Map.of(),
                BigDecimal.valueOf(2000),
                List.of(),
                List.of(),
                0.1,
                true,
                List.of(),
                new LinkedHashMap<>(Map.of("2026-01", BigDecimal.valueOf(1000), "2026-02", BigDecimal.valueOf(1000))),
                BigDecimal.valueOf(1000),
                Enums.DataSourceMode.CONFIRMED,
                2L,
                "ok"
        );

        ScoreService.ScoreResult result = service.score(user, analysis);

        assertTrue(result.score() >= 0);
        assertEquals("B", result.grade());
        assertEquals(0.5, result.plannedRatio());
        assertTrue(result.volatilityMeasured());
        assertEquals(Enums.DataSourceMode.CONFIRMED, result.dataSourceMode());
    }

    @Test
    void unmeasuredVolatilityFallsBackToWeightedScoreWithoutStability() {
        AnalysisProperties props = new AnalysisProperties();
        props.getScore().setSavingsWeight(0.5);
        props.getScore().setStabilityWeight(0.3);
        props.getScore().setPlannedWeight(0.2);
        ScoreService service = new ScoreService(props);

        AppUser user = new AppUser("alice", new BigDecimal("5000"), new BigDecimal("12000"), 12);

        AnalysisResult analysis = new AnalysisResult(
                1L,
                Map.of(),
                BigDecimal.valueOf(2000),
                List.of(),
                List.of(),
                0.0,
                false,
                List.of(),
                new LinkedHashMap<>(Map.of("2026-01", BigDecimal.valueOf(1000), "2026-02", BigDecimal.valueOf(1000))),
                BigDecimal.valueOf(1000),
                Enums.DataSourceMode.ESTIMATED,
                2L,
                "not enough history"
        );

        ScoreService.ScoreResult result = service.score(user, analysis);

        assertNull(result.stability());
        assertFalse(result.volatilityMeasured());
        assertEquals(Enums.DataSourceMode.ESTIMATED, result.dataSourceMode());
        assertEquals("not enough history", result.estimationReason());
    }
}
