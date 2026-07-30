-- 통장 거래 원장(§13-11) — 이체·월급·이자·세금·카드출금을 실제 행으로 적재한다.
--
-- 왜 저장하는가. 예전에는 통장을 열 때마다 개설일부터 지금까지의 이체를 다시 계산했다.
-- 그런데 그 계산은 "지금 이후는 건너뛴다"로 잘렸고, 건너뛴 거래가 난수 열을 소비하지 않아
-- **조회 시점에 따라 지난달 입금 총액이 달라졌다.** 어제 본 통장과 오늘 본 통장의 지난주가
-- 다르면 그건 통장이 아니다. 게다가 사용자마다 매 조회에 9개월치 월 루프가 돌았다.
--
-- balance_after는 두지 않는다. 결제가 하나 들어오면 그 뒤 모든 행의 잔액이 낡기 때문이다.
-- 잔액은 조회에서 시간순으로 굴린다(구간 시작 잔액 + 누적).
CREATE TABLE mydata_account_txn (
    mydata_account_txn_id   BIGINT       NOT NULL AUTO_INCREMENT,
    -- 계좌번호. 사용자당 통장 1개라 사용자 조회는 mydata_account를 거친다.
    mydata_account_id       VARCHAR(32)  NOT NULL,
    mydata_account_txn_date DATETIME     NOT NULL,
    -- DEPOSIT | WITHDRAWAL. amount는 부호 없는 절대액이다(실제 통장의 입금/출금 두 칸).
    mydata_account_txn_type VARCHAR(12)  NOT NULL,
    mydata_account_txn_amount BIGINT     NOT NULL,
    -- 적요: 거래 상대나 성격(뚜레쥬르 병영1동점 · 이자입금 · 김민준)
    mydata_account_txn_description VARCHAR(120) NOT NULL,
    -- 비고: 취급점·채널(KB국민카드 · BNK경남은행본부 · 전자금융이체)
    mydata_account_txn_note VARCHAR(60)  NOT NULL,
    -- 어디서 온 거래인가: TRANSFER(사람 간 이체) · SALARY · INTEREST · TAX · CARD(결제 복제).
    -- 카드 출금은 mydata_payment의 사본이라, 갈라졌는지 대조하려면 출처를 알아야 한다.
    mydata_account_txn_source VARCHAR(12) NOT NULL,
    -- 이 거래가 복제한 결제(source='CARD'일 때만). 나머지는 NULL.
    --
    -- 사본에는 원본을 가리키는 것이 있어야 한다. 생성 후 정리 단계가 해시충돌 사업자번호의 결제를
    -- 지우는데, 이 열이 없으면 통장에 그 결제의 출금만 남아 **결제와 통장이 갈라진다**.
    -- 날짜·금액·가맹점명으로 되짚는 방법은 같은 날 같은 가게에서 같은 금액을 두 번 쓰면 무너진다.
    mydata_account_txn_payment_id VARCHAR(64) NULL,
    PRIMARY KEY (mydata_account_txn_id),
    CONSTRAINT fk_account_txn_account FOREIGN KEY (mydata_account_id)
        REFERENCES mydata_account (mydata_account_id)
) ENGINE=InnoDB;

-- 조회는 언제나 "이 계좌의 [from, now] 구간"이다. 잔액을 굴려야 해서 날짜순으로 읽는다.
CREATE INDEX idx_account_txn_account_date
    ON mydata_account_txn (mydata_account_id, mydata_account_txn_date);

-- 정리 단계가 "지워진 결제의 통장 사본"을 찾을 때 쓴다.
CREATE INDEX idx_account_txn_payment ON mydata_account_txn (mydata_account_txn_payment_id);
