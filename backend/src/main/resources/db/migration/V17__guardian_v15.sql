-- 지킴이 v1.5 — 엔티티가 늘린 칼럼을 스키마에 맞춘다 (2026-08-06)
--
-- v1.5 는 판정 축을 `kind` 하나로 모으며 엔티티에 칼럼을 아홉 개 늘렸는데 마이그레이션이
-- 없었다. 운영은 `ddl-auto=validate` 라 **스키마가 어긋나면 기동 자체가 막힌다**(V6·V8 전례).
-- 실측으로도 확인했다 — 병합 트리에서 `Column "CHIP_REWARDED" not found` 로 5건이 깨졌고,
-- 운영 스키마에는 아홉 중 여덟이 없었다(2026-08-06).
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 기존 행이 있다 — 그래서 NOT NULL 에는 전부 기본값을 준다
--
--   guardian_transaction    463행
--   guardian_daily_verdict   25행
--   guardian_weekly_mission   8행
--
-- 기본값 없이 NOT NULL 을 붙이면 그 행들이 갈 곳이 없다. 값은 **판정을 바꾸지 않는 쪽**으로
-- 고른다 — `UNKNOWN`·`false`·`0` 은 "아직 v1.5 로 판정한 적 없음"을 뜻하고, 배치가 다시 돌면
-- 제 값으로 채워진다. 마이그레이션이 과거를 해석하려 들면 안 된다.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ENUM 은 네이티브다 — 값을 늘리면 MODIFY 해야 한다
--
-- `prefer_native_enum_types=false` 인데도 MySQLDialect 는 네이티브 ENUM 을 만든다. V6 가 그렇게
-- 만들었고 운영에 그대로 있다(`condition_type enum('CATEGORY_COUNT_MAX',…)`). 그래서 자바 enum 에
-- 상수를 더하면 **그 값은 저장되지 않는다** — 오류도 안 나고 잘린 채 들어갈 수 있다.
--
-- v1.5 의 `MissionType` 은 `CATEGORY_COUNT_MAX` 를 빼고 `MAX_COUNT`·`AVOID_SLOT` 을 더했다.
-- 운영의 기존 8행은 전부 `NO_SPEND_STREAK_MIN` 이라 **잃을 데이터가 없다**(2026-08-06 실측).
-- 값 목록은 Hibernate 가 만드는 형식대로 **알파벳순**으로 적는다 — 순서가 다르면 validate 가
-- 같은 타입으로 보지 않을 수 있다.

-- ── guardian_daily_verdict ───────────────────────────────────────────────────
-- 첫날 예식을 자동으로 열어 줄지. 과거 행은 이미 지나갔으므로 열지 않는다.
ALTER TABLE guardian_daily_verdict
    ADD COLUMN ceremony_auto_open BIT NOT NULL DEFAULT 0;

-- ── guardian_transaction ─────────────────────────────────────────────────────
-- v1.5 의 판정 축. 과거 결제는 다시 판정하기 전까지 UNKNOWN 이다.
ALTER TABLE guardian_transaction
    ADD COLUMN kind ENUM('FIXED','NORMAL','SANCT','TARGET','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN';

-- 고정지출 표시. 과거 행은 판정한 적이 없으므로 false 로 둔다.
ALTER TABLE guardian_transaction
    ADD COLUMN is_fixed_expense BIT NOT NULL DEFAULT 0;

-- 소비 맥락 칩(`chip`·`chip_rewarded`)은 **칼럼을 만들지 않는다.** v1.5 초안에만 있었고 붙이는
-- API 도 읽는 화면도 없었다 — 라벨링 포인트는 분류 확정이 이미 준다. 엔티티에서 걷어냈으므로
-- 스키마도 만들지 않는다. 안 쓰는 칼럼을 미리 파 두면 다음 사람이 "쓰는 데가 있나 보다"로
-- 읽고, `validate` 는 그것을 영영 요구한다. 되살릴 일이 생기면 그때 새 마이그레이션을 만든다.

-- ── guardian_weekly_mission ──────────────────────────────────────────────────
-- 미션 종류가 바뀌었다. 기존 8행(NO_SPEND_STREAK_MIN)은 새 목록에도 있어 그대로 산다.
ALTER TABLE guardian_weekly_mission
    MODIFY COLUMN condition_type
        ENUM('AVOID_SLOT','LABELING_COUNT_MIN','MAX_COUNT','NO_SPEND_STREAK_MIN') NOT NULL;

-- 특정 요일·시간대를 피하는 미션(AVOID_SLOT)의 조건. 다른 종류에는 없으므로 NULL 이다.
ALTER TABLE guardian_weekly_mission
    ADD COLUMN avoid_weekday ENUM('FRIDAY','MONDAY','SATURDAY','SUNDAY','THURSDAY','TUESDAY','WEDNESDAY') NULL;
ALTER TABLE guardian_weekly_mission
    ADD COLUMN avoid_hour_start INTEGER NULL;
ALTER TABLE guardian_weekly_mission
    ADD COLUMN avoid_hour_end INTEGER NULL;

-- 이 미션 몫의 포인트. 과거 미션은 이미 정산됐으므로 0 이 맞다 —
-- 값을 채워 넣으면 지난 주가 소급 지급된 것처럼 보인다.
ALTER TABLE guardian_weekly_mission
    ADD COLUMN point_share INTEGER NOT NULL DEFAULT 0;

-- ── guardian_point_event ─────────────────────────────────────────────────────
-- v1.5 가 `DUPLICATE_OBJECT`(중복 오브젝트 보상)를 더했다. 운영 ENUM 은 네 값뿐이라
-- **그 보상이 저장되지 않는다** — 오류 없이 잘린 채 들어가거나 거부된다. 값을 늘려 준다.
-- (기존 네 값은 그대로 두므로 지난 포인트 이력은 온전하다.)
ALTER TABLE guardian_point_event
    MODIFY COLUMN type
        ENUM('DUPLICATE_OBJECT','LABELING','MONTHLY_COMPLETE','RISK_DEFENSE','WEEKLY_MISSION') NOT NULL;
