-- V4: 마이데이터에서 불러온 카드결제에 가맹점 사업자등록번호(10자리)를 보관한다(§13).
-- 사용자는 가맹점명+사업자번호를 받고, 이 번호로 가맹점 주소를 조회한다.
ALTER TABLE user_payment
    ADD COLUMN business_number VARCHAR(10);
