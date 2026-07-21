package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 감사로그 계층 1 — prev_hash 선형 체인 (문서 §5-4).
 * entry_hash(n) = SHA256(prev_hash || canonical_json(entry_n))
 */
@Entity
@Table(name = "audit_log", indexes = @Index(name = "idx_audit_seq", columnList = "seq"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 1부터 증가하는 체인 순번 */
    @Column(nullable = false, unique = true)
    private Long seq;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    /** 정규화된 JSON 페이로드 — 이 바이트가 그대로 해시 입력이 된다 */
    // @Lob은 H2에서 VARCHAR로 떨어져 값이 잘린다. 길이를 명시하면 H2는 CHARACTER VARYING,
    // MySQL은 length>65535라 LONGTEXT로 매핑된다.
    @Column(name = "payload_json", nullable = false, length = 1_000_000)
    private String payloadJson;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "entry_hash", nullable = false, length = 64)
    private String entryHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 배치 앵커링 후 채워진다 (계층 2·3) */
    @Column(name = "batch_id")
    private Long batchId;

    protected AuditLog() {}

    public AuditLog(Long seq, String eventType, String payloadJson,
                    String prevHash, String entryHash, LocalDateTime createdAt) {
        this.seq = seq;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getSeq() { return seq; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public String getPrevHash() { return prevHash; }
    public String getEntryHash() { return entryHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long v) { this.batchId = v; }
}
