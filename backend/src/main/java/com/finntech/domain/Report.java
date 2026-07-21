package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 생성된 리포트 저장 — 재조회 시 재계산 방지. */
@Entity
@Table(name = "report", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "period"}))
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** yyyy-MM */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "body_json", nullable = false, length = 1_000_000)
    private String bodyJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Report() {}

    public Report(Long userId, String period, String bodyJson, LocalDateTime createdAt) {
        this.userId = userId;
        this.period = period;
        this.bodyJson = bodyJson;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getPeriod() { return period; }
    public String getBodyJson() { return bodyJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
