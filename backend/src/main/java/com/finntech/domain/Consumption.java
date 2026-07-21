package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 소비 내역. 수집 항목은 카테고리/금액/일시/계획소비 여부 4개뿐이다 (문서 §5-3).
 * {@code source}로 더미 시드와 실사용자 입력을 분리한다 (문서 §5-2).
 */
@Entity
@Table(name = "consumption", indexes = {
        @Index(name = "idx_consumption_user_time", columnList = "user_id, occurred_at")
})
public class Consumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 계획소비 여부 — 소비건전성지수의 '계획소비 비율' 근거 */
    @Column(name = "is_planned", nullable = false)
    private boolean planned;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.DataSource source;

    protected Consumption() {}

    public Consumption(Long userId, Category category, BigDecimal amount,
                       LocalDateTime occurredAt, boolean planned, Enums.DataSource source) {
        this.userId = userId;
        this.category = category;
        this.amount = amount;
        this.occurredAt = occurredAt;
        this.planned = planned;
        this.source = source;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Category getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public boolean isPlanned() { return planned; }
    public Enums.DataSource getSource() { return source; }
}
