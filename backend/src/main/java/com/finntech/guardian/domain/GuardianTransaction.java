package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.TxState;
import com.finntech.guardian.domain.GuardianEnums.TxType;
import com.finntech.guardian.domain.GuardianEnums.UndoReason;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 지킴이 원장의 거래 한 건 (설계서 §3.4).
 *
 * <p><b>왜 {@code Consumption}을 그대로 쓰지 않는가.</b> 지킴이는 소비 원본에 없는 판정 상태를
 * 얹는다 — 낙관적 집계 여부, 되돌리기 유예 시각, 어느 날짜로 집계했는지, 잔돈인지.
 * 소비 원본에 이 컬럼들을 넣으면 리포트·점수·FDS 등 다른 소비자가 전부 영향을 받는다.
 * 그래서 원장을 분리하고 {@code sourceConsumptionId}로 원본을 가리킨다.
 *
 * <p><b>낙관적 판정(설계서 D1-A).</b> 들어온 즉시 집계하고 24시간 안에 되돌릴 수 있게 한다.
 * "확인받고 차감"은 사용자가 매번 확인 버튼을 눌러야 해서 알림 피로가 커진다.
 */
@Entity
@Table(name = "guardian_transaction", indexes = {
        @Index(name = "idx_gtx_challenge_date", columnList = "challenge_id, counted_date"),
        @Index(name = "idx_gtx_user_received", columnList = "user_id, received_at"),
        @Index(name = "idx_gtx_undo", columnList = "state, undo_deadline")
})
public class GuardianTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id")
    private Long challengeId;

    /** 이 거래를 만든 소비 원본. 마이데이터 투영에서 끌어온 경우에만 채워지며 중복 적재를 막는다. */
    @Column(name = "source_consumption_id")
    private Long sourceConsumptionId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    @Column(name = "merchant_display_name", length = 120)
    private String merchantDisplayName;

    @Column(nullable = false)
    private long amount;

    @Column(length = 8)
    private String mcc;

    /** 카테고리 코드. 분류 실패면 null이고 상태는 PENDING_CATEGORY로 남는다. */
    @Column(length = 40)
    private String category;

    @Column(name = "category_confidence")
    private Double categoryConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 10)
    private TxType txType = TxType.EXPENSE;

    /** 환불이면 원 거래. 한도를 조용히 복원한다(C12). */
    @Column(name = "original_tx_id")
    private Long originalTxId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TxState state = TxState.PENDING_CATEGORY;

    /** 어느 날짜의 지출로 집계했는가 — 일 판정의 기준. */
    @Column(name = "counted_date")
    private LocalDate countedDate;

    /** 되돌리기 유예 만료 시각. */
    @Column(name = "undo_deadline")
    private LocalDateTime undoDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "undo_reason", length = 20)
    private UndoReason undoReason;

    @Column(name = "undone_at")
    private LocalDateTime undoneAt;

    /** 단건 잔돈 임계 미만 — 개별 알림 대신 버킷에 모은다. */
    @Column(name = "is_micro", nullable = false)
    private boolean micro;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    protected GuardianTransaction() {}

    public GuardianTransaction(Long userId, Long challengeId, LocalDateTime occurredAt, LocalDateTime receivedAt,
                               String merchantName, String merchantDisplayName, long amount, String mcc,
                               String category, Double categoryConfidence, TxType txType,
                               boolean micro, boolean demo) {
        if (amount <= 0) throw new IllegalArgumentException("금액은 0보다 커야 해요");
        this.userId = userId;
        this.challengeId = challengeId;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.merchantName = merchantName;
        this.merchantDisplayName = merchantDisplayName;
        this.amount = amount;
        this.mcc = mcc;
        this.category = category;
        this.categoryConfidence = categoryConfidence;
        this.txType = txType == null ? TxType.EXPENSE : txType;
        this.micro = micro;
        this.demo = demo;
    }

    /** 낙관적 집계 — 상태·집계일·되돌리기 유예를 한 번에 세운다. */
    public void count(LocalDate countedDate, LocalDateTime undoDeadline) {
        this.state = TxState.COUNTED;
        this.countedDate = countedDate;
        this.undoDeadline = undoDeadline;
    }

    /** 집계 대상이 아닌 거래(성역·무관 카테고리·환불). 원장에는 남기되 합계에 넣지 않는다. */
    public void exclude() {
        this.state = TxState.EXCLUDED;
        this.countedDate = null;
    }

    public void undo(UndoReason reason, LocalDateTime at) {
        this.state = reason == UndoReason.EXEMPTION ? TxState.EXEMPTED : TxState.EXCLUDED;
        this.undoReason = reason;
        this.undoneAt = at;
    }

    /** 분류가 늦게 확정된 경우 — PENDING_CATEGORY를 풀어준다. */
    public void assignCategory(String category, Double confidence) {
        this.category = category;
        this.categoryConfidence = confidence;
    }

    @Transient
    public boolean isCounted() { return state == TxState.COUNTED; }

    @Transient
    public boolean isUndoable(LocalDateTime now) {
        return state == TxState.COUNTED && undoDeadline != null && now.isBefore(undoDeadline);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getChallengeId() { return challengeId; }
    public void setChallengeId(Long v) { this.challengeId = v; }
    public Long getSourceConsumptionId() { return sourceConsumptionId; }
    public void setSourceConsumptionId(Long v) { this.sourceConsumptionId = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public String getMerchantName() { return merchantName; }
    public String getMerchantDisplayName() { return merchantDisplayName; }
    public long getAmount() { return amount; }
    public String getMcc() { return mcc; }
    public String getCategory() { return category; }
    public Double getCategoryConfidence() { return categoryConfidence; }
    public TxType getTxType() { return txType; }
    public Long getOriginalTxId() { return originalTxId; }
    public void setOriginalTxId(Long v) { this.originalTxId = v; }
    public TxState getState() { return state; }
    public void setState(TxState v) { this.state = v; }
    public LocalDate getCountedDate() { return countedDate; }
    public LocalDateTime getUndoDeadline() { return undoDeadline; }
    public UndoReason getUndoReason() { return undoReason; }
    public LocalDateTime getUndoneAt() { return undoneAt; }
    public boolean isMicro() { return micro; }
    public boolean isDemo() { return demo; }
}
