-- 실사용자의 행태 기록 — 화면·클릭·참여시간 (2026-08-18)
--
-- 누가 언제 어느 화면에서 무엇을 눌렀고 얼마나 머물렀는지. admin 이 통계로 본다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ## 법적 근거를 먼저 적는다
--
-- 개인정보 처리방침 정본(`legal/privacy-policy.md`) 33조가 수집 항목에 이미 적고 있다 —
-- *"서비스 이용 과정에서 수집되는 IP, DeviceID, 접속내역, OS버전. **이용자의 서비스
-- 이용내역을 비롯한 행태정보**"*. 그래서 정본을 고치지 않는다(고칠 수도 없다).
--
-- 대신 그 조항이 함께 정한 두 가지를 코드가 지켜야 한다.
--   ① 보유기간 "회원 탈퇴·동의 철회까지"  → PrivacyService.eraseUserData 가 지운다
--   ② 방침 1조 "동의 없으면 더미 데모"     → **동의한 실사용자만** 기록한다
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ## 무엇을 안 담는지가 이 표의 절반이다
--
-- 클릭을 **자동으로** 줍기 때문에, 무엇을 읽느냐가 곧 무엇이 새느냐다. 이 앱의 버튼 라벨에는
-- 실제로 개인정보가 들어 있다 —
--
--     MyGoals      aria-label 에 목표 이름이 보간된다   ← 사용자가 직접 지은 이름
--     MyChallenge  aria-label 에 카테고리 라벨이 보간된다
--     Transactions 결제 행마다 버튼 (결제 식별자를 들고 있다)
--
-- (예시를 코드 그대로 옮겨 적지 않는 이유: Flyway 는 이 파일에 **플레이스홀더 치환**을
--  돌린다. 달러표 뒤 중괄호로 감싼 꼴이 주석 안에 있어도, 값을 못 찾으면 마이그레이션이
--  통째로 실패한다. 실제로 이 파일이 그것으로 한 번 막혔고 시험은 H2 + Flyway 꺼짐이라
--  못 잡았다 — MigrationPlaceholderTest 가 그 구멍을 막는다.)
--
-- 그래서 **텍스트·aria-label·value·id 를 읽지 않는다.** 대신 `data-track` 이 있으면 그것을,
-- 없으면 DOM 구조 경로(`section2>button1`)를 쓴다. GA4 도 같은 위험을 알고 있지만 저쪽은
-- *정책*으로 금지할 뿐이다("이벤트 파라미터에 PII 를 넣지 말 것"). 자동 수집에는 정책이
-- 안 통하므로 여기서는 **구조로** 막는다.
--
-- 금액·가맹점명·사업자번호·결제 식별자는 칸 자체가 없다. 넣을 자리가 없어야 안 들어간다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ## 참여시간은 벽시계가 아니다 (GA4 를 따른다)
--
-- "이벤트 사이 간격 = 체류"로 재면 **앱을 켜 두고 자리를 비운 30분이 체류로 잡힌다.**
-- GA4 는 그것을 Page Visibility API 로 푼다 — 탭이 백그라운드로 가면 타이머가 멈추고
-- 돌아오면 이어 센다. 그리고 이벤트마다 **직전 이벤트 이후 누적된 참여 시간의 델타**를
-- 실어 보내고 서버가 합산한다(`engagement_time_msec`).
--
-- `engaged_ms` 가 그 델타다. **서버가 계산할 수 없는 값이라 클라이언트가 준 것을 그대로 적는다** —
-- 서버는 그 기기가 백그라운드였는지 알 방법이 없다.
--
-- 그 델타가 **어느 화면의 것인가**는 `screen` 이 말한다. 화면을 옮길 때 수집기가
-- ① 지금 화면으로 ENGAGEMENT 를 먼저 보내고 ② 그다음 새 화면으로 SCREEN_VIEW 를 보낸다.
-- 그래서 `SUM(engaged_ms) GROUP BY screen` 이 그대로 화면별 참여시간이 된다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ## 타입 규칙은 V34 머리말과 같다
--
-- BIT(1)·DOUBLE·VARCHAR(열거값도 문자열)·표 수준 COLLATE·외래키 없음.
-- 운영이 `ddl-auto: validate` 라 하나만 어긋나도 기동이 막힌다.

CREATE TABLE usage_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,

    -- 클라이언트가 만든 세션 식별자(UUID). 30분 넘게 조용하면 새로 만든다 — GA4 기본값과 같다.
    -- 서버가 세션을 나누지 않는 이유: 기기가 백그라운드에 있던 시간을 서버는 모른다.
    session_id  VARCHAR(36)  NOT NULL,
    -- 세션 안 순번. **재전송 멱등의 근거**다 — sendBeacon 은 같은 묶음을 두 번 보낼 수 있다.
    seq         INT          NOT NULL,

    -- SESSION_START · SCREEN_VIEW · CLICK · ENGAGEMENT
    kind        VARCHAR(16)  NOT NULL,
    -- ScreenId(`home`·`transactions`·`r-analysis` …). 프론트 라우터의 값을 그대로 쓴다.
    screen      VARCHAR(40)  NOT NULL,
    -- CLICK 일 때만. `data-track` 값 또는 구조 경로. **텍스트는 절대 안 들어온다.**
    element     VARCHAR(80)  NULL,

    -- 직전 이벤트 이후 **포그라운드** 누적 ms. SESSION_START·SCREEN_VIEW 에는 없다
    -- (막 들어온 화면에는 아직 머문 시간이 없다 — GA4 도 page_view 에 안 붙인다).
    engaged_ms  INT          NULL,

    -- 서버가 받은 시각. 집계는 이것으로 한다.
    occurred_at DATETIME(6)  NOT NULL,
    -- 기기가 찍은 시각. 둘이 크게 벌어지면 기기 시계가 틀렸거나 오래 묵혀 보낸 것이다.
    -- 집계에 쓰지 않고 **이상을 알아보는 용도**로만 둔다.
    client_at   DATETIME(6)  NOT NULL,

    -- '390x844' — **창** 크기. 회전·리사이즈로 세션 도중에 바뀌므로 이벤트 줄에 둔다.
    -- (기기 화면 전체 크기와 플랫폼은 세션 내내 안 변하므로 usage_session 에 한 번만 적는다.)
    viewport    VARCHAR(12)  NULL,

    PRIMARY KEY (id),
    -- **재전송을 두 번 세지 않는다.** 화면을 벗어날 때 sendBeacon 으로 밀어낸 묶음이
    -- 다음 기동에서 한 번 더 갈 수 있다.
    UNIQUE KEY uq_usage_event_session_seq (session_id, seq),
    -- 사용자별 기간 조회(통계의 주 축).
    KEY idx_usage_event_user_time (user_id, occurred_at),
    -- 화면별 집계.
    KEY idx_usage_event_screen (screen, occurred_at),
    -- 보관기간 정리가 훑는 축.
    KEY idx_usage_event_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ─────────────────────────────────────────────────────────────────────────────
-- ## 세션 표 — 한 번의 이용 동안 안 변하는 것은 여기에 한 번만 적는다
--
-- 유입 경로·브라우저·OS·기기 종류·화면 해상도·언어·시간대는 **세션 단위로 정해지고 그 세션이
-- 끝날 때까지 안 변한다.** 이벤트 줄마다 되풀이하면 줄당 150바이트가 늘고, 이 표는 이미
-- 가장 빨리 자라는 표다(사용자 한 명이 하루 수백 줄). 2026-08-18 에 디스크가 95% 까지 찬
-- 뒤라 되풀이할 이유가 없다. GA4 도 같은 이유로 이 축들을 **세션 범위 차원**으로 둔다.
--
-- ## 왜 IP 를 안 쓰나 — 지역을 시간대에서 얻는다
--
-- GA4 는 국가·지역을 IP 로 알아낸다. 우리는 IP 를 저장하지 않으므로 대신 브라우저가 스스로
-- 말하는 IANA 시간대(`Asia/Seoul`)와 언어(`ko-KR`)를 쓴다. 굵기는 국가 수준까지이고 도시는
-- 못 낸다 — 대신 **주소를 다루지 않아도 되는 값**이라 보관이 가볍다.
--
-- ## 기기 문자열은 서버가 해석하고 원문은 버린다
--
-- 브라우저가 보내는 User-Agent 전체는 그 자체로 지문이 된다. 받아서 브라우저 이름·주버전·
-- OS 이름·기기 종류로 **줄여서만** 적고 원문은 어디에도 안 남긴다. GA4 가 보여 주는 굵기와
-- 같고, 그보다 잘게 쪼개지 않는다.
CREATE TABLE usage_session (
    -- 클라이언트가 만든 UUID. usage_event.session_id 와 같은 값이지만 **외래키를 두지 않는다**
    -- (V34 머리말과 같은 이유 — 파기가 벌크 DML 이라 FK 가 있으면 막힌다).
    session_id      VARCHAR(36)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    started_at      DATETIME(6)  NOT NULL,

    -- ── 획득 (GA4 의 사용자/트래픽 획득) ──────────────────────────────────────
    -- DIRECT | REFERRAL | ORGANIC | SOCIAL | INTERNAL — source·medium 에서 서버가 파생한다.
    channel         VARCHAR(20)  NOT NULL DEFAULT 'DIRECT',
    -- utm_source · utm_medium · utm_campaign. 없으면 referrer 호스트가 source 가 된다.
    source          VARCHAR(60)  NULL,
    medium          VARCHAR(40)  NULL,
    campaign        VARCHAR(60)  NULL,
    -- **호스트만.** 경로·질의는 버린다 — 남의 사이트 주소에 무엇이 붙어 있을지 모른다.
    referrer_host   VARCHAR(100) NULL,

    -- ── 기기 (GA4 의 Tech) ────────────────────────────────────────────────────
    device_category VARCHAR(10)  NULL,   -- mobile | tablet | desktop
    browser         VARCHAR(30)  NULL,   -- Chrome · Safari · Firefox · Samsung Internet …
    browser_version VARCHAR(10)  NULL,   -- 주버전만. '124' 지 '124.0.6367.60' 이 아니다
    os              VARCHAR(20)  NULL,   -- Android · iOS · Windows · macOS · Linux
    os_version      VARCHAR(10)  NULL,
    -- 기기 화면 전체. viewport(창) 와 다르다 — 둘을 견주면 앱이 전체화면인지 알 수 있다.
    screen_size     VARCHAR(12)  NULL,
    -- web · android · ios (Capacitor). 이벤트 줄에도 있지만 세션 축 집계를 조인 없이 하려고 둔다.
    platform        VARCHAR(20)  NULL,

    -- ── 지역·언어 (GA4 의 인구통계 중 우리가 낼 수 있는 것) ───────────────────
    language        VARCHAR(20)  NULL,   -- 'ko-KR'
    time_zone       VARCHAR(40)  NULL,   -- 'Asia/Seoul'
    country         VARCHAR(2)   NULL,   -- 시간대에서 파생한 ISO 국가 코드. 도시는 못 낸다

    PRIMARY KEY (session_id),
    KEY idx_usage_session_user (user_id, started_at),
    KEY idx_usage_session_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────────
-- ## 인구통계 — 연령은 이미 있고 성별만 없었다
--
-- ─────────────────────────────────────────────────────────────────────────────
-- GA4 의 인구통계 보고서에 해당하는 축을 세우려는데, 연령은 이미 있고
-- (`app_user.birth_year`, V8) 성별만 없었다. 없던 이유는 못 구해서가 아니다 —
-- `AuthService.birthYearOf` 가 주민번호 앞 7자리에서 **연도만 뽑고 성별세대코드는 버렸다.**
--
-- ## 동의 범위 안이다
--
-- 정본이 이미 수집항목으로 적고 있다. 새로 받을 동의가 없다.
--
--   `legal/privacy-policy.md` 33조 1·2항
--       "이름, 생년월일, **성별**, 휴대폰번호, 이동통신사, CI, DI …"
--   `legal/consent-credit-info.md`
--       "이름, 생년월일, **성별**, 휴대폰번호, …"
--
-- 정본은 고치지 않는다(고칠 수도 없다). 코드가 정본을 따라간 것이다.
--
-- ## 성별세대코드가 세기와 성별을 동시에 정한다
--
--   1·3·5·7·9 = 남    2·4·6·8·0 = 여
--   1·2=1900년대 내국인  3·4=2000년대 내국인  5·6=1900년대 외국인
--   7·8=2000년대 외국인  9·0=1800년대
--
-- 세기는 이미 `birthYearOf` 가 쓰고 있었다. 같은 한 글자에서 성별도 나온다 — 홀수면 남,
-- 짝수면 여. **내외국인 구분은 담지 않는다.** 통계에 쓸 일이 없고, 담는 순간 사람을 좁히는
-- 축이 하나 더 늘어난다.
--
-- ## 파기
--
-- `PrivacyService.eraseUserData` 가 `birth_year` 를 null 로 만드는 그 자리에서 함께 지운다.
-- 본인인증에서 파생한 값이라 보유 근거가 같다.
--
-- ## 타입
--
-- 열거값이지만 **VARCHAR + Java String** 이다. `@Enumerated` 를 쓰면 Hibernate 가 MySQL
-- 네이티브 `enum(...)` 을 기대해 운영의 `ddl-auto: validate` 가 갈린다(V24·V34 머리말과 같다).
-- 기존 사용자는 NULL 로 남는다 — 다음 본인인증 때 채워지고, 통계는 '미상' 으로 센다.
ALTER TABLE app_user
    ADD COLUMN gender VARCHAR(10) NULL COMMENT 'MALE | FEMALE — 주민번호 성별세대코드에서 파생';
