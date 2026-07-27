-- V8: 고유 가맹점(mydata_merchant) — 사업자등록번호를 키로 가맹점명·지번주소·좌표.
-- 결제(mydata_payment)에서 business_number DISTINCT로 집계해 생성 후 1회 채운다(GenerationRunner).
-- 사용자는 결제에 실린 사업자번호로 이 테이블을 조회해 가맹점 주소를 얻는다(번호→주소 조회).
CREATE TABLE mydata_merchant (
    business_number VARCHAR(10)  NOT NULL,
    merchant_name   VARCHAR(80),
    address         VARCHAR(160),
    lat             DOUBLE,
    lng             DOUBLE,
    online          BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (business_number)
);
