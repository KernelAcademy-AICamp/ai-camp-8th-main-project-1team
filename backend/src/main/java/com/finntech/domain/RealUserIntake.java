package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 실사용자 신청 한 건 (설계서 Phase 3).
 *
 * <p>실제 사람이 신원 3값과 카드별 명세서를 내면 여기 쌓이고, admin 이 승인하면 제공자로 넘어간다.
 *
 * <h2>여기가 실 개인정보를 보관하는 자리다</h2>
 *
 * <p>그래서 셋을 못박는다. ① 신원과 명세서는 <b>암호화해서</b> 넣는다(KMS envelope).
 * ② 승인·반려 <b>직후 행을 지운다</b> — 대기열은 창고가 아니라 통로다.
 * ③ 아무도 손대지 않은 신청은 만료된다.
 *
 * <p>요약 지표는 평문이다. admin 이 승인을 판단하는 데 필요한 값이고, 그 자체로는
 * 누가 무엇을 샀는지 말하지 않는다. <b>원문을 보여주는 경로는 만들지 않는다</b> —
 * 남의 소비내역 전체를 admin 이 열람하는 것은 그 자체가 개인정보 처리다.
 */
@Entity
@Table(name = "realuser_intake", indexes = {
        @Index(name = "idx_intake_status", columnList = "status,submitted_at"),
        @Index(name = "idx_intake_expires", columnList = "expires_at")
})
public class RealUserIntake {

    public enum Status { RECEIVED, IMPORTED, REJECTED }

    /**
     * 반려 사유 — <b>코드로만 고른다.</b>
     *
     * <p>자유 입력을 두지 않는 이유: 내용에 관한 사유를 쓰려면 내용을 봐야 하고, 그러면
     * "요약만 본다"는 원칙이 무너진다. 여기 있는 것은 전부 <b>기계적 지표</b>다.
     */
    public enum RejectReason {
        TOO_MANY_BAD_ROWS, PERIOD_OUT_OF_RANGE, TOO_FEW_ROWS,
        BUSINESS_NUMBER_MISSING, DUPLICATE_REQUEST, OTHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 신청자에게 준 접수증. 신원을 담지 않는 무작위 값이다. */
    @Column(nullable = false, unique = true, length = 20)
    private String ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.RECEIVED;

    @Column(name = "name_enc", nullable = false, length = 512)
    private byte[] nameEnc;

    @Column(name = "social7_enc", nullable = false, length = 512)
    private byte[] social7Enc;

    @Column(name = "phone_enc", nullable = false, length = 512)
    private byte[] phoneEnc;

    /** 카드별 명세서 묶음(JSON, 암호화). */
    @Column(name = "payload_enc", nullable = false, length = 50_000_000)
    private byte[] payloadEnc;

    /** 마스킹한 이름(홍○동) — 승인에 필요한 것은 "누구인지"가 아니라 "어떤 배치인지"다. */
    @Column(name = "masked_name", nullable = false, length = 40)
    private String maskedName;

    @Column(name = "card_count", nullable = false) private int cardCount;
    @Column(name = "row_count", nullable = false) private int rowCount;
    @Column(name = "rejected_row_count", nullable = false) private int rejectedRowCount;
    @Column(name = "total_amount", nullable = false) private long totalAmount;
    @Column(name = "refund_count", nullable = false) private int refundCount;
    @Column(name = "refund_amount", nullable = false) private long refundAmount;
    @Column(name = "with_business_number", nullable = false) private int withBusinessNumber;
    @Column(name = "distinct_merchants", nullable = false) private int distinctMerchants;
    @Column(name = "period_from") private LocalDate periodFrom;
    @Column(name = "period_to") private LocalDate periodTo;

    @Column(name = "submitted_at", nullable = false) private LocalDateTime submittedAt;
    @Column(name = "submitted_ip", length = 45) private String submittedIp;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "decided_at") private LocalDateTime decidedAt;
    /** 누가 승인했는가 — <b>계정명이 사실로 남는다.</b> */
    @Column(name = "decided_by", length = 40) private String decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", length = 40)
    private RejectReason rejectReason;

    protected RealUserIntake() {}

    public RealUserIntake(String ticket, byte[] nameEnc, byte[] social7Enc, byte[] phoneEnc,
                          byte[] payloadEnc, String maskedName, LocalDateTime submittedAt,
                          String submittedIp, LocalDateTime expiresAt) {
        this.ticket = ticket;
        this.nameEnc = nameEnc;
        this.social7Enc = social7Enc;
        this.phoneEnc = phoneEnc;
        this.payloadEnc = payloadEnc;
        this.maskedName = maskedName;
        this.submittedAt = submittedAt;
        this.submittedIp = submittedIp;
        this.expiresAt = expiresAt;
    }

    public void summarize(int cardCount, int rowCount, int rejectedRowCount, long totalAmount,
                          int refundCount, long refundAmount, int withBusinessNumber,
                          int distinctMerchants, LocalDate periodFrom, LocalDate periodTo) {
        this.cardCount = cardCount;
        this.rowCount = rowCount;
        this.rejectedRowCount = rejectedRowCount;
        this.totalAmount = totalAmount;
        this.refundCount = refundCount;
        this.refundAmount = refundAmount;
        this.withBusinessNumber = withBusinessNumber;
        this.distinctMerchants = distinctMerchants;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
    }

    public void markImported(String adminUsername, LocalDateTime at) {
        this.status = Status.IMPORTED;
        this.decidedBy = adminUsername;
        this.decidedAt = at;
    }

    public void markRejected(String adminUsername, RejectReason reason, LocalDateTime at) {
        this.status = Status.REJECTED;
        this.decidedBy = adminUsername;
        this.rejectReason = reason;
        this.decidedAt = at;
    }

    public Long getId() { return id; }
    public String getTicket() { return ticket; }
    public Status getStatus() { return status; }
    public byte[] getNameEnc() { return nameEnc; }
    public byte[] getSocial7Enc() { return social7Enc; }
    public byte[] getPhoneEnc() { return phoneEnc; }
    public byte[] getPayloadEnc() { return payloadEnc; }
    public String getMaskedName() { return maskedName; }
    public int getCardCount() { return cardCount; }
    public int getRowCount() { return rowCount; }
    public int getRejectedRowCount() { return rejectedRowCount; }
    public long getTotalAmount() { return totalAmount; }
    public int getRefundCount() { return refundCount; }
    public long getRefundAmount() { return refundAmount; }
    public int getWithBusinessNumber() { return withBusinessNumber; }
    public int getDistinctMerchants() { return distinctMerchants; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public String getSubmittedIp() { return submittedIp; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public String getDecidedBy() { return decidedBy; }
    public RejectReason getRejectReason() { return rejectReason; }

    /**
     * 이름 가운데를 가린다 — {@code 홍길동 → 홍○동}, {@code 김철 → 김○}.
     *
     * <p>승인 화면이 사람을 특정할 필요가 없다. 두 신청을 구분할 만큼만 남긴다.
     */
    public static String mask(String name) {
        if (name == null || name.isBlank()) return "○○";
        String trimmed = name.trim();
        if (trimmed.length() == 1) return trimmed;
        if (trimmed.length() == 2) return trimmed.charAt(0) + "○";
        return trimmed.charAt(0) + "○".repeat(trimmed.length() - 2)
                + trimmed.charAt(trimmed.length() - 1);
    }
}
