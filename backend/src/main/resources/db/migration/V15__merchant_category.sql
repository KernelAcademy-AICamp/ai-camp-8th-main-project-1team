-- 확정 분류 사전 + LLM 보조 분류 (2026-08-04)
--
-- 실제 사람의 카드 명세서에는 **업종코드가 없다.** 그래서 그대로 넣으면 전부 '카테고리없음'이
-- 되는데, 그중에는 사람이 보면 명백한 것이 섞여 있다. 두 갈래로 푼다.
--
--   ① merchant_category  — **확실해진 것**을 쌓아 두고 다시 묻지 않는다(아래)
--   ② user_payment.category2_llm — LLM 추정. **판정에는 쓰지 않는다**(맨 아래)
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ① 확정 분류 사전
--
-- 키가 **(사업자번호, 가맹점 풀네임)** 인 것이 이 설계의 전부다. `GS25` 가 아니라
-- `GS25 강남역점` 이 들어간다(사용자 결정 2026-08-04).
--
-- **복합키가 PG 문제를 구조적으로 없앤다.** PG(전자지급결제대행)를 거친 결제는 사업자번호가
-- 결제처를 말해 주지 않는다 — `KG모빌리언스 번호 + 삼성물산리조트(주)에버랜드` 처럼 찍힌다.
-- 번호만 키로 쓰면 그 PG 를 거친 모든 결제가 한 분류로 오염되는데, 풀네임이 함께 키라서
-- 서로 다른 행이 된다. 한 PG 에 업종 하나가 박히는 사고가 구조적으로 안 난다.
--
-- 사람마다 나누지 않는다. `user_merchant_stance`(*"이 가게가 **나에게** 낭비인가"*)와 달리
-- 여기 담기는 것은 *"이 점포의 업종이 무엇인가"* 라 **사람에 따라 달라지지 않는 사실**이다.
-- 그래서 전역이고, 다음 사용자의 명세서에 같은 가맹점이 나오면 LLM 을 다시 부르지 않는다.
-- 만료시키지 않는다 — 이건 캐시가 아니라 자산이다.
--
-- **LLM 추정만으로는 들어오지 않는다.** 출처가 둘뿐인 이유다.
--   USER_CSV        사용자가 업종코드를 직접 준 것(국세청 등록 정보라 추정이 아니다)
--   USER_CONFIRMED  LLM 추정을 사람이 "맞다"고 확인한 것
-- 추정은 ② 의 category2_llm 에 머문다. 그래야 "확실하게 분류된 것"이라는 말이 지켜진다.
CREATE TABLE merchant_category (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    -- 하이픈 없는 10자리. 해외 본사처럼 번호가 없으면 빈 문자열로 둔다 —
    -- NULL 로 두면 UNIQUE 가 NULL 을 서로 다르게 봐서 같은 가맹점이 여러 번 쌓인다.
    business_number VARCHAR(10)  NOT NULL DEFAULT '',
    -- **풀네임**이다. 'GS25' 가 아니라 'GS25 강남역점'.
    merchant_name   VARCHAR(120) NOT NULL,
    category2       VARCHAR(30)  NOT NULL,
    source          VARCHAR(20)  NOT NULL,
    -- 오입력을 되돌릴 수 있어야 한다. 누가 언제 확정했는지 남긴다(뒤집을 때 근거가 된다).
    confirmed_by    BIGINT       NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_merchant_category UNIQUE (business_number, merchant_name)
);

-- 정확 일치가 없을 때 **같은 사업자번호의 다른 행**을 찾는 조회용.
--
-- 왜 이 완화가 필요한가. 한 사업자번호에 상호가 38,690종 붙은 것이 있다 — 택시다.
-- 표시명 뒤에 차량번호가 붙어(`카카오T경기33아6084`) 결제마다 풀네임이 다르다. 복합키만 쓰면
-- 이런 가맹점은 사전이 영영 재사용되지 않고 행만 쌓인다.
--
-- **단, PG 목록에 있는 번호에는 이 완화를 적용하지 않는다.** PG 는 한 번호에 업종이 제각각인
-- 가맹점이 붙어 정반대다. PG 가 아닌 번호는 한 사업자의 것이라 업종이 하나이므로 안전하다.
-- 저장은 복합키 그대로 두고 **읽을 때만** 완화한다 — 잘못 쌓일 위험을 만들지 않는다.
CREATE INDEX idx_merchant_category_biz ON merchant_category (business_number);

-- ─────────────────────────────────────────────────────────────────────────────
-- ② LLM 보조 분류 — 표시만 하고 판정에는 쓰지 않는다
--
-- **`category2` 를 덮지 않는다.** WasteScoringService 가 그 필드를 직접 읽어 낭비를 판정하므로,
-- 덮는 순간 "판단은 설명가능한 모델이" 라는 원칙(마스터 §4-1)이 깨진다. 그래서 칸을 따로 둔다.
--
--   category2_source  NONE      아직 아무것도 안 붙음
--                     LLM       AI 추정 — 화면에 "AI 추정" 배지로만 보인다
--                     USER      사람이 확인함 → 이때 merchant_category 로 승격된다
--                     DICT      사전에서 붙음 → 처음부터 확정이라 판정에 바로 참여한다
ALTER TABLE user_payment ADD COLUMN category2_llm VARCHAR(30) NULL;
ALTER TABLE user_payment ADD COLUMN category2_source VARCHAR(10) NOT NULL DEFAULT 'NONE';
