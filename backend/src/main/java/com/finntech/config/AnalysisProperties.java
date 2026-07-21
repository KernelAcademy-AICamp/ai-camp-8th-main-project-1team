package com.finntech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모든 분석 임계치를 설정값으로 분리한다.
 *
 * <p>설계 제약 (구현과정 문서 §8): 페르소나가 확정되지 않았으므로 엔진은 세그먼트 비의존적이어야 한다.
 * 임계치를 코드에 상수로 박으면 페르소나 확정 시점에 엔진을 다시 만들어야 한다.
 * 카테고리 이름도 코드에 등장해서는 안 되며, 오직 DB 데이터와 아래 규칙으로만 판단한다.
 */
@ConfigurationProperties(prefix = "finntech.analysis")
public class AnalysisProperties {

    private Overspending overspending = new Overspending();
    private Fds fds = new Fds();
    private Volatility volatility = new Volatility();
    private Confirmation confirmation = new Confirmation();
    private Score score = new Score();
    private Matching matching = new Matching();

    /** 과소비 기준: 카테고리 지출 ÷ 전체 지출 > ratioThreshold (문서 §5 ②) */
    public static class Overspending {
        private double ratioThreshold = 0.30;

        public double getRatioThreshold() { return ratioThreshold; }
        public void setRatioThreshold(double v) { this.ratioThreshold = v; }
    }

    /**
     * 단기 이상소비 탐지 (문서 §5 ①).
     * Modified Z-score = 0.6745 × (log(amount) − median) / MAD, 임계 3.5.
     * 최종 경고 = z-score 플래그 AND 룰 1개 이상 일치.
     */
    public static class Fds {
        private double modifiedZThreshold = 3.5;
        /** 카테고리별 최소 표본. 미달 시 해당 카테고리는 산출하지 않는다. */
        private int minSamplesPerCategory = 10;
        /** 평가 대상 구간(개월) — 최근 1개월 */
        private int evaluationWindowMonths = 1;
        /** 기준 분포 구간(개월) — 직전 3개월. 평가 구간과 겹치지 않는다. */
        private int baselineWindowMonths = 3;

        /** 룰 ① 심야 고액 */
        private int nightStartHour = 0;
        private int nightEndHour = 6;
        private double nightAmountMultiplier = 2.0;
        /** 룰 ② 신규 카테고리 급증 — 기준 구간 거래건수가 이 값 이하면 '신규'로 본다 */
        private int newCategoryMaxBaselineCount = 2;
        /** 룰 ③ 빈도 이탈 — 최근 건수가 기준 월평균 건수의 이 배수를 넘으면 이탈 */
        private double frequencyMultiplier = 2.0;

        public double getModifiedZThreshold() { return modifiedZThreshold; }
        public void setModifiedZThreshold(double v) { this.modifiedZThreshold = v; }
        public int getMinSamplesPerCategory() { return minSamplesPerCategory; }
        public void setMinSamplesPerCategory(int v) { this.minSamplesPerCategory = v; }
        public int getEvaluationWindowMonths() { return evaluationWindowMonths; }
        public void setEvaluationWindowMonths(int v) { this.evaluationWindowMonths = v; }
        public int getBaselineWindowMonths() { return baselineWindowMonths; }
        public void setBaselineWindowMonths(int v) { this.baselineWindowMonths = v; }
        public int getNightStartHour() { return nightStartHour; }
        public void setNightStartHour(int v) { this.nightStartHour = v; }
        public int getNightEndHour() { return nightEndHour; }
        public void setNightEndHour(int v) { this.nightEndHour = v; }
        public double getNightAmountMultiplier() { return nightAmountMultiplier; }
        public void setNightAmountMultiplier(double v) { this.nightAmountMultiplier = v; }
        public int getNewCategoryMaxBaselineCount() { return newCategoryMaxBaselineCount; }
        public void setNewCategoryMaxBaselineCount(int v) { this.newCategoryMaxBaselineCount = v; }
        public double getFrequencyMultiplier() { return frequencyMultiplier; }
        public void setFrequencyMultiplier(double v) { this.frequencyMultiplier = v; }
    }

    /** 장기 변동성 (문서 §4 원칙 2) — 전체 보유 기간 월별 총지출의 변동계수 */
    public static class Volatility {
        private int windowMonths = 6;
        private int minMonths = 3;
        /** 안정성 = clamp(1 − CV / cvCap, 0, 1) */
        private double cvCap = 0.6;

        public int getWindowMonths() { return windowMonths; }
        public void setWindowMonths(int v) { this.windowMonths = v; }
        public int getMinMonths() { return minMonths; }
        public void setMinMonths(int v) { this.minMonths = v; }
        public double getCvCap() { return cvCap; }
        public void setCvCap(double v) { this.cvCap = v; }
    }

    /** ESTIMATED → CONFIRMED 전환 (문서 §5-2 ③) — 건수 AND 기간 */
    public static class Confirmation {
        private int minRecords = 30;
        private int minDays = 14;

        public int getMinRecords() { return minRecords; }
        public void setMinRecords(int v) { this.minRecords = v; }
        public int getMinDays() { return minDays; }
        public void setMinDays(int v) { this.minDays = v; }
    }

    /** 소비건전성지수 (문서 §5 ③) */
    public static class Score {
        private double savingsWeight = 0.4;
        private double stabilityWeight = 0.3;
        private double plannedWeight = 0.3;
        private int gradeA = 85;
        private int gradeB = 70;
        private int gradeC = 50;

        public double getSavingsWeight() { return savingsWeight; }
        public void setSavingsWeight(double v) { this.savingsWeight = v; }
        public double getStabilityWeight() { return stabilityWeight; }
        public void setStabilityWeight(double v) { this.stabilityWeight = v; }
        public double getPlannedWeight() { return plannedWeight; }
        public void setPlannedWeight(double v) { this.plannedWeight = v; }
        public int getGradeA() { return gradeA; }
        public void setGradeA(int v) { this.gradeA = v; }
        public int getGradeB() { return gradeB; }
        public void setGradeB(int v) { this.gradeB = v; }
        public int getGradeC() { return gradeC; }
        public void setGradeC(int v) { this.gradeC = v; }
    }

    /** 추천 매칭 (문서 §5-1 ③) */
    public static class Matching {
        private double periodWeight = 0.3;
        private double riskWeight = 0.3;
        private double categoryWeight = 0.4;
        private int topN = 3;
        /** 타겟카테고리 없는 범용상품의 중립값 — 구조적 불리 제거 */
        private double neutralCategoryFit = 0.5;
        /** 지출 상위 N위 이내면 부분 점수 */
        private int nearMissTopRank = 3;

        public double getPeriodWeight() { return periodWeight; }
        public void setPeriodWeight(double v) { this.periodWeight = v; }
        public double getRiskWeight() { return riskWeight; }
        public void setRiskWeight(double v) { this.riskWeight = v; }
        public double getCategoryWeight() { return categoryWeight; }
        public void setCategoryWeight(double v) { this.categoryWeight = v; }
        public int getTopN() { return topN; }
        public void setTopN(int v) { this.topN = v; }
        public double getNeutralCategoryFit() { return neutralCategoryFit; }
        public void setNeutralCategoryFit(double v) { this.neutralCategoryFit = v; }
        public int getNearMissTopRank() { return nearMissTopRank; }
        public void setNearMissTopRank(int v) { this.nearMissTopRank = v; }
    }

    public Overspending getOverspending() { return overspending; }
    public void setOverspending(Overspending v) { this.overspending = v; }
    public Fds getFds() { return fds; }
    public void setFds(Fds v) { this.fds = v; }
    public Volatility getVolatility() { return volatility; }
    public void setVolatility(Volatility v) { this.volatility = v; }
    public Confirmation getConfirmation() { return confirmation; }
    public void setConfirmation(Confirmation v) { this.confirmation = v; }
    public Score getScore() { return score; }
    public void setScore(Score v) { this.score = v; }
    public Matching getMatching() { return matching; }
    public void setMatching(Matching v) { this.matching = v; }
}
