-- V3: 결제 채널(ONLINE/OFFLINE) — 온라인 결제 반영(개정4·W1-1a).
ALTER TABLE mydata_payment
    ADD COLUMN mydata_payment_channel VARCHAR(10);
