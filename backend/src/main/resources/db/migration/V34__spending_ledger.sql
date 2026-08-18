-- 정리된 소비 원장 — 결제 1건 = 1줄, 월로 자른다 (2026-08-14)
--
-- 뒤에 붙을 별도 알고리즘 프로그램이 **이 표 하나만 읽고 필터링해서** 바로 데이터를 뽑는다.
-- 지금은 그럴 표가 없다: 결제 사실은 `user_payment`, 분류 근거는 `merchant_category`,
-- 브랜드는 `merchant_brand`, 고정지출과 낭비는 **어디에도 저장되지 않고 매 요청마다 다시
-- 계산된다**(RecurringPaymentDetector · ml/WasteScoringService). 무엇 하나를 알려면
-- 표 넷을 잇고 엔진 둘을 돌려야 한다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ## 이 표를 지배하는 원칙 다섯
--
--   ① **표는 계산을 일으키지 않는다.** 계산이 일어날 때 그 결과를 받아 적을 뿐이다.
--      표를 위한 재계산 배치는 없다(초기 채우기 한 번만 예외).
--   ② **한 줄 = 최신 스냅샷.** 이력을 남기지 않고 덮어쓴다. 그래서 PK 가 payment_id 다.
--   ③ **사건이 있어야 바뀌는 것만 적는다.** 고정지출의 진행/종료 여부가 그 반례다 —
--      `RecurringPayment.Status` 는 오늘 날짜를 봐서 정해지므로, 적어 두면 그 줄은 쓰인 날의
--      답을 영원히 들고 있게 된다. 대신 판단할 **재료**(group_last_paid_on · period_days)를
--      적어 읽는 쪽이 오늘과 견주게 한다.
--   ④ **확정과 추정은 칸을 나눈다.** `user_payment` 는 category2_source 한 칸이 둘을 겸하는데
--      (confirmCategory2 는 DICT/USER/REGISTRY/LLM_LOCAL/GIVE_UP 를, suggestCategory2 는
--      LLM/TEMP 를 같은 칸에 적는다), 그대로 옮기면 마스터 §4 원칙 1
--      "판단은 설명가능한 모델이" 가 표 안에서 깨진다.
--   ⑤ **실사용자만 들어온다**(app_user.real_person). 그래서 real_person 칸이 없다 —
--      더미가 안 들어오는 것은 이 표의 성질이지 이 표가 기록할 사실이 아니다.
--
-- 원칙 ①의 대가로 **칸이 비거나 낡을 수 있다.** 감추지 않는다: 층마다 언제 기록됐는지를
-- 적어 두어, 읽는 쪽이 `fixed_recorded_at < facts_updated_at` 으로 낡음을 스스로 안다.
-- 낡음 플래그를 따로 두지 않는 것은 그 플래그를 갱신할 사건이 또 필요해지기 때문이다(원칙 ③).
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ## 타입을 이렇게 쓰는 이유 — 전부 이 저장소가 이미 겪은 사고다
--
--   BIT(1)   Java boolean 을 BOOLEAN/TINYINT(1) 로 만들면 운영(ddl-auto: validate)이
--            드라이버의 tinyInt1isBit 기본값 하나에 기동을 못 한다(V24 머리말).
--            이 저장소의 Java boolean 칸은 예외 없이 BIT(1) 이다.
--   DOUBLE   Java double. V1 의 deviation_score 가 같은 모양이다.
--   VARCHAR  열거값도 문자열로 둔다. @Enumerated 를 쓰면 Hibernate 가 MySQL 네이티브
--            enum(...) 을 만들어(V1 의 guardian_challenge.state) validate 가 갈린다.
--   COLLATE  표 수준에 못박는다. V22 가 이것을 빠뜨려 서버 기본 collation 이 붙었고
--            merchant_name 대 merchant_name 비교가 ERROR 1267 로 거부됐다(V23 이 되돌렸다).
--
-- **외래키를 두지 않는다.** user_payment 를 비우는 경로가 벌크 DML 이라(재연동·파기)
-- FK 가 있으면 그 자리가 막히거나 조용히 함께 지워진다. merchant_category·merchant_brand 도
-- 같은 이유로 FK 가 없다. 정합은 재작성과 대조 점검이 지킨다.

CREATE TABLE spending_ledger (
    -- ── 식별·시간 ────────────────────────────────────────────────────────────
    -- user_payment 의 PK 를 그대로 쓴다(= 앱사용자id + ":" + 제공자 결제id).
    -- 이 한 줄이 "한 결제 = 한 줄"과 "덮어쓴다"를 동시에 보장한다.
    payment_id             VARCHAR(40)  NOT NULL,
    user_id                BIGINT       NOT NULL,
    -- 'YYYY-MM'. report.period 와 같은 형식이라 두 표를 같은 축으로 자를 수 있다.
    month_key              VARCHAR(7)   NOT NULL,
    paid_at                DATETIME(6)  NOT NULL,
    -- paid_on·day_of_month·day_of_week 는 paid_at 에서 유도되는 값이라 중복이다.
    -- **일부러 적는다.** 읽는 쪽이 계산하지 않고 필터링만 하게 하는 것이 이 표의 목적이다.
    paid_on                DATE         NOT NULL,
    day_of_month           INT          NOT NULL,
    day_of_week            INT          NOT NULL,   -- 1=월 .. 7=일 (java.time.DayOfWeek)
    -- 아침·점심·저녁·심야. AnalysisProperties.Daypart.bucketOf 한 곳에서만 나온다 —
    -- 경계값을 여기 박으면 yml 을 고쳐도 표가 안 따라온다(마스터 §4 원칙 4).
    daypart                VARCHAR(10)  NOT NULL,
    -- REAL | SYNTHETIC. UserPayment.isFromRealPerson() 이 정한다.
    -- real_person 과 다른 물음이다: 실사용자 계정에도 생성기 결제가 섞일 수 있다
    -- (데모 신원이 다섯뿐이라 같은 페르소나를 고른 계정이 실물과 함께 산다).
    origin                 VARCHAR(10)  NOT NULL,

    -- ── 가맹점 ──────────────────────────────────────────────────────────────
    -- 하이픈 없는 10자리. 없으면 빈 문자열 — NULL 과 '' 두 가지를 읽는 쪽에 떠넘기지 않는다.
    business_number        VARCHAR(10)  NOT NULL DEFAULT '',
    -- IndustryCategoryMapper.isPaymentAgency. 참이면 그 번호는 결제를 대행한 회사의 것이라
    -- 결제처를 말해 주지 않는다 — 읽는 쪽이 번호로 묶으면 안 되는 줄을 알아볼 유일한 표시다.
    payment_agency         BIT(1)       NOT NULL DEFAULT b'0',
    merchant_name          VARCHAR(60)  NULL,       -- user_payment.merchant_name 과 같은 폭
    brand                  VARCHAR(60)  NULL,       -- merchant_category.brand / merchant_brand.brand
    merchant_address       VARCHAR(200) NULL,       -- merchant_category.address (V26 과 같은 폭)
    -- 같은 계약을 한 묶음으로 모으는 키. 'BIZ:1234567890' 또는 'NAME:GS25 강남역점'.
    -- RecurringPaymentDetector.merchantKeyOf 가 만들고 **묶는 쪽과 이 칸이 같은 함수**를 쓴다 —
    -- 갈라지면 표의 고정지출 묶음과 화면의 정기결제가 다른 것을 가리키고 그 차이는 안 찍힌다.
    -- 번호도 이름도 없으면 NULL 이다. 어느 묶음에도 못 든다는 뜻이고 그게 사실이다.
    merchant_key           VARCHAR(70)  NULL,

    -- ── 금액·분류 ────────────────────────────────────────────────────────────
    amount                 INT          NOT NULL,
    -- **국세청 6자리다.** 원장의 칸 이름은 ksic_code 지만 값은 KSIC 가 아니다
    -- (IndustryCategoryMapper 머리말). 저쪽 이름은 이미 적용된 마이그레이션이라 못 고치므로
    -- 새로 만드는 이 칸에서 바로잡는다 — 같은 값에 두 이름이 남지만 옳은 이름이 하나는 있어야 한다.
    -- 실 명세서에는 업종코드가 없어 자리채움값 '642004' 가 들어 있을 수 있다
    -- (RealPersonImportService.UNKNOWN_INDUSTRY). 그 값은 대조표에 일부러 없어 '카테고리없음'이 된다.
    nts_industry_code      VARCHAR(8)   NOT NULL,
    -- 등록 업종 조회(②-b)가 받아 온 KSIC 세세분류 **이름**. 80자에서 잘린다(V21 과 같은 폭).
    registry_industry_name VARCHAR(80)  NULL,
    -- ── 확정 (판정에 참여하는 값) ──
    category2              VARCHAR(30)  NULL,
    -- NONE · DICT · REGISTRY · USER · LLM_LOCAL · GIVE_UP.
    -- user_payment 의 같은 칸은 length 10 이라 LLM_LOCAL(9자)이 상한 근처다. 여기는 12 로 둔다 —
    -- 출처를 하나 더 만들 때 이 표까지 마이그레이션이 따라붙게 하지 않는다.
    category2_source       VARCHAR(12)  NOT NULL DEFAULT 'NONE',
    -- ── 추정 (표시 전용, 판정에 참여하지 않는다) ──
    category2_llm          VARCHAR(30)  NULL,
    -- LLM(유료) · TEMP(무료 임시). **추정값은 있는데 이 칸이 NULL 인 줄이 나올 수 있다** —
    -- 원장이 한 칸에 확정 출처와 추정 출처를 겸해 담아서, 확정이 덮인 뒤에는 그 추정이 어느
    -- 통로에서 왔는지 되찾을 길이 없기 때문이다. 그때는 '출처 미상 추정'이 사실이다.
    category2_llm_source   VARCHAR(12)  NULL,

    -- ── 고정지출 (가맹점 묶음에서 나온다 · RecurringPaymentDetector 가 돌 때 기록된다) ──
    -- 아래 칸들은 판정이 아직 안 돌았으면 전부 NULL 이다. fixed 도 NULL 이다 —
    -- "고정지출이 아니다"(거짓)와 "아직 모른다"(NULL)는 다른 사실이라 갈라 둔다.
    fixed                  BIT(1)       NULL,
    -- 지금은 'FIXED' 하나뿐이다. **루틴형은 담지 않는다** — 루틴형 묶음은 (category2, 시간대)라
    -- 분류가 바뀔 때마다 다시 갈리고, 최근 창(routineWindowDays)에 매여 있어 오늘 날짜를 봐야
    -- 아는 값이다(원칙 ③). 칸을 남기는 것은 다른 종류가 생겼을 때 구별할 자리를 미리 두는 것이다.
    recurring_type         VARCHAR(10)  NULL,
    period_kind            VARCHAR(10)  NULL,       -- WEEKLY | MONTHLY
    period_days            INT          NULL,       -- 간격 평균의 반올림
    -- 간격 변동계수. fixedGapCvMax(0.20) 이하일 때만 값이 있다 — 판정이 얼마나 아슬아슬했는지
    -- 읽는 쪽이 알 수 있게 한다. 지금까지 이 값은 판정 안에서 계산되고 버려졌다.
    gap_cv                 DOUBLE       NULL,
    group_payment_count    INT          NULL,       -- 묶음의 결제 건수
    group_occurrence_days  INT          NULL,       -- 서로 다른 결제일 수(판정이 실제로 세는 값)
    group_first_paid_on    DATE         NULL,
    group_last_paid_on     DATE         NULL,
    representative_amount  BIGINT       NULL,       -- 안정이면 median, 흔들리면 최근 결제액
    amount_varies          BIT(1)       NULL,
    prior_amount           BIGINT       NULL,       -- 계단 변화(요금 인상) 이전 구간의 금액
    -- **끝났는지 여부로 비우지 않는다.** group_last_paid_on + period_days 를 그대로 적는다.
    -- "주기대로라면 다음"이지 "다음 예상"이 아니다 — 끝났는가는 오늘을 봐야 아는 것이라
    -- 읽는 쪽 몫이다(원칙 ③). RecurringPayment.nextExpected 를 그대로 옮기면 안 된다.
    next_expected_on       DATE         NULL,
    fixed_recorded_at      DATETIME(6)  NULL,
    -- 이 판정을 낸 유도 규칙의 판. 규칙을 고치면 상수를 올리고, 그러면 점검이 옛 판으로 쓰인
    -- 줄을 한 질의로 찾아낸다.
    detector_version       VARCHAR(20)  NULL,

    -- ── 낭비 (사용자 전체에서 나온다 · WasteScoringService 가 돌 때 기록된다) ──
    -- 성향 임계를 적용한 답 = 화면이 보여 주는 답.
    waste                  BIT(1)       NULL,
    waste_probability      DOUBLE       NULL,
    -- MODEL | OVERRIDE | UNJUDGED.
    --   UNJUDGED  category2 가 '카테고리없음'·'기타'·빈값이라 판정 자체를 안 했다
    --             (IndustryCategoryMapper.isUnknown).
    --   OVERRIDE  user_spending_override 가 확률을 무시하고 라벨을 덮었다. 근거 칸이 빈다.
    waste_label_source     VARCHAR(12)  NULL,
    -- 이 줄에 실제로 적용된 임계. **성향이 EXCLUDED 면 NULL 이다** — 코드가 Double.MAX_VALUE 를
    -- 쓰는데 그것은 숫자가 아니라 "어떤 확률도 넘지 못한다"는 뜻이고, 그 뜻은 stance 칸이
    -- 이미 말한다. 숫자로 적으면 읽는 쪽이 그 값으로 산술을 하다 다친다.
    waste_threshold        DOUBLE       NULL,
    stance                 VARCHAR(10)  NULL,       -- NORMAL | LENIENT | EXCLUDED
    -- 성향을 뺀 전역 임계(ebm_model.json 의 decision_threshold).
    -- 이 칸이 있으면 읽는 쪽이 waste_probability >= model_threshold 로 **집계 쪽 답**을 되살린다.
    -- WasteScoringService 는 화면(scoreUser, 성향 적용)과 집계(summarize, 전역 임계)가 서로
    -- 다른 답을 내는데, 표가 한쪽만 담으면 그 갈라짐이 표 안에서 사라진다 — 없애는 대신
    -- 둘 다 되살릴 수 있게 적는다.
    model_threshold        DOUBLE       NULL,
    -- ebm_model.json 파일의 SHA-256. 모델에는 버전 식별자가 없다(최상위 키가 intercept·
    -- features·terms·decision_threshold 뿐). 재학습은 형상함수와 임계를 통째로 갈아치우므로,
    -- 이 칸이 없으면 "왜 어제와 확률이 다른가"에 답할 수 없다.
    model_fingerprint      VARCHAR(64)  NULL,
    -- 판정을 밀어올린 축 셋. label 은 사람이 읽는 이름, detail 은 검증 가능한 수치,
    -- contribution 은 로그오즈 기여. 낭비로 판정됐을 때만 채워진다.
    factor1_label          VARCHAR(30)  NULL,
    factor1_detail         VARCHAR(80)  NULL,
    factor1_contribution   DOUBLE       NULL,
    factor2_label          VARCHAR(30)  NULL,
    factor2_detail         VARCHAR(80)  NULL,
    factor2_contribution   DOUBLE       NULL,
    factor3_label          VARCHAR(30)  NULL,
    factor3_detail         VARCHAR(80)  NULL,
    factor3_contribution   DOUBLE       NULL,
    waste_recorded_at      DATETIME(6)  NULL,

    -- ── 관리 ────────────────────────────────────────────────────────────────
    -- 사실 칸(1층)을 마지막으로 쓴 시각. 위 두 recorded_at 과 견주면 그 판정이 낡았는지 안다.
    facts_updated_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (payment_id),
    -- 주 조회축. 월로 자르는 것이 이 표의 존재 이유이고, user_id 만으로도 앞자리를 탄다.
    KEY idx_spending_ledger_user_month (user_id, month_key, paid_at),
    -- 고정지출 묶음 단위 조회.
    KEY idx_spending_ledger_user_merchant (user_id, merchant_key),
    -- 사용자를 가로질러 한 달을 뽑는 질의.
    KEY idx_spending_ledger_month (month_key),
    -- 브랜드로 훑는 질의(같은 브랜드의 지출 전부).
    KEY idx_spending_ledger_brand (brand)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ─────────────────────────────────────────────────────────────────────────────
-- 더러워진 사용자 표시
--
-- ## 왜 메모리가 아니라 표인가
--
-- 이 저장소에는 메모리 큐가 이미 둘 있고(FreeChannelQueue · mydata-followups),
-- PendingWorkTrigger 머리말이 그 성질을 적어 두었다 — "큐는 할 일의 사본이지 원본이 아니고,
-- 원본은 DB 에 남는다." 그 말이 여기서는 성립하지 않는다. **원장이 바뀌었다는 사실은 어디에도
-- 안 남는다** — 분류가 조용히 바뀐 사용자를 값싼 질의로 찾을 길이 없고, 찾으려면 재작성을
-- 해 봐야 안다. 그러면 재기동 한 번에 표가 원장과 영영 어긋난 채 남는다.
--
-- 표로 두면 표시가 **바꾼 트랜잭션과 같은 커밋에 들어간다.** 그 트랜잭션이 되돌려지면 표시도
-- 함께 되돌려지고, 커밋됐는데 표시만 없는 상태가 원리적으로 안 생긴다. 커밋 뒤에 메모리에
-- 적는 방식으로는 그 사이에 죽는 창이 남는다.
--
-- ## 유일키를 두지 않는다
--
-- 같은 사용자에 표시가 여러 줄 쌓여도 상관없다 — 배수는 사용자별로 읽고 처리한 만큼만 지운다.
-- 유일키를 두면 두 트랜잭션이 동시에 같은 사용자를 표시할 때 한쪽이 제약에 부딪히고, 그것을
-- 피하려고 별도 트랜잭션으로 빼는 순간 "같은 커밋"이라는 성질을 잃는다. 중복 몇 줄이 싸다.

CREATE TABLE spending_ledger_dirty (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    user_id   BIGINT      NOT NULL,
    -- PAYMENT · CATEGORY · STANCE · OVERRIDE · BACKFILL.
    -- 처리 분기에 쓰지 않는다 — 재작성은 언제나 그 사용자의 사실 칸 전체다. 로그·점검용이다.
    reason    VARCHAR(30) NOT NULL,
    marked_at DATETIME(6) NOT NULL,
    -- 재작성이 실패한 횟수. 한 사용자가 계속 터지면 배수가 그 줄에 걸려 영원히 헛돈다.
    -- 상한을 넘으면 건너뛰고 운영 점검이 그 줄을 보여 준다.
    attempts  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_spending_ledger_dirty_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
