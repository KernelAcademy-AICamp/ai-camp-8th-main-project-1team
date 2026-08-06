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

    /** 챌린지 예산 대비 이 비율을 넘으면 AT_RISK(그리고 C3 발화). */
    private double atRiskRatio = 0.80;

    /** 부분 달성으로 인정하는 달성률 하한. 5주차 인터뷰로 검증할 가정값이다. */
    private double partialUnlockThreshold = 0.70;

    /** 이 신뢰도 미만이면 {@code UNKNOWN}으로 두고 차감을 보류한 채 C7로 되묻는다. */
    private double categoryConfidenceThreshold = 0.70;

    /** 이번 주 같은 카테고리 결제가 이 건수에 이르면 C2(패턴 지적). */
    private int repeatWeeklyCount = 3;

    // ---- v1.5 신설 ------------------------------------------------------

    /**
     * C5 — 무지출 연속이 이 값의 <b>배수</b>일 때마다 칭찬한다(3·6·9일…).
     * v1.2는 "3일 이상"이라 넷째 날부터 매일 걸렸고, 매일 울리면 칭찬이 닳는다.
     */
    private int noSpendPraiseInterval = 3;

    /** C10·C11 — 종료 임박 판정에 들어가는 남은 일수. */
    private int endingSoonDaysLeft = 3;

    /**
     * C10과 C11을 가르는 사용률. 이상이면 사실 통보(C11), 미만이면 격려(C10).
     * v1.2는 "초과했는가(1.0)"로 갈랐는데, 0.9에서 사흘 남은 사람에게 격려를 보내면 어긋난다.
     */
    private double endingSoonRatio = 0.85;

    /** C9 — 최근 4주 중 이 횟수 이상 반복된 시간대만 사전 넛지 대상. */
    private int nudgeFrequency4w = 3;

    /** C9 — 해당 시간대 몇 분 전에 보낼지. */
    private int nudgeLeadMinutes = 30;

    /**
     * 세리머니 모달 자동 노출 — 챌린지 시작 후 이 일수 안에는 매일 띄운다 (v1.5 §5.3).
     * 그 뒤로는 희귀 이상일 때만. 매일 띄우면 연출이 광고처럼 읽힌다.
     */
    private int ceremonyAutoOpenFirstDays = 7;

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
    /**
     * 횟수 줄이기 미션의 감축 비율. 0.2면 "지난 4주 주당 평균의 80%".
     *
     * <p>한 번 줄이기(-1)로 두면 자주 쓰는 카테고리에서 미션이 무의미해진다 — 주 18회를
     * 17회로 줄이라는 것은 통계지 미션이 아니다.
     */
    private double weeklyMissionCutRatio = 0.2;

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
        /** 반복 표현 감지에 넘길 최근 알림 수 (v1.5 §6.3). */
        private int recentPhraseWindow = 5;
        private int maxTitleLen = 20;
        private int maxBodyLen = 90;
        /** 홈 한마디 — 2줄 × 22자. 넘으면 레이아웃이 깨진다 (v1.5 §4.2). */
        private int onelineMaxLen = 44;

        public int getRecentPhraseWindow() { return recentPhraseWindow; }
        public void setRecentPhraseWindow(int v) { this.recentPhraseWindow = v; }
        public int getMaxTitleLen() { return maxTitleLen; }
        public void setMaxTitleLen(int v) { this.maxTitleLen = v; }
        public int getMaxBodyLen() { return maxBodyLen; }
        public void setMaxBodyLen(int v) { this.maxBodyLen = v; }
        public int getOnelineMaxLen() { return onelineMaxLen; }
        public void setOnelineMaxLen(int v) { this.onelineMaxLen = v; }
        public int getDailyPushLimit() { return dailyPushLimit; }
        public void setDailyPushLimit(int v) { this.dailyPushLimit = v; }
        public int getNightStartHour() { return nightStartHour; }
        public void setNightStartHour(int v) { this.nightStartHour = v; }
        public int getNightEndHour() { return nightEndHour; }
        public void setNightEndHour(int v) { this.nightEndHour = v; }
        public boolean isGameEventsUsePush() { return gameEventsUsePush; }
        public void setGameEventsUsePush(boolean v) { this.gameEventsUsePush = v; }
    }

    /** 포인트 (스펙 v1.5 §5.5). 미션 30 + 위기 방어 20 + 라벨링 최대 50(25건) = 주간 상한 100. */
    public static class Point {
        /** 주간 미션 <b>총액</b> — 진행 중 미션이 나눠 갖는다(1개 30 · 2개 15 · 3개 10). */
        private int weeklyMission = 30;
        /** 동시에 진행할 수 있는 미션 수 (v1.5 §5.5). */
        private int maxActiveMissions = 3;
        private int riskDefense = 20;
        private int labeling = 2;
        /** 라벨링 주간 상한 — 25건까지 (v1.5 §5.5). */
        private int labelingWeeklyCap = 50;
        private int monthlyComplete = 100;
        private int weeklyCap = 100;
        /** 완주 보상은 장기 보상이라 주간 상한과 별도로 지급한다. */
        private boolean monthlyExemptFromCap = true;
        /** 중복 사물 전환 (v1.5 §5.5) — 등급별. 주간 상한 밖. */
        private int duplicateCommon = 5;
        private int duplicateRare = 15;
        private int duplicateEpic = 30;
        /** 출석·로그인 보상은 없다. 포인트는 절약 행동의 증명이어야 한다. */
        private int attendanceReward = 0;

        public int getMaxActiveMissions() { return maxActiveMissions; }
        public void setMaxActiveMissions(int v) { this.maxActiveMissions = v; }
        public int getLabelingWeeklyCap() { return labelingWeeklyCap; }
        public void setLabelingWeeklyCap(int v) { this.labelingWeeklyCap = v; }
        public int getDuplicateCommon() { return duplicateCommon; }
        public void setDuplicateCommon(int v) { this.duplicateCommon = v; }
        public int getDuplicateRare() { return duplicateRare; }
        public void setDuplicateRare(int v) { this.duplicateRare = v; }
        public int getDuplicateEpic() { return duplicateEpic; }
        public void setDuplicateEpic(int v) { this.duplicateEpic = v; }
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
    public int getNoSpendPraiseInterval() { return noSpendPraiseInterval; }
    public void setNoSpendPraiseInterval(int v) { this.noSpendPraiseInterval = v; }
    public int getEndingSoonDaysLeft() { return endingSoonDaysLeft; }
    public void setEndingSoonDaysLeft(int v) { this.endingSoonDaysLeft = v; }
    public double getEndingSoonRatio() { return endingSoonRatio; }
    public void setEndingSoonRatio(double v) { this.endingSoonRatio = v; }
    public int getNudgeFrequency4w() { return nudgeFrequency4w; }
    public void setNudgeFrequency4w(int v) { this.nudgeFrequency4w = v; }
    public int getNudgeLeadMinutes() { return nudgeLeadMinutes; }
    public void setNudgeLeadMinutes(int v) { this.nudgeLeadMinutes = v; }
    public int getCeremonyAutoOpenFirstDays() { return ceremonyAutoOpenFirstDays; }
    public void setCeremonyAutoOpenFirstDays(int v) { this.ceremonyAutoOpenFirstDays = v; }
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

    public double getWeeklyMissionCutRatio() { return weeklyMissionCutRatio; }
    public void setWeeklyMissionCutRatio(double v) { this.weeklyMissionCutRatio = v; }
    public int getWeeklyMissionNoSpendDays() { return weeklyMissionNoSpendDays; }
    public void setWeeklyMissionNoSpendDays(int v) { this.weeklyMissionNoSpendDays = v; }
    public Notification getNotification() { return notification; }
    public void setNotification(Notification v) { this.notification = v; }
    public Point getPoint() { return point; }
    public void setPoint(Point v) { this.point = v; }
    public Shop getShop() { return shop; }
    public void setShop(Shop v) { this.shop = v; }
}
