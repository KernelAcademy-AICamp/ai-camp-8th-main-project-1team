-- V2: 연동 카드사·마지막 동기화 시각(§13-11 실시간 증분, W2). UserCardCompany 엔티티와 1:1.
CREATE TABLE user_card_company (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    company_id        BIGINT       NOT NULL,
    company_name      VARCHAR(40),
    linked_at         DATETIME(6)  NOT NULL,
    last_renewal_time DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_user_company UNIQUE (user_id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
