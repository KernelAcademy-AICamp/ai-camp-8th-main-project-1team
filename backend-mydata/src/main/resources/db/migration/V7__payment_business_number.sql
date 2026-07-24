-- V7: 가맹점 사업자등록번호(10자리) — 가맹점 신원(이름+동, 온라인은 이름)에서 결정론 파생.
-- 사용자에게는 가맹점명+사업자번호를 전달하고, 사용자는 이 번호로 가맹점 주소를 조회한다(mydata_merchant).
ALTER TABLE mydata_payment
    ADD COLUMN mydata_payment_business_number VARCHAR(10);
