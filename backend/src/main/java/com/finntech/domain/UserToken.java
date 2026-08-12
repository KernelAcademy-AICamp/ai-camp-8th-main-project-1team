package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 인증 토큰 (설계서 Phase 1).
 *
 * <p><b>토큰 원문을 저장하지 않는다.</b> 저장하는 것은 {@code SHA-256(토큰)} 뿐이라, 이 표가
 * 통째로 유출돼도 그 값으로는 로그인할 수 없다. 비밀번호를 해시로 두는 것과 같은 이유다.
 *
 * <p>비밀번호와 달리 <b>느린 KDF(Argon2)를 쓰지 않는다.</b> 토큰은 32바이트 무작위라 애초에
 * 추측이 불가능하고, 요청마다 검사해야 하므로 빠른 SHA-256 이 맞다. 느린 해시를 쓰면
 * 모든 요청이 그만큼 느려질 뿐 얻는 것이 없다.
 */
@Entity
@Table(name = "user_token", indexes = {
        @Index(name = "idx_user_token_subject", columnList = "role,subject_id"),
        @Index(name = "idx_user_token_expires", columnList = "expires_at")
})
public class UserToken {

    /** 토큰의 주인이 누구인가 — 필터가 이 값으로 접근 가능한 경로를 가른다. */
    public enum Role { USER, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** {@link Role#USER} 면 {@code app_user.id}, {@link Role#ADMIN} 이면 {@code admin_account.id}. */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /**
     * 발급 시점의 접속 정보.
     *
     * <p>admin 계정은 사람별로 두므로 "누가 승인했는가"는 계정명이 답한다. 이 둘은 그 위에
     * 덧붙이는 자료다 — 같은 계정이 낯선 곳에서 쓰였는지 나중에 되짚을 수 있게 한다.
     */
    @Column(name = "issued_ip", length = 45)
    private String issuedIp;

    @Column(name = "issued_agent", length = 255)
    private String issuedAgent;

    protected UserToken() {}

    public UserToken(String tokenHash, Long subjectId, Role role,
                     LocalDateTime issuedAt, LocalDateTime expiresAt,
                     String issuedIp, String issuedAgent) {
        this.tokenHash = tokenHash;
        this.subjectId = subjectId;
        this.role = role;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.issuedIp = issuedIp;
        this.issuedAgent = issuedAgent;
    }

    public Long getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public Long getSubjectId() { return subjectId; }
    public Role getRole() { return role; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public String getIssuedIp() { return issuedIp; }
    public String getIssuedAgent() { return issuedAgent; }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * 쓰인 시각을 남기고, admin 세션이면 만료를 미룬다(슬라이딩).
     *
     * <p>실사용자 토큰은 미루지 않는다 — 30일 고정이라 미룰 이유가 없고, 매 요청마다 쓰기가
     * 생기면 읽기 전용이어야 할 조회가 전부 쓰기 트랜잭션이 된다.
     */
    public void touch(LocalDateTime now, java.time.Duration slide) {
        this.lastUsedAt = now;
        if (slide != null) {
            this.expiresAt = now.plus(slide);
        }
    }
}
