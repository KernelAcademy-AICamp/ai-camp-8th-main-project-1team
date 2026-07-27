package com.finntech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
    private Daypart daypart = new Daypart();
    private Recurring recurring = new Recurring();
    private CutCandidate cutCandidate = new CutCandidate();
    private Profile profile = new Profile();

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
        // (W1 처분) window-months는 엔진이 전 기간을 읽어 참조자가 0이던 미사용 키 → 제거.
        private int minMonths = 3;
        /** 안정성 = clamp(1 − CV / cvCap, 0, 1) */
        private double cvCap = 0.6;

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

    /**
     * 시간대 버킷(아침/점심/저녁/심야) — 반복결제 루틴형(②)의 시간대 그룹키와 소비패턴(③)이
     * <b>공유하는 단일 정의</b>(모순 방지). 심야는 ML 피처({@code WasteFeatureExtractor.night}=[23,4])와
     * <b>값만</b> 일치시키되 코드는 독립(모델 결속 없음). 경계는 설정값(원칙 4), 아침/점심/저녁은 5~22시 분할.
     */
    public static class Daypart {
        /** 심야 시작 시(포함). */
        private int nightStart = 23;
        /** 심야 끝 시(포함) — 심야 = hour >= nightStart 또는 hour <= nightEnd. */
        private int nightEnd = 4;
        /** 아침 끝(미포함): 아침 = (nightEnd, morningEnd). */
        private int morningEnd = 11;
        /** 점심 끝(미포함): 점심 = [morningEnd, lunchEnd), 저녁 = [lunchEnd, nightStart). */
        private int lunchEnd = 17;

        /** 시(0~23) → 시간대 버킷 라벨. ②·③이 공유하는 유일한 분류 지점. */
        public String bucketOf(int hour) {
            if (hour >= nightStart || hour <= nightEnd) return "심야";
            if (hour < morningEnd) return "아침";
            if (hour < lunchEnd) return "점심";
            return "저녁";
        }

        public int getNightStart() { return nightStart; }
        public void setNightStart(int v) { this.nightStart = v; }
        public int getNightEnd() { return nightEnd; }
        public void setNightEnd(int v) { this.nightEnd = v; }
        public int getMorningEnd() { return morningEnd; }
        public void setMorningEnd(int v) { this.morningEnd = v; }
        public int getLunchEnd() { return lunchEnd; }
        public void setLunchEnd(int v) { this.lunchEnd = v; }
    }

    /** 반복 결제 탐지(②) — 고정형(Fixed)·루틴형(Routine) 이원화 임계. 값은 잠정(튜닝 예정). */
    public static class Recurring {
        /** 고정형 최소 발생 건수. */
        private int fixedMinCount = 3;
        /** 고정형 금액 변동계수(CV) 상한 — 이 이하면 '고정 금액'. */
        private double fixedCvMax = 0.05;
        /** 고정형 주간 주기 허용(일). */
        private int[] weeklyIntervalDays = {6, 8};
        /** 고정형 월간 주기 허용(일). */
        private int[] monthlyIntervalDays = {27, 33};
        /** 루틴형 관측 창(일) — 최신 시점 기준 롤링. */
        private int routineWindowDays = 28;
        /** 루틴형 등장 비율 임계 = 서로 다른 날 등장수 ÷ 창. */
        private double routineAppearRatio = 0.25;
        /** 루틴형 최소 등장일수 바닥값. */
        private int routineMinDays = 3;
        /** 루틴형 금액 산포 임계 — median 대비 mad 비율이 이 이하면 통과. */
        private double routineDispersionMax = 0.35;

        public int getFixedMinCount() { return fixedMinCount; }
        public void setFixedMinCount(int v) { this.fixedMinCount = v; }
        public double getFixedCvMax() { return fixedCvMax; }
        public void setFixedCvMax(double v) { this.fixedCvMax = v; }
        public int[] getWeeklyIntervalDays() { return weeklyIntervalDays; }
        public void setWeeklyIntervalDays(int[] v) { this.weeklyIntervalDays = v; }
        public int[] getMonthlyIntervalDays() { return monthlyIntervalDays; }
        public void setMonthlyIntervalDays(int[] v) { this.monthlyIntervalDays = v; }
        public int getRoutineWindowDays() { return routineWindowDays; }
        public void setRoutineWindowDays(int v) { this.routineWindowDays = v; }
        public double getRoutineAppearRatio() { return routineAppearRatio; }
        public void setRoutineAppearRatio(double v) { this.routineAppearRatio = v; }
        public int getRoutineMinDays() { return routineMinDays; }
        public void setRoutineMinDays(int v) { this.routineMinDays = v; }
        public double getRoutineDispersionMax() { return routineDispersionMax; }
        public void setRoutineDispersionMax(double v) { this.routineDispersionMax = v; }
    }

    /**
     * 절약 후보(⑤) — 낭비형 임계치 + 3등급 카테고리. 카테고리 목록은 설정값(원칙 4)이며 category2로 매칭.
     * 보호 목록은 ML 특징의 ESSENTIAL({@code WasteFeatureExtractor}, 모델 결속)과 <b>독립</b>이다(값만 겹칠 수 있음).
     */
    public static class CutCandidate {
        /** 낭비형 후보 임계 — category1의 낭비금액 비율이 이 이상이면 후보({@code WasteScoringService}의 하드코딩 0.5 이전). */
        private double wasteRatioThreshold = 0.5;
        /** 제거가능 — 대표금액 전체를 절감액으로(커피·배달·구독·쇼핑류). */
        private List<String> removable = List.of("카페", "배달", "스트리밍", "백화점", "면세점", "간편결제");
        /** 최적화가능 — 이 카테고리 중앙값 초과분만 절감액으로(식비·교통). */
        private List<String> optimizable = List.of("한식", "중식", "일식", "양식", "분식", "대중교통", "택시");
        /** 보호 — 후보에서 원천 제외(공과금·통신비·약국·보험). */
        private List<String> protectedCategories = List.of("공과금", "통신비", "약국", "보험");

        public double getWasteRatioThreshold() { return wasteRatioThreshold; }
        public void setWasteRatioThreshold(double v) { this.wasteRatioThreshold = v; }
        public List<String> getRemovable() { return removable; }
        public void setRemovable(List<String> v) { this.removable = v; }
        public List<String> getOptimizable() { return optimizable; }
        public void setOptimizable(List<String> v) { this.optimizable = v; }
        public List<String> getProtectedCategories() { return protectedCategories; }
        public void setProtectedCategories(List<String> v) { this.protectedCategories = v; }
    }

    /**
     * 이상소비지수(④) 가중치 — 4개 성분(낭비비율·과소비집중·지출변동성·심야충동)의 해석가능 가중합.
     * 지수 = 100 × Σ(weight_i × component_i[0,1]). 가중치 합은 1을 권장(합≠1도 동작하나 의미가 흐려짐).
     */
    public static class Profile {
        /** 낭비비율(제거가능 카테고리 지출 ÷ 총지출) 가중치. */
        private double wasteWeight = 0.4;
        /** 과소비집중도(최대 category1 지출비율) 가중치. */
        private double concentrationWeight = 0.25;
        /** 지출변동성(월지출 CV ÷ cvCap, clamp) 가중치. */
        private double volatilityWeight = 0.15;
        /** 심야·충동비중(심야 지출 + 루틴형 낭비지출 ÷ 총지출) 가중치. */
        private double nightImpulseWeight = 0.2;

        public double getWasteWeight() { return wasteWeight; }
        public void setWasteWeight(double v) { this.wasteWeight = v; }
        public double getConcentrationWeight() { return concentrationWeight; }
        public void setConcentrationWeight(double v) { this.concentrationWeight = v; }
        public double getVolatilityWeight() { return volatilityWeight; }
        public void setVolatilityWeight(double v) { this.volatilityWeight = v; }
        public double getNightImpulseWeight() { return nightImpulseWeight; }
        public void setNightImpulseWeight(double v) { this.nightImpulseWeight = v; }
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
    public Daypart getDaypart() { return daypart; }
    public void setDaypart(Daypart v) { this.daypart = v; }
    public Recurring getRecurring() { return recurring; }
    public void setRecurring(Recurring v) { this.recurring = v; }
    public CutCandidate getCutCandidate() { return cutCandidate; }
    public void setCutCandidate(CutCandidate v) { this.cutCandidate = v; }
    public Profile getProfile() { return profile; }
    public void setProfile(Profile v) { this.profile = v; }
}
