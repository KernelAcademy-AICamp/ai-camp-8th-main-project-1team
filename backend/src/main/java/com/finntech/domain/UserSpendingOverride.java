package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 개인화 낭비/필수 재분류 (W8-5, 요구 10). "통념상 낭비지만 본인에겐 필수"인 category2를 사용자가
 * 지정하면, 그 사용자에 한해 ML 판정을 이 라벨로 덮어쓴다(취미·필수 보호). 파기 흐름에 포함(PrivacyService).
 */
@Entity
@Table(name = "user_spending_override",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "category2"}))
public class UserSpendingOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category2", nullable = false, length = 30)
    private String category2;

    /** true=낭비로, false=필수로 강제. */
    @Column(name = "forced_waste", nullable = false)
    private boolean forcedWaste;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected UserSpendingOverride() {}

    public UserSpendingOverride(Long userId, String category2, boolean forcedWaste, LocalDateTime createdAt) {
        this.userId = userId;
        this.category2 = category2;
        this.forcedWaste = forcedWaste;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCategory2() { return category2; }
    public boolean isForcedWaste() { return forcedWaste; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
