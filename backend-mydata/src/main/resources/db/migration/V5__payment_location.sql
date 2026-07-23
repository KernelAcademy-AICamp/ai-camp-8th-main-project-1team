-- V5: 위치(주소·위경도) — 하루활동·동선 앵커(요구9). 온라인 결제는 null.
-- 실사용자 위치 수집 시 개인정보 방침 갱신 트리거(합성 페르소나는 더미 좌표).
ALTER TABLE mydata_payment
    ADD COLUMN mydata_payment_location_address VARCHAR(120),
    ADD COLUMN mydata_payment_location_lat     DOUBLE,
    ADD COLUMN mydata_payment_location_lng     DOUBLE;
