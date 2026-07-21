package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 감사로그 계층 2·3 — Merkle 루트와 TSA 앵커 (문서 §5-4).
 *
 * <p>계층 3(RFC 3161 TSA 앵커링)은 2단계에서 구현한다. 골격 단계에서는
 * {@code anchorStatus = PENDING}으로 두되, 스키마와 검증 경로는 지금 만들어 둔다 —
 * 나중에 붙이려면 스키마 마이그레이션이 필요해지기 때문이다.
 */
@Entity
@Table(name = "audit_batch")
public class AuditBatch {

    public enum AnchorStatus { PENDING, ANCHORED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_root", nullable = false, length = 64)
    private String batchRoot;

    /** 배치 체인 — 이전 배치 루트 (없으면 64자 0) */
    @Column(name = "prev_batch_root", nullable = false, length = 64)
    private String prevBatchRoot;

    @Column(name = "from_seq", nullable = false)
    private Long fromSeq;

    @Column(name = "to_seq", nullable = false)
    private Long toSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_status", nullable = false, length = 20)
    private AnchorStatus anchorStatus = AnchorStatus.PENDING;

    /** RFC 3161 응답(.tsr) base64 */
    @Column(name = "tsa_response", length = 1_000_000)
    private String tsaResponse;

    /**
     * 요청(.tsq) base64. 검증 시 {@code openssl ts -verify -in x.tsr -queryfile x.tsq}에 필요하므로
     * 응답과 <b>함께</b> 보관한다 — 나중에 재생성할 수 없다.
     */
    @Column(name = "tsa_query", length = 1_000_000)
    private String tsaQuery;

    /** TSA가 서명한 시각. "이 해시가 이 시각 이전에 존재했다"의 증거. */
    @Column(name = "tsa_gen_time")
    private java.time.Instant tsaGenTime;

    /** TSA 인증서의 X.500 DN. FreeTSA는 212자를 반환한다 — 짧게 잡으면 저장이 통째로 실패한다. */
    @Column(name = "tsa_name", length = 1000)
    private String tsaName;

    /** 마지막 앵커링 실패 사유. PENDING으로 남은 이유를 화면에 보여주기 위함. */
    @Column(name = "anchor_error", length = 2000)
    private String anchorError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuditBatch() {}

    public AuditBatch(String batchRoot, String prevBatchRoot, Long fromSeq, Long toSeq,
                      LocalDateTime createdAt) {
        this.batchRoot = batchRoot;
        this.prevBatchRoot = prevBatchRoot;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getBatchRoot() { return batchRoot; }
    public String getPrevBatchRoot() { return prevBatchRoot; }
    public Long getFromSeq() { return fromSeq; }
    public Long getToSeq() { return toSeq; }
    public AnchorStatus getAnchorStatus() { return anchorStatus; }
    public void setAnchorStatus(AnchorStatus v) { this.anchorStatus = v; }
    public String getTsaResponse() { return tsaResponse; }
    public void setTsaResponse(String v) { this.tsaResponse = v; }
    public String getTsaQuery() { return tsaQuery; }
    public void setTsaQuery(String v) { this.tsaQuery = v; }
    public java.time.Instant getTsaGenTime() { return tsaGenTime; }
    public void setTsaGenTime(java.time.Instant v) { this.tsaGenTime = v; }
    public String getTsaName() { return tsaName; }
    public void setTsaName(String v) { this.tsaName = v; }
    public String getAnchorError() { return anchorError; }
    public void setAnchorError(String v) { this.anchorError = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
