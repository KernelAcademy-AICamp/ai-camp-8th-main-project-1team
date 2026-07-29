-- 업종코드 축 도입 — 제공자는 업종까지만 주고, 소비 카테고리는 앱이 붙인다.
--
-- 예전에는 제공자의 category1(7대분류)이 그대로 `category` 테이블의 코드가 됐다.
-- 한 축이 "가맹점 업종"과 "사용자 소비 종류"를 겸하다 보니 교통이 '온라인'에 들어갔고,
-- 분석 엔진이 대분류로 돌아 배달을 줄이려면 지하철 요금까지 챌린지 한도에 잡혔다.
--
-- 이제 `ksic_code`는 분류의 **원본 근거**로 보관하고, `category2`에 우리 소비 중분류를 담는다
-- (IndustryCategoryMapper가 결정론 1:1 표로 옮긴다).

ALTER TABLE user_payment
    CHANGE COLUMN category1 ksic_code VARCHAR(8) NOT NULL;

-- 업종코드로 가맹점·중분류를 되짚는 경로.
CREATE INDEX idx_user_payment_ksic ON user_payment (ksic_code);

-- `category` 테이블은 손대지 않는다.
-- consumption.category_id가 FK로 물려 있어 구 7행을 지울 수 없고, 지울 필요도 없다 —
-- 재연동하면 MYDATA 소스 소비가 새 중분류 행을 가리키게 다시 적재된다.
-- 구 행은 USER_INPUT 등 다른 출처가 참조할 수 있으므로 남겨 둔다.
