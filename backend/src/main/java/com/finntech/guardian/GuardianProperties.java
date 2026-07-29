package com.finntech.guardian;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지킴이 Agent 임계치 — 전부 설정값이다 (마스터 §4 원칙 4).
 *
 * <p>설계서는 "임계값을 DB에 넣지 말고 깃으로 버전 관리하라"고 요구하고, 이 저장소는
 * "임계치는 전부 {@code application.yml}"을 요구한다. yml도 깃 안에 있으므로 둘 다 만족한다.
 * 케이스의 <b>구조</b>(우선순위·톤·쿨다운)는 값이 아니라 로직이라 {@link GuardianRules}에 남는다.
 *
 * <p>여기 있는 기본값은 설계서 v1.2의 수치이며, {@code GuardianRulesTest}가 이 값으로 검산을 고정한다.
 */
@ConfigurationProperties(prefix = "finntech.guardian")
public class GuardianProperties {

    /** 챌린지 한도 대비 이 비율을 넘으면 AT_RISK(그리고 C3 발화). */
    private double atRiskRatio = 0.80;

    /** 부분 달성으로 인정하는 달성률 하한. 5주차 인터뷰로 검증할 가정값이다. */
    private double partialUnlockThreshold = 0.70;

    /** 이 신뢰도 미만이면 집계하지 않고 C7로 되묻는다 — 분류 전에는 판정할 수 없다. */
    private double categoryConfidenceThreshold = 0.70;

    /** 이번 주 같은 카테고리 결제가 이 건수에 이르면 C2(패턴 지적). */
    private int repeatWeeklyCount = 3;

    /** 단건 이 금액 미만은 잔돈으로 본다. */
    private long microTxThreshold = 5_000L;

    /** 잔돈 버킷 합계가 이 금액을 넘으면 C8. */
    private long microBucketTrigger = 12_000L;

    /** 되돌리기 유예(시간). 이 시간이 지나야 판정이 확정된다. */
    private int undoWindowHours = 24;

    /** 챌린지 기본 기간(일). 사용자 지정 기간은 설계서 D4에서 보류됐다. */
    private int defaultDurationDays = 30;

    /**
     * 주간 미션의 목표 — 한 주에 만들어야 할 무지출 연속 일수. 0이면 주간 미션을 만들지 않는다.
     *
     * <p>설계서 §9가 미션 내용을 열린 항목("보상 계층이 정한다")으로 두어 생성부가 없었고,
     * 그래서 주간 미션 30P가 한 번도 지급되지 않았다. 개입 케이스 C5(무지출 3일 연속)와
     * 같은 기준을 써서 사용자가 이미 아는 목표를 그대로 미션으로 삼는다.
     */
    private int weeklyMissionNoSpendDays = 3;

    private Notification notification = new Notification();
    private Point point = new Point();
    private Shop shop = new Shop();

    /** 알림 예산 (설계서 §4.1). */
    public static class Notification {
        /** 하루 푸시 상한 — 거래성 1 + 시간성 1. */
        private int dailyPushLimit = 2;
        /** 야간 시작(이 시각부터 푸시 금지). */
        private int nightStartHour = 22;
        /** 야간 종료(이 시각에 모아 발송). */
        private int nightEndHour = 8;
        /** 게임 이벤트는 푸시하지 않는다 — 홈 미개봉 뱃지 + 앱 안 모달로만. */
        private boolean gameEventsUsePush = false;

        public int getDailyPushLimit() { return dailyPushLimit; }
        public void setDailyPushLimit(int v) { this.dailyPushLimit = v; }
        public int getNightStartHour() { return nightStartHour; }
        public void setNightStartHour(int v) { this.nightStartHour = v; }
        public int getNightEndHour() { return nightEndHour; }
        public void setNightEndHour(int v) { this.nightEndHour = v; }
        public boolean isGameEventsUsePush() { return gameEventsUsePush; }
        public void setGameEventsUsePush(boolean v) { this.gameEventsUsePush = v; }
    }

    /** 포인트 (설계서 §3.7). 미션 30 + 위기 방어 20 + 라벨링 최대 50(25건) = 주간 상한 100. */
    public static class Point {
        private int weeklyMission = 30;
        private int riskDefense = 20;
        private int labeling = 2;
        private int monthlyComplete = 100;
        private int weeklyCap = 100;
        /** 완주 보상은 장기 보상이라 주간 상한과 별도로 지급한다. */
        private boolean monthlyExemptFromCap = true;
        /** 출석·로그인 보상은 없다. 포인트는 절약 행동의 증명이어야 한다. */
        private int attendanceReward = 0;

        public int getWeeklyMission() { return weeklyMission; }
        public void setWeeklyMission(int v) { this.weeklyMission = v; }
        public int getRiskDefense() { return riskDefense; }
        public void setRiskDefense(int v) { this.riskDefense = v; }
        public int getLabeling() { return labeling; }
        public void setLabeling(int v) { this.labeling = v; }
        public int getMonthlyComplete() { return monthlyComplete; }
        public void setMonthlyComplete(int v) { this.monthlyComplete = v; }
        public int getWeeklyCap() { return weeklyCap; }
        public void setWeeklyCap(int v) { this.weeklyCap = v; }
        public boolean isMonthlyExemptFromCap() { return monthlyExemptFromCap; }
        public void setMonthlyExemptFromCap(boolean v) { this.monthlyExemptFromCap = v; }
        public int getAttendanceReward() { return attendanceReward; }
        public void setAttendanceReward(int v) { this.attendanceReward = v; }
    }

    /** 상점 가격 — 포인트로만 산다. 현금 결제 경로는 두지 않는다. */
    public static class Shop {
        private int object = 30;
        private int furniture = 150;
        private int wallFloor = 400;

        public int getObject() { return object; }
        public void setObject(int v) { this.object = v; }
        public int getFurniture() { return furniture; }
        public void setFurniture(int v) { this.furniture = v; }
        public int getWallFloor() { return wallFloor; }
        public void setWallFloor(int v) { this.wallFloor = v; }
    }

    public double getAtRiskRatio() { return atRiskRatio; }
    public void setAtRiskRatio(double v) { this.atRiskRatio = v; }
    public double getPartialUnlockThreshold() { return partialUnlockThreshold; }
    public void setPartialUnlockThreshold(double v) { this.partialUnlockThreshold = v; }
    public double getCategoryConfidenceThreshold() { return categoryConfidenceThreshold; }
    public void setCategoryConfidenceThreshold(double v) { this.categoryConfidenceThreshold = v; }
    public int getRepeatWeeklyCount() { return repeatWeeklyCount; }
    public void setRepeatWeeklyCount(int v) { this.repeatWeeklyCount = v; }
    public long getMicroTxThreshold() { return microTxThreshold; }
    public void setMicroTxThreshold(long v) { this.microTxThreshold = v; }
    public long getMicroBucketTrigger() { return microBucketTrigger; }
    public void setMicroBucketTrigger(long v) { this.microBucketTrigger = v; }
    public int getUndoWindowHours() { return undoWindowHours; }
    public void setUndoWindowHours(int v) { this.undoWindowHours = v; }
    public int getDefaultDurationDays() { return defaultDurationDays; }
    public void setDefaultDurationDays(int v) { this.defaultDurationDays = v; }

    public int getWeeklyMissionNoSpendDays() { return weeklyMissionNoSpendDays; }
    public void setWeeklyMissionNoSpendDays(int v) { this.weeklyMissionNoSpendDays = v; }
    public Notification getNotification() { return notification; }
    public void setNotification(Notification v) { this.notification = v; }
    public Point getPoint() { return point; }
    public void setPoint(Point v) { this.point = v; }
    public Shop getShop() { return shop; }
    public void setShop(Shop v) { this.shop = v; }
}
