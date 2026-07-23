-- V1 baseline: 마이데이터 제공자 서버 기존 스키마(대량 생성 확장 이전 상태).
-- 운영 MySQL(Phase 6)에서 Flyway가 이 그릇을 만든다. dev(h2)는 ddl-auto:update가 담당(이 파일 미실행).
-- 컬럼명은 JPA @Column 과 1:1 일치.

CREATE TABLE card_company (
    card_company_id      BIGINT       NOT NULL AUTO_INCREMENT,
    card_company_name    VARCHAR(40)  NOT NULL,
    card_company_img_url VARCHAR(255),
    PRIMARY KEY (card_company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE card (
    card_code       BIGINT       NOT NULL AUTO_INCREMENT,
    card_name       VARCHAR(60)  NOT NULL,
    card_img_url    VARCHAR(255),
    card_color      VARCHAR(20),
    card_company_id BIGINT       NOT NULL,
    PRIMARY KEY (card_code),
    CONSTRAINT fk_card_company FOREIGN KEY (card_company_id) REFERENCES card_company (card_company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE card_benefit (
    card_benefit_id               BIGINT      NOT NULL AUTO_INCREMENT,
    card_code                     BIGINT      NOT NULL,
    category1_name                VARCHAR(30) NOT NULL,
    card_benefit_discount_percent INT         NOT NULL,
    card_benefit_performance_start INT        NOT NULL,
    card_benefit_performance_end  INT         NOT NULL,
    card_benefit_limit            INT         NOT NULL,
    PRIMARY KEY (card_benefit_id),
    CONSTRAINT fk_benefit_card FOREIGN KEY (card_code) REFERENCES card (card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mydata_user (
    mydata_user_id            VARCHAR(64) NOT NULL,
    mydata_user_name          VARCHAR(40) NOT NULL,
    mydata_user_social_number VARCHAR(20) NOT NULL,
    mydata_user_phone_number  VARCHAR(20) NOT NULL,
    mydata_user_persona       VARCHAR(40),
    PRIMARY KEY (mydata_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mydata_card (
    mydata_card_id               VARCHAR(24) NOT NULL,
    mydata_user_id               VARCHAR(64) NOT NULL,
    card_code                    BIGINT      NOT NULL,
    mydata_card_expiration_date  DATE        NOT NULL,
    mydata_card_prev_month_amount INT        NOT NULL,
    PRIMARY KEY (mydata_card_id),
    CONSTRAINT fk_mydata_card_user FOREIGN KEY (mydata_user_id) REFERENCES mydata_user (mydata_user_id),
    CONSTRAINT fk_mydata_card_product FOREIGN KEY (card_code) REFERENCES card (card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mydata_payment (
    mydata_payment_id                    VARCHAR(40) NOT NULL,
    mydata_card_id                       VARCHAR(24) NOT NULL,
    mydata_payment_date                  DATETIME(6) NOT NULL,
    mydata_payment_category1             VARCHAR(30) NOT NULL,
    mydata_payment_category2             VARCHAR(30),
    mydata_payment_amount                INT         NOT NULL,
    mydata_payment_merchant_name         VARCHAR(60),
    mydata_payment_received_benefit_amount INT       NOT NULL,
    PRIMARY KEY (mydata_payment_id),
    CONSTRAINT fk_mydata_payment_card FOREIGN KEY (mydata_card_id) REFERENCES mydata_card (mydata_card_id),
    INDEX idx_mydata_payment_card_date (mydata_card_id, mydata_payment_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
