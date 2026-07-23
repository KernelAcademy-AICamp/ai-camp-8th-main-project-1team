package com.finntech.service;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.AppUser;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisResult;
import com.finntech.engine.Stats;
import com.finntech.ml.WasteScoringService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 소비건전성지수 (문서 §5 ③).
 *
 * <pre>
 * 점수 = 100 × (0.4×저축진행률 + 0.3×안정성 + 0.3×계획소비비율)
 * 저축진행률   = min(1, 누적저축액 / (목표금액 × 경과기간/목표기간))
 * 안정성       = clamp(1 − CV / 0.6, 0, 1)
 * 계획소비비율 = 계획소비 금액 / 전체 지출 금액
 * 등급: A 85+ / B 70~84 / C 50~69 / D 49-
 * </pre>
 */
@Service
public class ScoreService {

    private final AnalysisProperties props;
    private final WasteScoringService wasteScoringService;

    public ScoreService(AnalysisProperties props, WasteScoringService wasteScoringService) {
        this.props = props;
        this.wasteScoringService = wasteScoringService;
    }

    public ScoreResult score(AppUser user, AnalysisResult analysis) {
        AnalysisProperties.Score cfg = props.getScore();

        double savingsProgress = savingsRate(user, analysis);
        // 계획소비비율 항을 ML '필수 소비 비율'로 대체(W8 다운스트림) — 마이데이터 연동 시. 미연동/모델없음이면 규칙 planned로 폴백.
        double plannedRatio = wasteScoringService.summarize(user.getId())
                .map(WasteScoringService.MlSummary::essentialRatio)
                .orElseGet(() -> analysis.totalSpend().signum() == 0 ? 0.0
                        : analysis.plannedAmount()
                            .divide(analysis.totalSpend(), 10, RoundingMode.HALF_UP).doubleValue());

        // 변동성을 측정하지 못했으면 안정성 항을 <b>빼고 남은 가중치를 정규화</b>한다.
        // 측정 불가를 0(=안정성 만점)으로 취급하면 기록이 적을수록 점수가 높아지는 역설이 생긴다.
        boolean measured = analysis.volatilityMeasured();
        double stability = measured
                ? Stats.clamp(1.0 - analysis.longTermVolatilityIndex() / props.getVolatility().getCvCap(), 0.0, 1.0)
                : 0.0;

        double raw;
        if (measured) {
            raw = 100.0 * (cfg.getSavingsWeight() * savingsProgress
                    + cfg.getStabilityWeight() * stability
                    + cfg.getPlannedWeight() * plannedRatio);
        } else {
            double remaining = cfg.getSavingsWeight() + cfg.getPlannedWeight();
            raw = remaining <= 0 ? 0.0
                    : 100.0 * (cfg.getSavingsWeight() * savingsProgress
                            + cfg.getPlannedWeight() * plannedRatio) / remaining;
        }
        int score = (int) Math.round(Stats.clamp(raw, 0.0, 100.0));

        return new ScoreResult(score, grade(score),
                round(savingsProgress), measured ? round(stability) : null, round(plannedRatio),
                measured, analysis.dataSourceMode(), analysis.estimationReason());
    }

    /**
     * 누적저축액 = Σ(월소득 − 월지출), 음수 월은 0으로 본다.
     * 목표선 = 목표금액 × 경과월/목표월.
     */
    /**
     * 저축률(§13-11 경제 모델) = 1 − 통상 월지출 / 월급. 흑자(지출&lt;월급)면 +, 적자면 0.
     * 통상 월지출 = 월별 지출의 <b>중앙값</b> — 가입 말일 사용자의 부분 시작월(결제 1건) 왜곡을 배제한다.
     * 저축 20%를 만점(1.0)으로 매핑(한국 가계 저축률 참고). 월급·자료 없으면 0.
     */
    private double savingsRate(AppUser user, AnalysisResult analysis) {
        BigDecimal income = user.getMonthlyIncome();
        if (income == null || income.signum() <= 0 || analysis.monthlySpend().isEmpty()) return 0.0;
        java.util.List<BigDecimal> months = new java.util.ArrayList<>(analysis.monthlySpend().values());
        java.util.Collections.sort(months);
        BigDecimal median = months.get(months.size() / 2);   // 중앙값(부분월 아웃라이어 배제)
        double rate = 1.0 - median.doubleValue() / income.doubleValue();
        // 저축률 -0.20(20% 적자)→0, 손익분기(0)→0.5, +0.20(20% 저축)→1.0. 흑자↑·적자↓를 완만히 반영.
        return Stats.clamp((rate + 0.20) / 0.40, 0.0, 1.0);
    }

    private String grade(int score) {
        AnalysisProperties.Score c = props.getScore();
        if (score >= c.getGradeA()) return "A";
        if (score >= c.getGradeB()) return "B";
        if (score >= c.getGradeC()) return "C";
        return "D";
    }

    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    public record ScoreResult(
            int score,
            String grade,
            double savingsProgress,
            /** null이면 관측 월수 부족으로 <b>측정하지 못한 것</b>이다 — 0(안정적)과 다르다. */
            Double stability,
            double plannedRatio,
            boolean volatilityMeasured,
            Enums.DataSourceMode dataSourceMode,
            String estimationReason
    ) {}
}
