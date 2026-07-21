package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 목표 마일스톤 (문서 §5-5). 목표를 단계로 쪼갠 것 — 예: 도쿄여행 = ✈️비행기표·🏨호텔·🎫교통패스.
 *
 * <p>목표 잔액이 마일스톤의 <b>누적 비용</b>을 넘으면 순서대로 하나씩 '획득'된다. 저축이 추상적 숫자가 아니라
 * 눈에 보이는 구체적 단계로 채워지게 한다.
 */
@Entity
@Table(name = "goal_milestone", indexes = { @Index(name = "idx_milestone_goal", columnList = "goal_id") })
public class GoalMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goal_id", nullable = false)
    private Long goalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 16)
    private String emoji;

    /** 이 단계에 필요한 금액. 앞 단계들의 누적 + 이 비용 ≤ 잔액이면 획득. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal cost;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected GoalMilestone() {}

    public GoalMilestone(Long goalId, Long userId, String name, String emoji, BigDecimal cost, int sortOrder) {
        this.goalId = goalId;
        this.userId = userId;
        this.name = name;
        this.emoji = emoji;
        this.cost = cost;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public Long getGoalId() { return goalId; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmoji() { return emoji; }
    public BigDecimal getCost() { return cost; }
    public int getSortOrder() { return sortOrder; }
}
