-- 업종코드(KSIC 세분류 4자리) 축 도입 — 소비 카테고리와 가맹점 업종을 갈라 놓는다.
--
-- 예전에는 category1(7대분류) 한 축이 "가맹점 업종"과 "사용자 소비 종류"를 겸했다.
-- 그래서 지하철이 '온라인' 대분류에 들어갔고, 분석 엔진이 대분류로 돌아 배달을 줄이려면
-- 지하철 요금까지 챌린지 한도에 잡혔다.
--
-- 이제 마이데이터는 **업종코드만** 넘기고, 소비 카테고리는 앱이 결정론 1:1 표로 붙인다
-- (scripts/ksic/ksic-mapping.tsv). 판정의 정답은 제공자 DB에만 남아 학습에 쓰인다 —
-- 낭비 라벨과 같은 방식이다.

-- ── 결제: category1 → ksic_code ──────────────────────────────────────────────
-- 이름을 바꾸는 이유: 값의 의미가 완전히 달라졌다(대분류명 '식비' → 코드 '5611').
-- 컬럼명을 그대로 두면 다음 사람이 옛 의미로 읽는다.
ALTER TABLE mydata_payment
    CHANGE COLUMN mydata_payment_category1 mydata_payment_ksic_code VARCHAR(8) NOT NULL;

-- category2는 남긴다 — 소비맥락(한식·카페 등)이라 생성 품질 추적에 쓰이고,
-- 낭비 라벨의 근거이기도 하다. 다만 API로는 나가지 않는다.

-- ── 가맹점: 업종코드 부여 ────────────────────────────────────────────────────
-- 앱이 사업자번호로 가맹점을 조회할 때 업종을 함께 받아야, 결제 없이도 분류할 수 있다.
ALTER TABLE mydata_merchant
    ADD COLUMN ksic_code VARCHAR(8) NULL;

-- ── 카드 혜택: 대분류 → 우리 중분류 ──────────────────────────────────────────
-- 혜택이 걸려 있던 category1(7대분류)을 없앴으므로 기준을 옮긴다.
-- 업종코드(502개)로 걸면 카드사가 502줄을 정의해야 해서 현실과 멀다 —
-- 실제 카드 혜택도 '온라인 5%'처럼 소비자가 아는 묶음 단위로 준다.
ALTER TABLE card_benefit
    CHANGE COLUMN category1_name mid_category VARCHAR(30) NOT NULL;

-- 조회 경로: 결제 → 업종코드로 가맹점·중분류를 찾는다.
CREATE INDEX idx_mydata_payment_ksic ON mydata_payment (mydata_payment_ksic_code);
