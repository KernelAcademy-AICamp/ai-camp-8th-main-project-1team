package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * admin 복구 코드 — 폰을 잃어버렸을 때 빠져나오는 길.
 *
 * <p>이것이 없으면 <b>폰 분실이 곧 영구 잠금</b>이다. 발급 시 원문을 한 번만 보여주고
 * 저장은 해시로만 한다(비밀번호와 같은 방식). 한 번 쓰면 그 행을 지운다 —
 * 일회용이라야 종이가 새어도 피해가 한 번으로 끝난다.
 */
@Entity
@Table(name = "admin_recovery_code", indexes = @Index(name = "idx_recovery_admin", columnList = "admin_id"))
public class AdminRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AdminRecoveryCode() {}

    public AdminRecoveryCode(Long adminId, String codeHash, LocalDateTime createdAt) {
        this.adminId = adminId;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getAdminId() { return adminId; }
    public String getCodeHash() { return codeHash; }
}
