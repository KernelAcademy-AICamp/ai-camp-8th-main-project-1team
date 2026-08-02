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

    /**
     * 이 소비를 만든 마이데이터 결제의 키 — {@code UserPayment.rowId(userId, providerPaymentId)}.
     *
     * <p><b>왜 필요한가.</b> 지킴이 원장은 지금까지 <b>어느 가맹점인지 몰랐다</b>. 소비를 원장에 넣을 때
     * 가맹점명 자리에 카테고리 이름("식비")을 넣고 있었고, 사업자번호는 아예 오지 않았다.
     * 그래서 사용자가 "이 결제는 챌린지랑 상관없어요"라고 해도 <b>어느 가맹점을 관대하게 볼지</b>
     * 알 수 없었다 — §8-S가 사업자번호를 키로 삼았는데 그 키에 닿는 길이 없었던 것이다.
     *
     * <p>상호명으로 역산하는 길도 있지만 그건 §8-S가 <b>이미 기각한 방법</b>이다(복원율 75.8%).
     * 결제 키를 직접 들고 있으면 역산이 필요 없다. {@code null}이면 마이데이터에서 온 소비가
     * 아니라는 뜻이다(더미 시드·직접 입력·카드 업로드). (2026-08-02)
     */
    @Column(name = "source_payment_id", length = 80)
    private String sourcePaymentId;

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
    public String getSourcePaymentId() { return sourcePaymentId; }

    /** 마이데이터 결제에서 투영할 때만 붙인다. 생성자에 안 넣은 것은 호출부 대부분이 이 값을 모르기 때문. */
    public Consumption withSourcePayment(String paymentRowId) {
        this.sourcePaymentId = paymentRowId;
        return this;
    }
}
