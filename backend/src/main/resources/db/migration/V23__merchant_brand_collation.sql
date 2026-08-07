-- merchant_brand 의 콜레이션을 나머지 표와 맞춘다 (2026-08-07)
--
-- V22 에서 `COLLATE` 를 안 적었다. 그러면 MySQL 8.4 의 기본값(`utf8mb4_0900_ai_ci`)이 붙는데,
-- 이 저장소의 다른 표는 전부 `utf8mb4_unicode_ci` 다.
--
--     user_payment.merchant_name       utf8mb4_unicode_ci
--     merchant_category.merchant_name  utf8mb4_unicode_ci
--     merchant_brand.merchant_name     utf8mb4_0900_ai_ci   ← 혼자 다르다
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 왜 이것이 버그인가
--
-- 콜레이션이 다른 두 칸을 `=` 로 비교하면 MySQL 이 **거부한다.**
--
--     ERROR 1267: Illegal mix of collations for operation '='
--
-- 그래서 `merchant_brand` 를 `user_payment` 와 조인하는 질의가 통째로 실패한다. 하필 브랜드
-- 게이트(`existsRealPersonPaymentByMerchantName`)가 그 조인을 쓰므로, 고치지 않으면 **더미를
-- 막으려고 넣은 방벽이 예외를 던진다.**
--
-- 운영 정리 작업에서 발견했다(2026-08-07). 더미 유래 행을 지우려는 DELETE 가 조용히 실패하고
-- 있었고, 원인을 찾다 콜레이션이 드러났다. 실패가 조용했던 것은 그 질의가 배치 안에 있어
-- 오류 출력이 묻혔기 때문이다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 왜 utf8mb4_unicode_ci 로 맞추나
--
-- 나머지를 `0900_ai_ci` 로 옮기는 것이 아니라 이쪽을 옮긴다. 표 하나를 바꾸는 것과 열 몇 개를
-- 바꾸는 것의 차이이고, 기존 표들은 이미 그 콜레이션으로 인덱스가 서 있다.

ALTER TABLE merchant_brand
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE merchant_category
  MODIFY COLUMN brand VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;
