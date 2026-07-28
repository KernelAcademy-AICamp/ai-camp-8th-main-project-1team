package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.MissionCondition;
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
    private MissionCondition conditionType;

    /** CATEGORY_COUNT_MAX일 때만 채워진다. */
    @Column(length = 40)
    private String category;

    @Column(nullable = false)
    private int threshold;

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

    public WeeklyMission(Long userId, Long challengeId, MissionCondition conditionType, String category,
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

    public void evaluate(boolean achieved, LocalDateTime at) {
        this.achieved = achieved;
        this.evaluatedAt = at;
    }

    /** 현재 진행값이 조건을 만족하는가. 순수 판정 — 서비스가 진행값을 세어 넣는다. */
    public boolean satisfiedBy(int current) {
        return switch (conditionType) {
            case CATEGORY_COUNT_MAX -> current <= threshold;
            case NO_SPEND_STREAK_MIN, LABELING_COUNT_MIN -> current >= threshold;
        };
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getChallengeId() { return challengeId; }
    public MissionCondition getConditionType() { return conditionType; }
    public String getCategory() { return category; }
    public int getThreshold() { return threshold; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public Boolean getAchieved() { return achieved; }
    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
