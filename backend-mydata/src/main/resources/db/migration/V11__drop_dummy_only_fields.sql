-- 실 마이데이터에 없는 두 칸을 없앤다 — 더미는 제약을 완화하는 게 아니라 재현해야 한다.
--
-- 1) mydata_card_prev_month_amount (전월 실적액)
--    카드 업권 API 어디에도 전월 실적액 필드가 없다. 이 값을 제공자가 내주면 앱은 실적을
--    승인내역에서 계산할 이유가 없어지고, 그 계산이 없는 채로 실데이터를 맞는다.
--    게다가 생성기는 이 값을 `rnd.nextInt(650000)` 으로 만들었다 — 그 사람의 소비와
--    아무 관계가 없는 난수인데, 화면의 실적 진행바가 그 위에 얹혀 있었다.
--
-- 2) mydata_payment_received_benefit_amount (받은 혜택 금액)
--    할인·적립액 필드도 없다(카드-005 청구 추가정보·카드-008 승인내역 모두). 이 값은
--    위의 난수 실적구간으로 계산돼 결제마다 "−1,200원"으로 화면에 나갔다. 실데이터에는
--    그 숫자를 만들 근거가 없다.
--
-- 두 칸은 함께 지운다. 혜택 계산이 실적구간 대조를 입구로 쓰기 때문에 하나만 지우면
-- 나머지가 공중에 뜬다.
--
-- 대량 생성 경로는 혜택을 이미 0으로만 넣고 있었다(GenerationRunner). 즉 실제로 값이
-- 들어 있던 것은 12명짜리 시드뿐이다.

ALTER TABLE mydata_card    DROP COLUMN mydata_card_prev_month_amount;
ALTER TABLE mydata_payment DROP COLUMN mydata_payment_received_benefit_amount;
