-- 가맹점별 낭비 판정 성향 (2026-07-31)
--
-- 온보딩에서 "이건 낭비가 아니다"로 뺀 결제는 그동안 **그 챌린지 한 번**에만 반영됐다.
-- 다음 달이면 같은 가게가 다시 낭비로 뜨고, 사용자는 같은 것을 또 빼야 했다.
--
-- 그렇다고 한 번 뺐다고 그 가맹점을 통째로 제외하면 안 된다 — 같은 가게에서 낭비 목적으로
-- 살 수도 있기 때문이다(사용자 지적 2026-07-31). 그래서 세 단계로 둔다.
--
--   NORMAL   전역 임계 그대로
--   LENIENT  임계를 δ 만큼 올린다 — "확실할 때만 낭비로 본다"
--   EXCLUDED 낭비로 보지 않는다
--
-- 승급은 사용자가 반복해서 "낭비 아님"이라고 했을 때만 일어나고, "낭비 맞음"이면 되돌아간다.
-- 한 번 새어나간 지출이 영영 안 잡히는 일이 없어야 한다.
CREATE TABLE user_merchant_stance (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    -- 1차 지표는 사업자등록번호다(사용자 결정). 브랜드는 원장에 없어 나중에 2차로 붙인다.
    business_number VARCHAR(10)  NOT NULL,
    -- 화면에 "어느 가게였는지" 보여주기 위한 표시용. 판정에는 쓰지 않는다.
    merchant_name   VARCHAR(60)  NULL,
    stance          VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    -- '낭비 아님'을 몇 번 눌렀나. 이 값이 문턱을 넘으면 다음 단계로 간다.
    kept_count      INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 한 사용자에 한 사업자번호는 하나뿐이다.
    CONSTRAINT uq_merchant_stance UNIQUE (user_id, business_number)
);

CREATE INDEX idx_merchant_stance_user ON user_merchant_stance (user_id);
