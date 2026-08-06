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
            boolean sufficientSamples,
            /**
             * <b>이 카테고리에</b> 소비가 있었던 달의 수 (yyyy-MM 기준, 최소 1).
             *
             * <p>월평균을 내려면 총액을 개월수로 나눠야 하는데, 그 개월수는 카테고리마다 다르다.
             * 예전에는 소비자 쪽에서 {@link AnalysisResult#monthlySpend()}{@code .size()}
             * (= 사용자가 <b>아무 카테고리든</b> 결제한 달의 수)로 나눴다. 분자는 한 카테고리의
             * 총액인데 분모는 전체 기간이라, 최근 시작한 습관은 최대 관측 개월수 배만큼
             * <b>과소평가</b>됐다 — 12개월 이력이 있는 사용자가 지난달 배달을 시작해 30만원을
             * 썼다면 기준이 2.5만원으로 잡혀 챌린지 시작 직후 예산을 넘겼다.
             *
             * <p>지킴이 설계서 §1이 "기준 지출은 분석이 낸 <b>카테고리 월평균</b>의 합이다.
             * 서비스가 다시 계산하지 않는다"고 못박으므로, 나눗셈의 분모도 엔진이 낸다(원칙 2).
             */
            int observedMonths,

            /**
             * 위 관측 달들의 <b>실제 일수 합</b>(2월=28·7월=31 …).
             *
             * <p>월평균은 관측한 달의 길이를 그대로 물려받는다. 하루 1만원을 쓰는 같은 습관이라도
             * 7월만 본 사용자는 31만원, 2월만 본 사용자는 28만원이 월평균이 된다. 이 값을 30일
             * 챌린지 예산으로 환산 없이 쓰면 <b>같은 습관에 10.7% 다른 예산</b>이 잡히고 정산 등급까지
             * 갈린다. 일수로 나눠 일액을 낸 뒤 챌린지 일수를 곱하면 관측 달이 무엇이든 같아진다.
             *
             * <p>챌린지 기간이 30일이 아닐 때는 더 크게 어긋난다 — 7일 챌린지에 한 달치 기준을
             * 그대로 주면 아무 노력 없이 성공한다.
             */
            int observedMonthDays
    ) {

        /** 이 카테고리의 월평균 지출(설계서 §1 "카테고리 월평균"). 분모가 카테고리별이라 습관의 실제 크기에 맞는다. */
        public BigDecimal monthlyAmount() {
            return totalAmount.divide(BigDecimal.valueOf(Math.max(1, observedMonths)),
                    0, java.math.RoundingMode.DOWN);
        }

        /** 이 카테고리의 지출을 {@code days}일치로 환산한다. 관측 달의 길이에 좌우되지 않는다. */
        public BigDecimal amountOver(int days) {
            int denominator = Math.max(1, observedMonthDays);
            return totalAmount.multiply(BigDecimal.valueOf(Math.max(0, days)))
                    .divide(BigDecimal.valueOf(denominator), 0, java.math.RoundingMode.DOWN);
        }
    }

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
