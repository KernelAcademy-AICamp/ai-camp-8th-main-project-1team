package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * admin 계정 — 실 개인정보 적재를 승인하는 열쇠라 시스템에서 가장 값진 표적이다.
 *
 * <p><b>사람별로 만든다. 공용 계정을 두지 않는다.</b> 공용이면 셋이 동시에 깨진다:
 * ① 첫 로그인 비밀번호 변경이 나머지 사람을 잠그고, ② TOTP 가 한 사람의 폰에만 등록되어
 * 그 사람이 없으면 아무도 승인을 못 하며, ③ 같은 사무실에서 접속하면 IP 가 같아
 * 누가 승인했는지 구분되지 않는다. 승인 기록에는 계정명이 <b>사실로</b> 남아야 한다.
 *
 * <p>비밀번호는 <b>단방향</b>(Argon2id), TOTP 비밀은 <b>양방향</b>(KMS envelope)이다 —
 * 비밀번호는 되돌릴 필요가 없지만 TOTP 는 검증에 원문이 필요하기 때문이다.
 */
@Entity
@Table(name = "admin_account")
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    /**
     * Argon2id 해시. OWASP 권장 파라미터(m=19456 KiB, t=2, p=1)를 쓴다.
     *
     * <p>SHA 계열이 아닌 이유: 너무 빠르다. GPU 가 초당 수십억 번 후보를 대입한다.
     * Argon2id 는 <b>메모리를 강제로 쓰게 해</b> 병렬화를 막는다.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 최초 발급 비밀번호는 첫 로그인에서 반드시 바꾼다. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;

    /** TOTP 비밀(Base32)의 암호문. 평문으로 두면 2FA 가 무력화된다. */
    @Column(name = "totp_secret_enc", length = 512)
    private byte[] totpSecretEnc;

    /** 등록을 마쳤는가. 마친 뒤에는 QR 을 다시 보여주지 않는다. */
    @Column(name = "totp_confirmed", nullable = false)
    private boolean totpConfirmed = false;

    /**
     * 마지막으로 성공한 30초 구간 번호.
     *
     * <p><b>같은 코드로 두 번 로그인하는 것을 막는다.</b> 이것이 없으면 어깨너머로 본 코드를
     * 30초 안에 그대로 재사용할 수 있다.
     */
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(nullable = false)
    private boolean enabled = true;

    protected AdminAccount() {}

    public AdminAccount(String username, String passwordHash, LocalDateTime createdAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public byte[] getTotpSecretEnc() { return totpSecretEnc; }
    public boolean isTotpConfirmed() { return totpConfirmed; }
    public Long getTotpLastStep() { return totpLastStep; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public boolean isEnabled() { return enabled; }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
    }

    /**
     * 계정을 <b>방금 만든 상태로 되돌린다</b> — 잠겼을 때 빠져나오는 유일한 길.
     *
     * <p><b>TOTP 도 함께 지운다.</b> 안 지우면 폰을 잃은 사람은 비밀번호를 새로 받아도
     * 여전히 못 들어온다 — 되돌리는 의미가 없다. 다음 로그인에서 비밀번호를 바꾸고
     * 2단계 인증을 다시 등록하게 된다.
     */
    public void reset(String temporaryHash) {
        this.passwordHash = temporaryHash;
        this.mustChangePassword = true;
        this.totpSecretEnc = null;
        this.totpConfirmed = false;
        this.totpLastStep = null;
    }

    /** 등록 단계 — 아직 확정 전이라 다시 발급할 수 있다. */
    public void prepareTotp(byte[] secretEnc) {
        this.totpSecretEnc = secretEnc;
        this.totpConfirmed = false;
        this.totpLastStep = null;
    }

    public void confirmTotp() {
        this.totpConfirmed = true;
    }

    public void markTotpStep(long step) {
        this.totpLastStep = step;
    }

    public void markLogin(LocalDateTime at) {
        this.lastLoginAt = at;
    }
}
