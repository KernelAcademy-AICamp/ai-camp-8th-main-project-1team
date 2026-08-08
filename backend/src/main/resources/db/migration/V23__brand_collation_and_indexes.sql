-- 브랜드 표의 콜레이션을 맞추고, 새 질의가 쓰는 인덱스를 세운다 (2026-08-07)
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ① 콜레이션 — 두 표를 잇는 질의가 아예 거부된다
--
-- V22 에서 `COLLATE` 를 안 적었다. 그러면 서버 기본값(`utf8mb4_0900_ai_ci`)이 붙는데,
-- 이 저장소의 다른 표는 전부 `utf8mb4_unicode_ci` 다.
--
--     user_payment.merchant_name       utf8mb4_unicode_ci
--     merchant_category.merchant_name  utf8mb4_unicode_ci
--     merchant_brand.merchant_name     utf8mb4_0900_ai_ci   ← 혼자 다르다
--
-- 콜레이션이 다른 **두 칸**을 `=` 로 비교하면 MySQL 이 거부한다:
--
--     ERROR 1267: Illegal mix of collations for operation '='
--
-- **깨지는 것은 칸 대 칸 비교다.** 이 파일 아래쪽의 정리 DELETE 가 정확히 그 모양이고
-- (`p.merchant_name = b.merchant_name`), 운영에서 손으로 돌린 같은 모양의 DELETE 가 세 번
-- 조용히 실패했다 — 오류가 배치 출력에 묻혀 있었다. 그래서 **③ 은 이 ALTER 다음이라야 한다.**
--
-- **깨지지 않는 것도 분명히 해 둔다**(2026-08-07 재감사에서 정정). 브랜드 게이트
-- `existsRealPersonPaymentByMerchantName` 는 `user_payment` **한 표**만 보고 칸을 바인드
-- 파라미터와 비교한다. 파라미터는 칸의 콜레이션을 따라가므로(coercibility) 1267 이 날 자리가
-- 없다 — 실제로 로컬 MySQL 9.7 에서 같은 모양을 돌려 정상 반환을 확인했다. 즉 **게이트가
-- 스스로 터져서 더미가 쌓인 것이 아니다.** 4,860줄은 게이트가 들어오기 전(PR #145 이전)에
-- 쌓인 것이고, 콜레이션은 그것을 *치우려는 시도*를 막고 있었다. 원인과 증상을 바꿔 적으면
-- 다음 사람이 엉뚱한 곳을 뒤진다.
--
-- 나머지를 `0900_ai_ci` 로 옮기는 것이 아니라 이쪽을 옮긴다. 표 하나 대 열 몇 개의 차이이고,
-- 기존 표들은 이미 그 콜레이션으로 인덱스가 서 있다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ② 인덱스 — 회차마다 풀스캔이 수백 번 나가고 있었다
--
-- 오늘 늘어난 질의들이 인덱스 없는 칸을 짚는다.
--
--     user_payment(merchant_name)       브랜드 게이트가 가맹점마다 부른다 → 273 × 19,000행
--     merchant_category(merchant_name)  브랜드를 적을 자리를 찾을 때마다
--     user_payment(user_id, category2)  미분류 개수·목록 (동기화 회차마다)
--
-- 기존 인덱스는 `(user_id, payment_date)` 뿐이라 어느 것도 못 탄다. 5분마다 도는 배치에서
-- 이런 스캔이 수백 번 나가면 DB 가 그것만으로 바쁘다.
--
-- `payment_id LIKE '%:real-%'` 는 앞에 와일드카드가 있어 인덱스를 못 탄다. 그래서
-- `merchant_name` 으로 먼저 좁히는 것이 중요하다 — 좁힌 뒤의 몇 행만 LIKE 로 걸러진다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ③ 더미가 만든 행을 걷어낸다
--
-- 게이트(`MerchantBrandService.remember` 의 실사용자 확인)가 들어오기 전에 쌓인 것들이다.
-- 실사용자의 가맹점은 273곳인데 표는 **4,860줄**이다(2026-08-07 운영 실측).
--
-- 남길 기준은 하나다 — **실제 사람의 결제에 그 상호가 있는가.** 코드의 게이트
-- (`MerchantBrandService.remember`)와 같은 조건이라야 하고, 그래서 같은 술어를 쓴다.
-- 실측으로 130줄이 남고 4,730줄이 지워진다.
--
-- 지운 것은 되살릴 필요가 없다. 카탈로그(`brand-forms.json`)가 더미의 브랜드를 이미
-- 알고 있어, 더미 화면에 브랜드가 필요하면 즉석에서 맞춘다.

ALTER TABLE merchant_brand
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE merchant_category
  MODIFY COLUMN brand VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

CREATE INDEX idx_user_payment_merchant ON user_payment (merchant_name);
CREATE INDEX idx_user_payment_user_cat ON user_payment (user_id, category2);
CREATE INDEX idx_merchant_category_name ON merchant_category (merchant_name);

-- **콜레이션을 맞춘 다음이라야 한다.** 순서가 뒤집히면 이 비교가 ERROR 1267 로 거부돼
-- 마이그레이션이 통째로 멈춘다. 위 인덱스 덕에 이 삭제도 풀스캔이 아니다.
DELETE b FROM merchant_brand b
WHERE NOT EXISTS (
    SELECT 1 FROM user_payment p
    WHERE p.merchant_name = b.merchant_name
      AND p.payment_id LIKE '%:real-%');
