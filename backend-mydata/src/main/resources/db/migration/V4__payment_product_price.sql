-- V4: 상품(품목명·단가·수량) — 상품 카탈로그(W1). amount=총액≈단가×수량+노이즈.
ALTER TABLE mydata_payment
    ADD COLUMN mydata_payment_product_name  VARCHAR(60),
    ADD COLUMN mydata_payment_product_price INT,
    ADD COLUMN mydata_payment_quantity      INT;
