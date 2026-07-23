-- V2: 낭비/필수 정답 라벨(W8) + 라벨러 잠재점수 + 데이터 분리 파티션(요구11).
ALTER TABLE mydata_payment
    ADD COLUMN mydata_payment_waste_label         VARCHAR(10),
    ADD COLUMN mydata_payment_discretionary_score DOUBLE;

ALTER TABLE mydata_user
    ADD COLUMN mydata_user_data_split VARCHAR(10);
