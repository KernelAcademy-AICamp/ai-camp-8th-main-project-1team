package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 이상소비 감지 이력. */
@Entity
@Table(name = "alert")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "consumption_id", nullable = false)
    private Long consumptionId;

    @Column(name = "category_code", nullable = false, length = 40)
    private String categoryCode;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** Modified Z-score */
    @Column(name = "deviation_score", nullable = false)
    private Double deviationScore;

    /** 일치한 룰 목록 (쉼표 구분) */
    @Column(name = "matched_rules", nullable = false, length = 200)
    private String matchedRules;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Alert() {}

    public Alert(Long userId, Long consumptionId, String categoryCode, BigDecimal amount,
                 LocalDateTime occurredAt, Double deviationScore, String matchedRules,
                 LocalDateTime createdAt) {
        this.userId = userId;
        this.consumptionId = consumptionId;
        this.categoryCode = categoryCode;
        this.amount = amount;
        this.occurredAt = occurredAt;
        this.deviationScore = deviationScore;
        this.matchedRules = matchedRules;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getConsumptionId() { return consumptionId; }
    public String getCategoryCode() { return categoryCode; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public Double getDeviationScore() { return deviationScore; }
    public String getMatchedRules() { return matchedRules; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
