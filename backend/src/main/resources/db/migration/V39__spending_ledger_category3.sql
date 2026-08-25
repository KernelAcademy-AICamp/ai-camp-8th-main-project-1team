-- 소비 원장과 사전에 **소분류** 칸을 넣는다.
--
-- 중분류보다 작고 브랜드보다 큰 칸이다 — 카카오T 는 브랜드, 교통/자동차 는 중분류인데
-- 그 사이의 '택시' 를 적을 자리가 없었다. 배달의민족과 식비 사이의 '배달' 도 같다.
--
-- **소분류는 정확히 한 중분류에만 속한다**(industry-mid.json 의 midBySub). 그래서 소분류를
-- 알면 중분류가 결정되고, 같은 브랜드가 통로(업종코드·등록조회·LLM)에 따라 갈리지 않는다.
-- 거꾸로 category2 가 midOfSub(category3) 와 다르면 그 자체가 오분류의 증거다.
--
-- 사전에도 같은 칸을 두는 이유는 **원장 재작성을 싸게 하기 위해서**다. 사전이 답을 들고
-- 있으면 원장은 옮겨 적기만 하면 된다.

ALTER TABLE spending_ledger
    ADD COLUMN category3 VARCHAR(30) NULL COMMENT '소비 소분류(중분류보다 작고 브랜드보다 큰 칸)',
    ADD COLUMN category3_source VARCHAR(12) NOT NULL DEFAULT 'NONE' COMMENT '소분류 출처 — NONE/NAME/BRAND';

ALTER TABLE merchant_category
    ADD COLUMN category3 VARCHAR(30) NULL COMMENT '소비 소분류. category2 와 어긋나면 오분류다';

-- 소분류로 묶어 보는 조회를 위한 색인. 원장은 사용자·월로 먼저 좁히므로 그 뒤에 붙인다.
CREATE INDEX idx_spending_ledger_category3 ON spending_ledger (user_id, month_key, category3);
