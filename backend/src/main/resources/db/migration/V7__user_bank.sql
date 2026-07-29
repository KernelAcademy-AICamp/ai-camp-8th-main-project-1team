-- 은행 연동 기록 (§13 자산연결).
-- 카드사와 달리 last_renewal_time이 없다 — 통장의 잔액·내역은 저장하지 않고 조회 시 계산하므로
-- 증분으로 당겨올 것이 없다. 여기에는 '무엇을 연동했는가'만 남는다.
CREATE TABLE user_bank (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    user_id   BIGINT       NOT NULL,
    bank_id   BIGINT       NOT NULL,
    bank_name VARCHAR(40)  NULL,
    linked_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_user_bank UNIQUE (user_id, bank_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_user_bank_user ON user_bank (user_id);
