-- 입출금 통장(§13-11 경제 모델) — 사용자당 1개. 카드=출금, 월급날=입금, 잔액은 조회 시 계산.
CREATE TABLE mydata_account (
    mydata_account_id              VARCHAR(32) NOT NULL,   -- 계좌번호(은행별 형식)
    mydata_user_id                 VARCHAR(64) NOT NULL,
    mydata_account_bank            VARCHAR(40) NOT NULL,
    mydata_account_product         VARCHAR(60) NOT NULL,
    mydata_account_salary_payer    VARCHAR(40) NOT NULL,   -- 월급 입금처(회사명)
    mydata_account_opened_date     DATE        NOT NULL,
    mydata_account_salary          INT         NOT NULL,   -- 월급(원, 10만원 단위)
    mydata_account_payday          INT         NOT NULL,   -- 월급날(1~28)
    mydata_account_initial_balance BIGINT      NOT NULL,
    PRIMARY KEY (mydata_account_id),
    CONSTRAINT uq_mydata_account_user UNIQUE (mydata_user_id),
    CONSTRAINT fk_mydata_account_user FOREIGN KEY (mydata_user_id) REFERENCES mydata_user (mydata_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
