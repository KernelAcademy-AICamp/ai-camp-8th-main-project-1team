package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 포인트 이벤트 (문서 §5-5 게임화 저축 루프).
 *
 * <p>저장하는 것은 <b>AVOIDED("살 뻔했다")</b>와 <b>TRANSFER(적금 가상 이체)</b> 둘뿐이다.
 * 월급(GRANT)은 {@code AppUser.monthlyIncome} 설정에서, 실지출(SPEND)은 {@link Consumption}에서 파생하므로
 * 별도로 저장하지 않는다 — 소비 데이터를 중복 모델링하지 않기 위함이다.
 *
 * <p>포인트는 <b>가상</b>이고 적금·상품은 <b>더미</b>다. TRANSFER는 화면상의 가상 이동이며 실제 송금·결제가 아니다.
 */
@Entity
@Table(name = "point_event", indexes = {
        @Index(name = "idx_point_user_time", columnList = "user_id, occurred_at")
})
public class PointEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.PointEventType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** SAVE는 어떤 카테고리를 참았는지. TRANSFER/WITHDRAWAL은 null. */
    @Column(name = "category_code", length = 40)
    private String categoryCode;

    /** TRANSFER/WITHDRAWAL이 어떤 목표 버킷을 대상으로 했는지. SAVE는 null. */
    @Column(name = "goal_id")
    private Long goalId;

    /** 세부 사유 — MANUAL(살뻔)·GUILTY_PLEASURE·SPEND_LESS·HABIT·OVERSPEND 등. 이벤트 피드 표시용. */
    @Column(length = 30)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(length = 100)
    private String memo;

    protected PointEvent() {}

    public PointEvent(Long userId, Enums.PointEventType type, BigDecimal amount,
                      String categoryCode, Long goalId, String reason,
                      LocalDateTime occurredAt, String memo) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.categoryCode = categoryCode;
        this.goalId = goalId;
        this.reason = reason;
        this.occurredAt = occurredAt;
        this.memo = memo;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Enums.PointEventType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCategoryCode() { return categoryCode; }
    public Long getGoalId() { return goalId; }
    public String getReason() { return reason; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public String getMemo() { return memo; }
}
