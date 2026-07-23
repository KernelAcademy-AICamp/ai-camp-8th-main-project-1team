-- 저축 목표 '자유입출금통장'(§13-11) — 목표에 모으는 돈을 담는 계좌(은행·통장명·계좌번호). 기존 목표는 NULL 허용.
ALTER TABLE `savings_goal`
    ADD COLUMN `account_bank`    VARCHAR(40) COLLATE utf8mb4_unicode_ci NULL,
    ADD COLUMN `account_product` VARCHAR(60) COLLATE utf8mb4_unicode_ci NULL,
    ADD COLUMN `account_number`  VARCHAR(32) COLLATE utf8mb4_unicode_ci NULL;
