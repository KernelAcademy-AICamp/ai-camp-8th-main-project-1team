-- 카드 상품 표 — 추천 엔진이 읽을 그릇 (2026-08-11)
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 무엇이 없어서 만드나
--
-- 카드 추천(FP-03)은 **로직 설계가 끝났고 공시 원문도 확보했는데 넣을 자리가 없었다.**
--
--     backend-mydata (제공자·8082)   card_product·card_benefit 있음   ← 더미 생성용
--     backend        (추천 엔진·8080) 카드 표 없음
--                                     CardRecommendService 가 application.yml 의 [더미] 3장을 읽는다
--
-- 그릇이 없으니 추출을 자동화할 이유도 안 생긴다. 표가 첫 단추다.
--
-- 재료는 `scripts/collect-cards/schema-draft.json` — BC 3장(ZONE·페이북·KaPick)을 원문
-- 대조로 채운 것이고 미확인 항목이 0 이다. 셋이 서로 충분히 달라서 스키마가 흔들릴 축은
-- 이미 다 드러났다.
--
--     ZONE     할인 · 2구간 · 통합한도 없음 · 원 단위             · 실적제외 10
--     페이북    적립 · 2구간 · 통합한도 있음 · 페이북머니          · 실적제외  9
--     KaPick   적립 · 4구간 · 그룹별 다른 한도 · 카카오페이포인트  · 실적제외 12
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ★ 번호가 V29 도 V34 도 아니라 V36 인 이유 — 두 번 밀렸다
--
-- 이 작업의 핸드오프 문서는 V29 로 적혀 있었는데 그사이 develop 이 움직였다.
-- 2026-08-11 확인:
--
--     V28__guardian_tx_source_index.sql     develop (#161)
--     V29__merchant_category_nts_codes.sql  develop (#161)
--     V30__merchant_category_vote.sql       develop (#161)
--     V31__drop_received_benefit.sql        이 브랜치
--
-- 그래서 V34 로 냈는데, 2026-08-18 develop 을 다시 병합하니 또 겹쳤다:
--
--     V34__spending_ledger.sql   develop (#199~#203)   ← 우리 V34 와 충돌
--     V35__usage_event.sql       develop               ← 우리 V35 와 충돌
--
-- **develop 쪽이 이미 배포됐을 수 있으므로 이 브랜치가 비킨다**(V34→V36 · V35→V37).
-- 규칙 3 이 지키는 것은 *이미 적용된* 파일이고, 이 둘은 아직 병합 전이라 옮겨도 된다.
-- 번호를 옮긴 뒤에는 develop 을 먼저 병합하고 배포한다 — 낮은 번호가 나중에 오면
-- Flyway 가 또 막는다.
--
-- 겹친 채로 냈으면 `Found more than one migration with version 34` 로 운영 기동이 죽는다.
-- 시험은 H2 + ddl-auto:create-drop 이라 Flyway 를 안 타서 못 잡고, git 도 파일 이름이
-- 달라 충돌로 안 본다 — `MigrationVersionTest` 하나가 잡는다(실제로 이번에 그것이 잡았다).
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ★ 표가 다섯이 아니라 아홉이다
--
-- 지시서는 다섯(실제로는 여섯)을 적었는데 아홉으로 낸다. 늘어난 셋은 전부 **길이가 카드마다
-- 다른 것**이고, 지시서가 스스로 "고정 배열로 두면 안 된다"고 적어 둔 바로 그 자리다.
--
--     card_performance_tier   실적 구간   ZONE 2단 · 페이북 2단 · KaPick 4단
--     card_benefit_cap        구간별 월한도   KaPick 은 묶음마다 한도가 다르다
--     card_combined_cap       통합한도       페이북: 개별 합 15,000 > 통합 13,000
--
-- JSON 칼럼으로 접을 수도 있었지만 이 저장소에는 JSON 칼럼이 한 곳도 없고, 무엇보다
-- **한도를 구간 행에 FK 로 매달면 게이트 3 의 검산 하나가 공짜로 된다** —
-- "monthly_cap_by_tier 키 != tiers → 오류"는 없는 구간을 가리키는 한도를 DB 가 거부하므로
-- 애초에 못 들어온다.
--
-- 반대로 하나는 **합쳤다.** 지시서가 "따로 둘지 결정할 것"으로 남긴 혜택 제외다.
--
--     card_exclusion (axis = PERFORMANCE | BENEFIT)
--
-- 두 목록은 모양이 같고(code·label) 어휘도 겹친다(CASH_ADVANCE·CARD_LOAN·FEE·GIFT_CARD·
-- INSTALLMENT_FREE). 표를 둘로 나누면 같은 코드 어휘가 두 벌이 된다. 그리고 이 축이
-- **정말 갈린다는 증거가 무이자할부**다 — ZONE 은 혜택에서만 빼고 실적에는 넣는데,
-- KaPick 은 둘 다 뺀다. 한 표에 axis 를 두면 그 차이가 두 행으로 그냥 보인다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ★ 혜택 대상은 업종코드가 아니라 '카드혜택 축'으로 받는다
--
-- 지시서에는 "INDUSTRY 는 국세청 6자리라 user_payment.ksic_code 와 바로 조인된다"고
-- 적혀 있는데, 그대로 하면 카드 한 장이 6자리 코드 수십 개를 이고 있게 된다. 그러라고
-- 만든 것이 `nts-mid.tsv` 4번째 칸이다(2026-08-10 신설, 534행 100% 배정).
--
--     user_payment.ksic_code(6자리)
--       → industry-mid.json 의 cardAxisByIndustry
--       → 카드혜택 축 21종 ('대중교통' · '카페/디저트' · '편의점' …)
--       → card_benefit_target.target_value  (kind = AXIS)
--
-- 카드 공시도 "시내버스·지하철"처럼 축의 언어로 적지 6자리로 적지 않는다. 축이 정본이므로
-- 카드 쪽에는 축 이름을 넣는다. 판정 순위는 07 §4.4 그대로 **브랜드 1순위 · 축 2순위**다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ★ 규제 — 이 표에 없는 칸이 방어다
--
-- 카드는 실제 상품이다(마스터 원칙 5 재개정 2026-08-10). "더미라서 영업이 아니다"라는
-- 방패가 없으므로 유권해석(2022.6.15) 네 요건으로 선다. 넘으면 중개업 등록 대상이 되는
-- 선 넷 중 둘이 스키마에 그대로 나타난다.
--
--     신청 URL·CTA 링크 칸을 두지 않는다   ← 여기는 혜택 비교까지다
--     제휴·광고비·노출순위 칸을 두지 않는다  ← 순위는 절감액순이고 근거를 같이 싣는다
--
-- `source_url` 은 **공시 원문 주소**이지 신청 링크가 아니다. 화면에 링크로 걸지 않는다.
-- 혜택 개정 추적은 스코프 밖이라 카드 정보는 수집 시점 스냅샷이고, `as_of`(심의필 날짜)를
-- 화면에 병기하는 것이 유일한 방어다. 그래서 `as_of` 가 없으면 `grade = REFERENCE` 다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 왜 FK 에 ON DELETE CASCADE 인가
--
-- 이 표들은 사용자 데이터가 아니라 **공시를 다시 읽으면 통째로 갈아끼우는 스냅샷**이다.
-- 카드 한 장을 지우면 그 카드의 연회비·구간·혜택·대상이 같이 사라져야 한다. 재적재가
-- "카드 지우고 다시 넣기"로 끝나야 부분 갱신에서 오는 유령 행이 안 생긴다.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 카드 한 장

CREATE TABLE card_product (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    -- 'BC카드' · '신한카드'. 카드사 이름은 원문 표기 그대로 둔다.
    issuer        VARCHAR(20)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    -- 카드사 내부 상품번호('104520'). 카드사가 바꾸지 않는 유일한 키라 재적재의 기준이 된다.
    product_id    VARCHAR(30)  NOT NULL,
    -- CREDIT / CHECK. 체크카드는 전월실적 구조가 달라 1급 구분이다.
    card_type     VARCHAR(10)  NOT NULL,
    -- ACTIVE / STOPPED. 발급중단(B군)도 **버리지 않는다** — 혜택 비교의 과거 축이다.
    status        VARCHAR(10)  NOT NULL,
    -- DISCOUNT_POINT / MILEAGE / PREMIUM.
    -- 마일리지는 '1,000원당 1마일'이라 %로 표현이 안 되고, 프리미엄(라운지·발렛)은 금액
    -- 환산이 안 된다 — 둘 다 계산에서 빼되 표시는 한다.
    benefit_style VARCHAR(20)  NOT NULL,
    -- K패스·기후동행 등 정책 카드. 환급형이라 혜택 구조가 일반 카드와 다르다.
    policy_card   BIT(1)       NOT NULL DEFAULT b'0',
    -- 후불교통 기능. 마이데이터 카드-002 의 is_trans_payable 과 짝이 맞는다.
    -- **NULL 은 '없다'가 아니라 '공시에 안 적혀 있다'** 이다 — 상품설명서가 이 기능을
    -- 늘 적지는 않는다. 모르는 것을 0 으로 적으면 그 자리가 사실이 돼 버린다.
    has_transit   BIT(1)       NULL,
    -- 여신금융협회 심의필 날짜. **화면에 나가는 기준일이 이 값이다.**
    as_of         DATE         NULL,
    review_no     VARCHAR(60)  NULL,
    -- 공시에 올라온 날. as_of 와 다를 수 있어 따로 둔다(ZONE: 심의 11-07, 게시 11-27).
    posted_at     DATE         NULL,
    -- **공시 원문 주소다. 신청 링크가 아니다** — 화면에 CTA 로 걸지 않는다.
    source_url    VARCHAR(500) NULL,
    -- 연회비 청구 방식 등 금액으로 안 담기는 단서. '가족카드 발급 시 카드당 기본연회비 1만원'.
    annual_fee_note VARCHAR(400) NULL,
    -- 혜택 **전체**에 걸리는 단서. 묶음 하나에 붙는 것은 card_benefit.conditions_text 다.
    -- '자체 가맹점번호로 승인되는 일부 결제(간편결제·키오스크)는 7% 적립이 안 될 수 있다' 처럼,
    -- 우리가 계산으로는 못 반영하지만 **숨기면 안 되는** 문장이 여기 온다.
    benefit_note  VARCHAR(600) NULL,
    -- PRECISE / REFERENCE. 게이트 3(규칙 검산)을 통과했는가.
    -- REFERENCE 면 **숫자를 화면에 보여주지 않는다.** 기본값이 REFERENCE 인 것은,
    -- 검산을 거치지 않고 들어온 행이 정밀로 보이는 사고가 더 비싸기 때문이다.
    grade         VARCHAR(10)  NOT NULL DEFAULT 'REFERENCE',
    -- 왜 참고인가 — '실적 제외 3개(<5)' 처럼 걸린 규칙을 적는다. 사람이 볼 단서다.
    grade_reason  VARCHAR(200) NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    -- 카드사가 다르면 상품번호가 겹칠 수 있다. 카드사와 함께여야 키다.
    UNIQUE KEY uk_card_product_issuer_pid (issuer, product_id),
    -- 추천 후보를 고를 때 쓰는 술어.
    KEY idx_card_product_status (status, grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 브랜드별 연회비
--
-- 한 카드에 (국내전용 BC · 해외겸용 Mastercard) 처럼 여러 줄이 붙는다. 절감액 마지막
-- 단계에서 빼는 값이라 정확해야 한다.

CREATE TABLE card_annual_fee (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    card_id   BIGINT      NOT NULL,
    -- DOMESTIC(국내전용) / GLOBAL(해외겸용).
    scope     VARCHAR(10) NOT NULL,
    -- 'BC' · 'Mastercard' · 'Visa'. 원문 표기 그대로.
    brand     VARCHAR(20) NOT NULL,
    -- 총연회비. 화면에 나가는 값이고 **항상 있어야 한다.**
    total     INT         NOT NULL,
    -- 기본/제휴 분해. **NULL 을 허용한다** — 총액만 적는 공시가 있는데, 여기에
    -- CHECK(total = base + affiliate) 를 걸면 그런 카드는 아예 못 들어온다.
    -- 검산은 DB 가 아니라 게이트 3 이 한다(둘 다 있는데 합이 안 맞으면 오류).
    base      INT         NULL,
    affiliate INT         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_annual_fee (card_id, scope, brand),
    CONSTRAINT fk_card_annual_fee_card FOREIGN KEY (card_id)
        REFERENCES card_product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 실적 산정 규칙 (카드당 한 행)
--
-- 절감액 계산 1단계 "전달 승인내역 → 실적 제외 빼기 → 전월실적"이 읽는 곳이다.
-- 이 단계를 빼먹으면 실적이 과대 계산된다 — 총소비 45만이어도 관리비·공과금·대중교통이
-- 13만이면 실적은 32만이다.

CREATE TABLE card_performance_rule (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    card_id                     BIGINT       NOT NULL,
    -- '전월 1일~말일'. 자연어 그대로 둔다 — 카드사가 다르게 적는 것을 표준화하면
    -- 원문과 대조가 안 된다.
    period_label                VARCHAR(40)  NOT NULL,
    -- APPROVAL(승인일) 이 기본이다.
    basis                       VARCHAR(10)  NOT NULL,
    -- PURCHASE(매입일). 해외결제·무승인결제(대중교통·통신요금·자동납부·기내판매)가
    -- 여기로 빠진다. 셋 다 **월 귀속이 달라져** 실적 판정이 한 달 밀린다.
    basis_exception             VARCHAR(10)  NULL,
    basis_exception_targets     VARCHAR(300) NULL,
    -- '국내 일시불, 해외 일시불, 할부'.
    includes                    VARCHAR(200) NULL,
    -- 가족카드 합산 여부. **NULL 은 '아니다'가 아니라 '공시에 안 적혀 있다'** 이다 —
    -- 3장 중 ZONE 만 명시했다. 모르는 것을 false 로 적으면 그 자리가 사실이 돼 버린다.
    includes_family_card        BIT(1)       NULL,
    -- 신규회원 유예. '최초 카드 사용 등록일로부터 다음달 말일' 같은 자연어다.
    new_member_grace_until      VARCHAR(160) NULL,
    -- 유예기간에 적용해 주는 구간(원). 구간 행 FK 가 아니라 금액인 것은 적재 순서 때문이다
    -- (규칙 → 구간 → 규칙 이 되면 서로를 기다린다).
    new_member_applied_tier_krw INT          NULL,
    new_member_note             VARCHAR(300) NULL,
    PRIMARY KEY (id),
    -- 카드당 한 행이다.
    UNIQUE KEY uk_card_performance_rule_card (card_id),
    CONSTRAINT fk_card_performance_rule_card FOREIGN KEY (card_id)
        REFERENCES card_product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 실적 구간 — 개수가 카드마다 다르다
--
-- ZONE 2단(30·60만) · 페이북 2단(40·80만) · KaPick 4단(15·30·60·100만).
-- 구간이 행이라 한도를 여기에 매달 수 있고, 그 덕에 "없는 구간의 한도"가 구조적으로 안 든다.
--
-- 실적 조건이 아예 없는 카드는 행이 0 개다.

CREATE TABLE card_performance_tier (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    card_id       BIGINT NOT NULL,
    -- 1부터. 정렬을 이 값으로 고정한다(원칙 3 재현성).
    tier_no       INT    NOT NULL,
    -- 이 구간이 열리는 최소 실적(원).
    threshold_krw INT    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_tier_no (card_id, tier_no),
    -- 같은 카드에 같은 금액의 구간이 둘일 수 없다. 추출이 표를 겹쳐 읽으면 여기서 걸린다.
    UNIQUE KEY uk_card_tier_threshold (card_id, threshold_krw),
    CONSTRAINT fk_card_tier_card FOREIGN KEY (card_id)
        REFERENCES card_product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. 제외 목록 — 실적 축과 혜택 축 둘 다
--
-- **카드마다 다르다. 공통 목록으로 뭉치면 안 된다.**
--   · ZONE 은 대중교통을 실적에서 빼는데 KaPick 은 안 뺀다(그리고 혜택 대상으로 삼는다)
--   · 무이자할부는 ZONE 에서 혜택만 못 받고 실적에는 들어가는데, KaPick 은 둘 다 빠진다
--
-- 실적 제외 항목이 5개 미만이면 게이트 3 이 '의심'으로 잡는다 — 이번에 KaPick 을
-- 1개로 잘못 읽었던 사고(지면에 박스가 둘인데 위쪽만 읽었다)를 잡았을 규칙이다.

CREATE TABLE card_exclusion (
    id      BIGINT       NOT NULL AUTO_INCREMENT,
    card_id BIGINT       NOT NULL,
    -- PERFORMANCE(전월실적에서 뺀다) / BENEFIT(혜택을 안 준다). **다른 축이다.**
    axis    VARCHAR(12)  NOT NULL,
    -- CASH_ADVANCE · PUBLIC_DUES · TRANSIT · INSTALLMENT_FREE …
    code    VARCHAR(30)  NOT NULL,
    -- 원문 문구. 길다 — '공과금(국세/관세/지방세, 우편요금, 여권, 범칙금, …)' 이 87자다.
    label   VARCHAR(300) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_exclusion (card_id, axis, code),
    CONSTRAINT fk_card_exclusion_card FOREIGN KEY (card_id)
        REFERENCES card_product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. 혜택 묶음
--
-- 계산 3단계 "전달 소비를 혜택 묶음별로 분배"의 단위다. 한 결제는 **한 묶음에만** 간다 —
-- 그래서 공시의 `exclusive_with`(쇼픽 ↔ 카카오페이 기본적립)를 계산에 쓰지 않고 원문으로만
-- 남긴다. 분배가 이미 하나를 고르므로 이중 계산이 구조적으로 안 난다.

CREATE TABLE card_benefit (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    card_id            BIGINT       NOT NULL,
    -- 공시가 쓰는 묶음 이름 그대로. 'EAT-ZONE' · '먹픽' · '기본 적립'.
    -- GROUP 이 예약어라 group_name 이다.
    group_name         VARCHAR(40)  NOT NULL,
    -- DISCOUNT(할인) / POINT(적립) / INSTALLMENT_FREE(무이자할부) / NON_MONETARY(비금전).
    kind               VARCHAR(20)  NOT NULL,
    -- '결제일 할인' 처럼 언제 돌려주는가.
    settle             VARCHAR(30)  NULL,
    -- BRAND(브랜드지정) / AXIS(업종지정) / ALL(모든가맹점).
    -- ALL 은 "특정 대상이 아니라 나머지 전부"라 화면에서 다르게 읽어야 한다(페이북 기본 1%).
    scope              VARCHAR(10)  NOT NULL,
    rate_percent       DECIMAL(5,2) NULL,
    -- '최대 1% (0.5% 또는 1%)' 처럼 조건에 따라 두 값이 갈리는 것. 단일 숫자로 못 담는다.
    rate_conditional   VARCHAR(60)  NULL,
    -- 정액 할인(원). '5,000원 이상 결제 시 5,000원 할인'은 %로 표현이 안 된다.
    amount_krw         INT          NULL,
    -- 건당 최소 결제금액. 위 정액 할인과 짝이고 무이자할부의 '5만원 이상'도 이 칸이다.
    min_amount_per_txn INT          NULL,
    -- 이 혜택이 열리는 최소 구간. NULL 이면 실적과 무관하다(해외 적립 등).
    requires_tier_id   BIGINT       NULL,
    -- 통합한도 묶음 이름. 같은 값을 가진 혜택들이 card_combined_cap 을 함께 쓴다.
    combined_cap_group VARCHAR(40)  NULL,
    -- 원(현금성) / '페이북 머니' / '카카오페이포인트'.
    unit               VARCHAR(30)  NOT NULL DEFAULT '원',
    -- 제3자 포인트면 그 사업자. 카카오페이포인트는 **BC 밖에서 정산된다** — 소멸·전환 조건이
    -- 우리 손 밖이라 현금과 같은 값으로 말하면 안 된다는 표시다.
    unit_third_party   VARCHAR(30)  NULL,
    -- 대상 목록이 닫혔는가. '편의점, 영화, 주유 **등**' 처럼 끝나면 false 고,
    -- false 면 하한 계산에서 **나열된 것만** 센다.
    targets_complete   BIT(1)       NOT NULL DEFAULT b'1',
    -- '간편결제 경유 시 제외' 같은 결제수단 조건. 승인내역에 결제수단 칸이 없어
    -- **판정 불가**다 — 칸은 두되 계산에서 뺀다(countable 로 끈다).
    pay_channel        VARCHAR(60)  NULL,
    -- 하한 계산에 넣는가. 무이자할부·비금전(라운지)·판정불가는 0 이다.
    countable          BIT(1)       NOT NULL DEFAULT b'1',
    -- 요약에 노출할 혜택인가. 카드 상세가 혜택 6개 중 4개만 '주요 혜택'으로 보여 준다.
    is_headline        BIT(1)       NOT NULL DEFAULT b'0',
    -- 공시의 단서들. 줄바꿈으로 잇는다 — 계산에 안 쓰고 표시·검수용이다.
    conditions_text    TEXT         NULL,
    -- 공시가 적은 배타 관계('쇼픽'과 배타). **계산에 쓰지 않는다**(위 머리말).
    exclusive_with     VARCHAR(200) NULL,
    -- 화면·계산 순서를 고정한다(원칙 3 — 조회 정렬 고정).
    sort_no            INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_benefit_group (card_id, group_name),
    CONSTRAINT fk_card_benefit_card FOREIGN KEY (card_id)
        REFERENCES card_product (id) ON DELETE CASCADE,
    -- 없는 구간을 가리키는 혜택이 못 들어온다.
    CONSTRAINT fk_card_benefit_tier FOREIGN KEY (requires_tier_id)
        REFERENCES card_performance_tier (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. 혜택 묶음의 구간별 월한도
--
-- **실적 충족은 한도를 여는 것이지 한도를 받는 게 아니다.** 실적 30만을 채워 EAT 한도
-- 5,000원이 열려도 커피에 2만원 썼으면 받는 건 1,400원이다 — `min(소비 × 요율, 한도)`.
--
-- 한도가 구간 행을 FK 로 가리키므로 **"tiers 에 없는 키의 한도"가 못 들어온다.**
-- 지시서 게이트 3 의 `monthly_cap_by_tier 키 != tiers → 오류` 가 여기서는 규칙이 아니라
-- 스키마다.
--
-- 한도가 없는 혜택(페이북 기본 적립 — '적립 한도 없음')은 행이 0 개다. 0 원 한도와 다르다.

CREATE TABLE card_benefit_cap (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    benefit_id BIGINT NOT NULL,
    tier_id    BIGINT NOT NULL,
    cap_krw    INT    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_benefit_cap (benefit_id, tier_id),
    CONSTRAINT fk_card_benefit_cap_benefit FOREIGN KEY (benefit_id)
        REFERENCES card_benefit (id) ON DELETE CASCADE,
    CONSTRAINT fk_card_benefit_cap_tier FOREIGN KEY (tier_id)
        REFERENCES card_performance_tier (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. 통합한도 — 개별 한도 위에 한 겹 더
--
-- 페이북 실측: 종합몰·패션몰·생활몰이 각 5,000 인데 셋을 합친 '특별적립' 통합한도가
-- 13,000 이다. **개별 합(15,000)이 통합(13,000)을 넘으므로 절삭 순서가 결과를 바꾼다.**
-- 계산은 건당 → 월(개별) → 통합 순으로 자른다(07 §4.4 5단계).

CREATE TABLE card_combined_cap (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    card_id    BIGINT      NOT NULL,
    -- card_benefit.combined_cap_group 과 맞물리는 이름. '특별적립'.
    group_name VARCHAR(40) NOT NULL,
    tier_id    BIGINT      NOT NULL,
    cap_krw    INT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_combined_cap (card_id, group_name, tier_id),
    CONSTRAINT fk_card_combined_cap_card FOREIGN KEY (card_id)
        REFERENCES card_product (id) ON DELETE CASCADE,
    CONSTRAINT fk_card_combined_cap_tier FOREIGN KEY (tier_id)
        REFERENCES card_performance_tier (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. 혜택 대상 — 브랜드와 축이 섞인다
--
-- 한 혜택에 브랜드 5개 + 축 3개가 붙는다. 그래서 별도 표여야 한다.
--
-- **채널·제외장소를 행마다 되풀이한다.** 정규화하면 표가 하나 더 늘고, 무엇보다 매칭이
-- 조인을 한 번 더 타게 된다. 이 표를 읽는 쪽은 "스타벅스"를 찾아 **그 한 행에서** 채널과
-- 제외장소까지 받아야 한다 — 되풀이가 비용이 아니라 목적이다.

CREATE TABLE card_benefit_target (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    benefit_id    BIGINT       NOT NULL,
    -- 공시가 묶어 놓은 소분류. '커피' · '간식' · '배달' · '온쇼(온라인쇼핑)'.
    -- 같은 브랜드가 두 묶음에 들 수 있어(KaPick 올리브영: 온쇼·오쇼) 키의 일부다.
    target_group  VARCHAR(40)  NOT NULL DEFAULT '',
    -- BRAND  브랜드명 — **1순위**. 배달·디지털구독·온라인쇼핑은 업종으로 못 푼다
    --        (배달의민족이 통신판매업으로 등록돼 '쇼핑'이 된다)
    -- AXIS   카드혜택 축 21종 — **2순위**. industry-mid.json 의 cardAxisByIndustry 가
    --        user_payment.ksic_code 를 이 값으로 옮겨 준다
    -- SCOPE  '해외 가맹점' 처럼 둘 다 아닌 서술. 매칭에 안 쓰고 표시만 한다
    kind          VARCHAR(10)  NOT NULL,
    -- **`value` 가 아니라 `target_value` 다.** MySQL 에서는 `value` 가 되지만 H2 에서는
    -- 예약어라 DDL 이 깨진다(2026-08-11 실측: `expected "identifier"`). 개발·시험이 H2 이고
    -- 운영이 MySQL 이라, MySQL 에서만 되는 이름을 쓰면 **운영에서만 되고 시험에서 죽는다.**
    target_value  VARCHAR(80)  NOT NULL,
    -- '오프라인 현장결제, 브랜드 공식앱'. 채널이 갈리면 같은 브랜드도 혜택이 갈린다.
    channel       VARCHAR(120) NULL,
    -- '백화점, 면세점, 할인점, 공항, 기차역, 임대매장'. 승인내역만으로는 대개 판정이
    -- 안 되지만, 안 적어 두면 우리가 **과대 추정**한 줄도 모른다.
    exclude_place VARCHAR(200) NULL,
    note          VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_benefit_target (benefit_id, target_group, kind, target_value),
    -- 브랜드 매칭의 진입점 — "이 결제의 브랜드를 혜택으로 가진 카드가 있나".
    KEY idx_card_benefit_target_value (kind, target_value),
    CONSTRAINT fk_card_benefit_target_benefit FOREIGN KEY (benefit_id)
        REFERENCES card_benefit (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
