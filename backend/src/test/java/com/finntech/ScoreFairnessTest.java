package com.finntech;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisResult;
import com.finntech.domain.AppUser;
import com.finntech.ml.WasteScoringService;
import com.finntech.service.ScoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 적대적 리뷰 회귀 테스트 — <b>기록을 적게 할수록 점수가 높아지는 역설</b>을 막는다.
 *
 * <p>변동성을 측정하지 못한 상태(관측 월수 부족)를 CV=0으로 흘려보내면
 * 안정성 만점(30점)을 공짜로 받는다. 그러면 데이터가 적은 사용자가 성실한 사용자보다 높은 점수를 받는다.
 */
class ScoreFairnessTest {

    private static final AnalysisProperties PROPS = new AnalysisProperties();
    private static final ScoreService SERVICE = new ScoreService(PROPS, noMlScoring());

    /** 규칙 기반 점수만 테스트 — ML 요약 비움(마이데이터 미연동) → 규칙 planned로 폴백. */
    private static WasteScoringService noMlScoring() {
        WasteScoringService ws = mock(WasteScoringService.class);
        when(ws.summarize(any())).thenReturn(Optional.empty());
        return ws;
    }

    private static AppUser user() {
        return new AppUser("t", new BigDecimal("3000000"), new BigDecimal("3000000"), 6);
    }

    private static AnalysisResult result(Map<String, BigDecimal> monthly, double cv, boolean measured) {
        BigDecimal total = monthly.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AnalysisResult(1L, new TreeMap<>(), total, List.of(), List.of(),
                cv, measured, List.of(), new TreeMap<>(monthly),
                total.multiply(new BigDecimal("0.5")),
                Enums.DataSourceMode.CONFIRMED, 0L, null);
    }

    @Test
    @DisplayName("변동성 미측정은 안정성 만점이 아니라 '해당 항목 제외'로 처리된다")
    void unmeasuredVolatilityIsNotPerfectStability() {
        Map<String, BigDecimal> twoMonths = new TreeMap<>(Map.of(
                "2026-06", new BigDecimal("2000000"),
                "2026-07", new BigDecimal("3000000")));

        ScoreService.ScoreResult r = SERVICE.score(user(), result(twoMonths, 0.0, false));

        assertNull(r.stability(), "측정하지 못했으면 0(안정적)이 아니라 null이어야 한다");
        assertFalse(r.volatilityMeasured());
        assertTrue(r.score() >= 0 && r.score() <= 100, "점수는 여전히 0~100: " + r.score());
    }

    @Test
    @DisplayName("데이터가 적다는 이유로 더 높은 점수를 받지 않는다")
    void lessDataDoesNotOutscoreMoreData() {
        // 관측 2개월, 변동성 측정 불가
        Map<String, BigDecimal> few = new TreeMap<>(Map.of(
                "2026-06", new BigDecimal("2000000"),
                "2026-07", new BigDecimal("2000000")));
        // 관측 6개월, 실제로 안정적(CV=0)인 성실한 사용자
        Map<String, BigDecimal> many = new TreeMap<>();
        for (int m = 2; m <= 7; m++) many.put("2026-0" + m, new BigDecimal("2000000"));

        int scoreFew = SERVICE.score(user(), result(few, 0.0, false)).score();
        int scoreMany = SERVICE.score(user(), result(many, 0.0, true)).score();

        assertTrue(scoreMany >= scoreFew,
                "6개월을 성실히 기록한 사용자(" + scoreMany + ")가 2개월 사용자(" + scoreFew + ")보다 낮으면 안 된다");
    }

    @Test
    @DisplayName("측정된 변동성은 그대로 반영된다")
    void measuredVolatilityIsApplied() {
        Map<String, BigDecimal> monthly = new TreeMap<>();
        for (int m = 2; m <= 7; m++) monthly.put("2026-0" + m, new BigDecimal("2000000"));

        ScoreService.ScoreResult stable = SERVICE.score(user(), result(monthly, 0.0, true));
        ScoreService.ScoreResult volatile_ = SERVICE.score(user(), result(monthly, 0.5, true));

        assertNotNull(stable.stability());
        assertNotNull(volatile_.stability());
        assertTrue(stable.stability() > volatile_.stability(),
                "변동성이 크면 안정성 점수가 낮아야 한다");
        assertTrue(stable.score() > volatile_.score());
    }
}
