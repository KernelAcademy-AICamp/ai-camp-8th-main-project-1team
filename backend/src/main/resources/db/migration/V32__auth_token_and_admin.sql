-- 인증 도입 (설계서 Phase 1) — 이 앱에는 지금까지 인증이 없었다.
--
-- 서버는 `?userId=7`을 그대로 믿었고, 운영에 실제 사람 750건이 그 상태로 올라가 있다
-- (`GET /api/report/monthly?userId=999999` 가 401 이 아니라 404 를 냈다 — 인증을 안 따진다는 뜻).
-- 실사용자 데이터를 더 받기 전에 이것부터 막는다.
--
-- **토큰을 그대로 저장하지 않는다.** 저장하는 것은 SHA-256 해시뿐이라, 이 표가 통째로
-- 유출돼도 그 값으로는 로그인할 수 없다. 비밀번호를 해시로 저장하는 것과 같은 이유이고,
-- 다만 토큰은 32바이트 무작위라 추측이 불가능하므로 느린 KDF(Argon2)가 필요 없다 —
-- 요청마다 검사해야 하니 빠른 SHA-256 이 맞다.

CREATE TABLE user_token (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    -- SHA-256(토큰) 64 hex. **원문 토큰은 어디에도 저장하지 않는다.**
    token_hash    CHAR(64)     NOT NULL,
    -- 실사용자면 app_user.id, admin 이면 admin_account.id. 역할로 어느 표를 볼지 가른다.
    subject_id    BIGINT       NOT NULL,
    -- USER | ADMIN. 필터가 이 값으로 접근 가능한 경로를 가른다.
    role          VARCHAR(10)  NOT NULL,
    issued_at     DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    -- 마지막으로 쓰인 시각. admin 세션은 활동이 있으면 만료를 미룬다(슬라이딩).
    last_used_at  DATETIME(6)  NULL,
    -- 발급 시점의 접속 정보. 승인 기록과 대조할 감사 자료다(설계 B1).
    issued_ip     VARCHAR(45)  NULL,
    issued_agent  VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_token_hash (token_hash),
    KEY idx_user_token_subject (role, subject_id),
    KEY idx_user_token_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- admin 계정.
--
-- **사람별로 만든다**(공용 계정 금지). 공용이면 ① 첫 로그인 비밀번호 변경이 나머지를 잠그고
-- ② TOTP 가 한 사람의 폰에만 등록되어 그 사람이 없으면 아무도 승인을 못 하며
-- ③ 같은 사무실에서 접속하면 IP 가 같아 누가 승인했는지 구분되지 않는다.
--
-- 승인 기록에는 계정명이 **사실로** 남고, IP·User-Agent 는 그 위에 덧붙인다.
CREATE TABLE admin_account (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    username            VARCHAR(40)  NOT NULL,
    -- Argon2id (OWASP 권장 m=19456 KiB, t=2, p=1). 되돌릴 필요가 없으므로 단방향이다.
    -- 메모리를 강제로 쓰게 해 GPU 병렬화를 막는 것이 SHA 계열과의 결정적 차이다.
    password_hash       VARCHAR(255) NOT NULL,
    -- 최초 발급 비밀번호는 첫 로그인에서 반드시 바꾼다.
    must_change_password TINYINT(1)  NOT NULL DEFAULT 1,
    -- TOTP 비밀(Base32 원문을 KMS envelope 로 감싼 암호문).
    -- **검증에 원문이 필요하므로 해시가 아니라 양방향이다.** 이 값이 새면 2FA 가 무력화되므로
    -- 비밀번호 해시와 같은 무게로 다룬다.
    totp_secret_enc     VARBINARY(512) NULL,
    -- 등록을 마쳤는가. 등록 전에는 QR 을 다시 보여줄 수 있고, 마친 뒤에는 보여주지 않는다.
    totp_confirmed      TINYINT(1)   NOT NULL DEFAULT 0,
    -- 마지막으로 성공한 30초 구간 번호. **같은 코드로 두 번 로그인하는 것을 막는다** —
    -- 없으면 어깨너머로 본 코드를 30초 안에 재사용할 수 있다.
    totp_last_step      BIGINT       NULL,
    created_at          DATETIME(6)  NOT NULL,
    last_login_at       DATETIME(6)  NULL,
    enabled             TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 폰을 잃어버리면 TOTP 를 영영 못 쓴다. 일회용 백업 코드로 빠져나온다.
-- 코드 원문은 발급 시 한 번만 보여주고 **해시만** 저장한다(비밀번호와 같은 방식).
CREATE TABLE admin_recovery_code (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    admin_id    BIGINT      NOT NULL,
    code_hash   CHAR(64)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recovery_admin (admin_id),
    UNIQUE KEY uk_recovery_hash (code_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
