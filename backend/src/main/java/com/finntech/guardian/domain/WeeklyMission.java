package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.MissionType;

import java.time.DayOfWeek;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주간 미션 — 내용은 보상 계층이 정하고, 달성 판정은 지킴이가 한다 (설계서 §3.7).
 *
 * <p>판정을 보상 계층이 하면 규칙이 두 곳으로 갈라진다. 미션은 "무엇을"만 정하고
 * "됐는지"는 언제나 지킴이의 원장이 답한다.
 */
@Entity
@Table(name = "guardian_weekly_mission", indexes = {
        @Index(name = "idx_gmission_period", columnList = "user_id, period_start")
})
public class WeeklyMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id")
    private Long challengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 30)
    private MissionType conditionType;

    /** 카테고리 기반 유형(MAX_COUNT·AVOID_SLOT)에서만 채워진다. */
    @Column(length = 40)
    private String category;

    @Column(nullable = false)
    private int threshold;

    // ---- AVOID_SLOT 전용 (스펙 v1.5 §5.5) --------------------------------
    // "금요일 저녁에는 배달을 안 시킨다" 같은 미션. 요일·시간은 반드시 KST로 판정한다 —
    // UTC로 재면 금요일 저녁 22시 주문이 토요일로 넘어가 미션이 통과해 버린다.

    /** 피해야 할 요일. */
    @Enumerated(EnumType.STRING)
    @Column(name = "avoid_weekday", length = 12)
    private DayOfWeek avoidWeekday;

    /** 피해야 할 시간대 시작(포함). */
    @Column(name = "avoid_hour_start")
    private Integer avoidHourStart;

    /** 피해야 할 시간대 끝(제외). */
    @Column(name = "avoid_hour_end")
    private Integer avoidHourEnd;

    /** 이 미션 몫의 포인트. 진행 중 미션이 주간 총액 30P를 나눠 갖는다. */
    @Column(name = "point_share", nullable = false)
    private int pointShare;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** null이면 아직 평가 전. */
    @Column
    private Boolean achieved;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected WeeklyMission() {}

    public WeeklyMission(Long userId, Long challengeId, MissionType conditionType, String category,
                         int threshold, LocalDate periodStart, LocalDate periodEnd, LocalDateTime createdAt) {
        this.userId = userId;
        this.challengeId = challengeId;
        this.conditionType = conditionType;
        this.category = category;
        this.threshold = threshold;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.createdAt = createdAt;
    }

    /** AVOID_SLOT 미션 — "금요일 19~22시에는 배달 결제가 없을 것". */
    public static WeeklyMission avoidSlot(Long userId, Long challengeId, String category,
                                          DayOfWeek weekday, int hourStart, int hourEnd,
                                          LocalDate periodStart, LocalDate periodEnd, LocalDateTime createdAt) {
        WeeklyMission m = new WeeklyMission(userId, challengeId, MissionType.AVOID_SLOT, category,
                0, periodStart, periodEnd, createdAt);
        m.avoidWeekday = weekday;
        m.avoidHourStart = hourStart;
        m.avoidHourEnd = hourEnd;
        return m;
    }

    public void evaluate(boolean achieved, LocalDateTime at) {
        this.achieved = achieved;
        this.evaluatedAt = at;
    }

    /**
     * 현재 진행값이 조건을 만족하는가. 순수 판정 — 서비스가 진행값을 세어 넣는다.
     *
     * <p>{@code AVOID_SLOT}은 "슬롯에 걸린 결제 건수"를 받아 0이어야 통과한다.
     */
    public boolean satisfiedBy(int current) {
        return switch (conditionType) {
            case MAX_COUNT -> current <= threshold;
            case AVOID_SLOT -> current == 0;
            case NO_SPEND_STREAK_MIN, LABELING_COUNT_MIN -> current >= threshold;
        };
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getChallengeId() { return challengeId; }
    public MissionType getConditionType() { return conditionType; }
    public DayOfWeek getAvoidWeekday() { return avoidWeekday; }
    public Integer getAvoidHourStart() { return avoidHourStart; }
    public Integer getAvoidHourEnd() { return avoidHourEnd; }
    public int getPointShare() { return pointShare; }
    public void setPointShare(int v) { this.pointShare = v; }
    public String getCategory() { return category; }
    public int getThreshold() { return threshold; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public Boolean getAchieved() { return achieved; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
