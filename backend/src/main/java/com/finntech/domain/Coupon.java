package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 치팅데이 쿠폰 (문서 §5-5 Phase 3). 참은 저축이 일정 이상 쌓이면, 가장 많이 참은(쓸 뻔한) 카테고리로
 * 자유이용권을 제안한다. 사용자가 쓸지/말지 고른다 — 안 쓰면 축하, 쓰면 응원.
 *
 * <p>전부 가상이다 — 실제 금전·상품이 아니라 동기부여용 상징 보상이다.
 */
@Entity
@Table(name = "coupon", indexes = { @Index(name = "idx_coupon_user", columnList = "user_id") })
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 자유이용권 대상 카테고리(가장 많이 참은 분류). */
    @Column(name = "category_code", length = 40)
    private String categoryCode;

    /** 자유이용권 금액. */
    @Column(name = "benefit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal benefitAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.CouponStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Coupon() {}

    public Coupon(Long userId, String categoryCode, BigDecimal benefitAmount, LocalDateTime createdAt) {
        this.userId = userId;
        this.categoryCode = categoryCode;
        this.benefitAmount = benefitAmount;
        this.status = Enums.CouponStatus.OFFERED;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCategoryCode() { return categoryCode; }
    public BigDecimal getBenefitAmount() { return benefitAmount; }
    public Enums.CouponStatus getStatus() { return status; }
    public void setStatus(Enums.CouponStatus s) { this.status = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
