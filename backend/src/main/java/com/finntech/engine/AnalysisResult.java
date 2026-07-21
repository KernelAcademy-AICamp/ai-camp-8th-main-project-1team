package com.finntech.engine;

import com.finntech.domain.Enums;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 하나의 분석 결과를 세 갈래로 재사용한다 (문서 §4 원칙 2).
 * RecommendService / ReportService / AlertService는 각자 계산하지 않고 이 결과를 그대로 받는다.
 */
public record AnalysisResult(

        Long userId,

        /** 카테고리 코드 → 집계. 키 순서 고정(TreeMap)이라 재현성이 보장된다. */
        Map<String, CategoryStat> categoryStats,

        BigDecimal totalSpend,

        /** 지출비중 > 임계치인 카테고리 코드. ReportService·RecommendService 공용. */
        List<String> overspendingCategories,

        /** 지출 비중 내림차순 카테고리 코드 — 매칭의 '상위 N위' 판정에 쓴다. */
        List<String> categoriesBySpendDesc,

        /** 전체 기간 월별 총지출의 변동계수 (장기, 추천·건전성지수용) */
        double longTermVolatilityIndex,

        /**
         * 변동성을 실제로 <b>측정했는지</b>. 관측 월수가 최소치 미만이면 false.
         *
         * <p>이 플래그가 없으면 "변동성 0(완벽히 안정적)"과 "변동성을 잴 수 없음"을 구분하지 못한다.
         * 그러면 데이터가 적은 사용자가 안정성 만점을 받아 <b>기록을 적게 할수록 점수가 높아진다.</b>
         */
        boolean volatilityMeasured,

        /** 최근 구간 거래별 Modified Z-score (단기, FDS 전용) */
        List<Deviation> deviations,

        /** 월별 총지출 (yyyy-MM → 금액), 키 순서 고정 */
        Map<String, BigDecimal> monthlySpend,

        BigDecimal plannedAmount,

        Enums.DataSourceMode dataSourceMode,

        long userInputCount,

        /** ESTIMATED인 이유 — 화면 안내 문구에 그대로 쓴다 */
        String estimationReason
) {

    public record CategoryStat(
            String categoryCode,
            String displayName,
            BigDecimal totalAmount,
            double spendRatio,
            long count,
            boolean sufficientSamples
    ) {}

    /** z-score를 어느 분포에 대해 계산했는가 — 경고 문구의 근거로 노출한다. */
    public enum BaselineSource {
        /** 같은 카테고리의 직전 구간 분포 */
        CATEGORY,
        /**
         * 사용자 전체 카테고리 합산 분포. 신규·희소 카테고리는 자기 분포가 없어서
         * z를 낼 수 없는데, 그대로 건너뛰면 "신규 카테고리 급증" 룰이 영원히 발화하지 못한다.
         * 그래서 전체 분포로 대체해 "당신 평소 씀씀이 대비 이례적"이라는 판정을 낸다.
         */
        GLOBAL
    }

    /**
     * 단기 이탈 후보. AlertService가 룰 AND 결합으로 최종 판정한다.
     * 룰 판정에 필요한 값은 전부 엔진이 계산해 여기 담는다 — 서비스가 다시 계산하지 않는다(원칙 2).
     */
    public record Deviation(
            Long consumptionId,
            String categoryCode,
            BigDecimal amount,
            LocalDateTime occurredAt,
            double modifiedZ,
            boolean exceedsThreshold,
            BaselineSource baselineSource,
            /** 기준 구간 해당 카테고리 금액 중앙값 (룰 ① 심야 고액 판정용) */
            double baselineMedianAmount,
            /** 기준 구간 해당 카테고리 건수 (룰 ② 신규 카테고리 판정용) */
            long baselineCount,
            /** 평가 구간 해당 카테고리 건수 (룰 ③ 빈도 이탈 판정용) */
            long recentCount,
            /** 기준 구간 해당 카테고리 월평균 건수 (룰 ③ 기준선) */
            double baselineMonthlyAvgCount
    ) {}

    public boolean isConfirmed() {
        return dataSourceMode == Enums.DataSourceMode.CONFIRMED;
    }
}
