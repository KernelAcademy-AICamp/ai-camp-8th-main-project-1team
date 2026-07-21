package com.finntech.service;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.AppUser;
import com.finntech.domain.Enums;
import com.finntech.domain.FinancialProduct;
import com.finntech.engine.AnalysisResult;
import com.finntech.repository.FinancialProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 추천 매칭 (문서 §5-1 ③).
 *
 * <pre>
 * [1단계 게이팅]  최소가입금액 &gt; 가용 여유자금  → 탈락
 *                사용자 목표기간 &lt; 상품 최소기간 → 탈락 (가입 자체가 불가능)
 * [2단계 매칭점수] 0.3×목표기간 + 0.3×리스크등급 + 0.4×타겟카테고리
 * </pre>
 *
 * <p><b>입력값을 재계산하지 않는다</b> — {@code overspendingCategories}와
 * {@code longTermVolatilityIndex}를 {@link AnalysisResult}에서 그대로 받는다 (원칙 2).
 */
@Service
public class RecommendService {

    private final FinancialProductRepository productRepository;
    private final AnalysisProperties props;

    public RecommendService(FinancialProductRepository productRepository, AnalysisProperties props) {
        this.productRepository = productRepository;
        this.props = props;
    }

    public Recommendations recommend(AppUser user, AnalysisResult analysis) {
        BigDecimal availableFunds = availableFunds(user, analysis);
        boolean estimated = !analysis.isConfirmed();

        List<FinancialProduct> all = productRepository.findAllByOrderByIdAsc();
        List<Scored> passed = new ArrayList<>();
        List<Scored> gatedOut = new ArrayList<>();

        for (FinancialProduct p : all) {
            // ESTIMATED 모드에서는 가용자금이 추정치라 금액 게이팅이 오작동한다.
            // 그래서 금액 게이팅을 적용하지 않고 최소가입금액을 근거필드로만 노출한다.
            boolean fundsFail = !estimated
                    && p.getMinJoinAmount().compareTo(availableFunds) > 0;
            boolean periodFail = user.getGoalMonths() < p.getMinPeriodMonths();

            Scored scored = score(p, user, analysis);
            if (fundsFail || periodFail) {
                gatedOut.add(scored.withGateReason(
                        periodFail ? "목표기간(" + user.getGoalMonths() + "개월)이 상품 최소기간("
                                + p.getMinPeriodMonths() + "개월)보다 짧습니다"
                                : "최소가입금액이 가용 여유자금을 초과합니다"));
            } else {
                passed.add(scored);
            }
        }

        Comparator<Scored> byScore = Comparator
                .comparingDouble(Scored::totalScore).reversed()
                .thenComparingLong(s -> s.product().getId());   // 동점 시 id로 깨서 순서를 결정론적으로
        passed.sort(byScore);

        boolean relaxed = false;
        List<Scored> top = new ArrayList<>(passed);
        int topN = props.getMatching().getTopN();

        if (top.size() < topN && !gatedOut.isEmpty()) {
            // 폴백: 여유자금이 적으면 후보가 3개 미만이라 Top3를 못 채운다 (문서 §5-1 ③ 수정 3).
            relaxed = true;
            List<Scored> fill = new ArrayList<>(gatedOut);
            fill.sort(Comparator
                    .comparing((Scored s) -> s.product().getMinJoinAmount())
                    .thenComparingLong(s -> s.product().getId()));
            for (Scored s : fill) {
                if (top.size() >= topN) break;
                top.add(s);
            }
        }
        if (top.size() > topN) top = top.subList(0, topN);

        return new Recommendations(top, availableFunds, relaxed, analysis.dataSourceMode(),
                analysis.estimationReason());
    }

    /** 가용 여유자금 = 월소득 − 월평균지출 (음수면 0) */
    private BigDecimal availableFunds(AppUser user, AnalysisResult analysis) {
        int months = Math.max(1, analysis.monthlySpend().size());
        BigDecimal avgSpend = analysis.totalSpend()
                .divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal funds = user.getMonthlyIncome().subtract(avgSpend);
        return funds.signum() < 0 ? BigDecimal.ZERO : funds;
    }

    private Scored score(FinancialProduct p, AppUser user, AnalysisResult analysis) {
        AnalysisProperties.Matching m = props.getMatching();

        // 목표기간 부합도 = 상품 최소기간 / 사용자 목표기간 (게이팅 통과 시 0~1]
        double periodFit = user.getGoalMonths() <= 0 ? 0.0
                : Stats0to1((double) p.getMinPeriodMonths() / user.getGoalMonths());

        double riskFit = riskFit(
                userRiskGrade(analysis.longTermVolatilityIndex(), analysis.volatilityMeasured()),
                p.getRiskGrade());
        double categoryFit = categoryFit(p, analysis);

        double total = m.getPeriodWeight() * periodFit
                + m.getRiskWeight() * riskFit
                + m.getCategoryWeight() * categoryFit;

        return new Scored(p, round(periodFit), round(riskFit), round(categoryFit), round(total), null);
    }

    private static double Stats0to1(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /**
     * ⚠️ 프록시 가정: 실제 투자성향 설문 없이 소비 변동성으로 리스크 성향을 대체한다.
     * "지출이 불규칙하다"가 "투자 리스크를 감수한다"를 의미하지는 않는다 (문서 §5-1 ①).
     * MVP 단계의 편의적 대체이며 README·명세서에 한계로 기록한다.
     */
    private Enums.RiskGrade userRiskGrade(double cv, boolean measured) {
        // 측정하지 못했으면 '안정형'으로 단정하지 않는다. cv 기본값 0을 그대로 쓰면
        // 데이터가 적은 사용자가 무조건 안정형으로 분류되어 추천이 한쪽으로 쏠린다.
        if (!measured) return Enums.RiskGrade.NEUTRAL;
        double cap = props.getVolatility().getCvCap();
        if (cv < cap / 3) return Enums.RiskGrade.STABLE;
        if (cv < cap * 2 / 3) return Enums.RiskGrade.NEUTRAL;
        return Enums.RiskGrade.AGGRESSIVE;
    }

    /** 등급 일치 1.0 / 인접 0.5 / 정반대 0 */
    private double riskFit(Enums.RiskGrade user, Enums.RiskGrade product) {
        int gap = Math.abs(user.ordinal() - product.ordinal());
        return switch (gap) { case 0 -> 1.0; case 1 -> 0.5; default -> 0.0; };
    }

    /**
     * 타겟카테고리 부합도 (문서 §5-1 ③ 수정 2).
     * 0/1 이진이면 타겟카테고리 없는 예금·적금이 항상 0점이 되어 배점 40%가 통째로 죽고
     * Top3가 캐시백 카드로만 채워진다. 범용상품에 중립값을 줘서 구조적 불리를 없앤다.
     */
    private double categoryFit(FinancialProduct p, AnalysisResult analysis) {
        String target = p.getTargetCategoryCode();
        if (target == null || target.isBlank()) {
            return props.getMatching().getNeutralCategoryFit();
        }
        if (analysis.overspendingCategories().contains(target)) {
            return 1.0;
        }
        int rank = analysis.categoriesBySpendDesc().indexOf(target);
        if (rank >= 0 && rank < props.getMatching().getNearMissTopRank()) {
            return props.getMatching().getNeutralCategoryFit();
        }
        return 0.0;
    }

    public record Scored(
            FinancialProduct product,
            double periodFit,
            double riskFit,
            double categoryFit,
            double totalScore,
            /** null이면 게이팅 통과. 값이 있으면 폴백으로 채워진 항목이다. */
            String gateReason
    ) {
        Scored withGateReason(String reason) {
            return new Scored(product, periodFit, riskFit, categoryFit, totalScore, reason);
        }
    }

    public record Recommendations(
            List<Scored> items,
            BigDecimal availableFunds,
            boolean gatingRelaxed,
            Enums.DataSourceMode dataSourceMode,
            String estimationReason
    ) {}
}
