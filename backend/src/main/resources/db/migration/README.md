# 이 폴더의 `.sql` 은 **한 글자도 고치지 않는다**

주석도 포함이다. 오타도, 낡은 문서 참조도 그대로 둔다.

## 왜

Flyway는 마이그레이션 파일의 **내용 전체로 체크섬(CRC32)** 을 계산해
`flyway_schema_history` 에 저장한다. 주석은 SQL이 아니지만 **파일의 일부**다.

이미 적용된 파일을 고치면 다음 기동에서 이렇게 죽는다.

```
FlywayValidateException: Migration checksum mismatch for version 6
  → Spring 컨텍스트 기동 실패 → healthcheck 실패 → 배포 롤백
```

운영 프로파일은 `flyway.enabled: true` 에 `validate-on-migrate` 를 끄지 않았다(기본 `true`).
**끄지 마라** — 그 검증이 §8-I에서 "H2가 통과시키던 스키마 누락을 MySQL 기동으로 잡는다"고
정한 바로 그 장치다.

## 실제로 밟았다 (2026-08-02)

기획 문서를 재번호하며 저장소 전체를 일괄 치환했고, `V6__guardian.sql` 첫 줄의 문서 참조가
`11_지킴이_Agent_설계.md` → `06_지킴이_Agent_설계.md` 로 바뀌었다.

```
바꾸기 전 CRC32 = 0x387575f2   (6867 bytes)
바꾼 뒤   CRC32 = 0x1934118e   (6867 bytes)   ← 길이는 같은데 값이 다르다
```

**길이가 같아서 눈으로는 안 보인다.** CI의 `운영 중지 검사`가 잡았다 —
1단계가 기준 커밋으로 DB를 채우고 2단계가 **볼륨을 지우지 않고** 갈아끼우기 때문이다.
빈 DB에서만 돌았다면 통과했고, 운영에서 처음 드러났을 것이다.

## 문서 이름이 바뀌면

**여기 말고 딴 데 적는다.** 마이그레이션의 주석은 *그때 그 시점의 기록*이고, 지금 문서가
어디 있는지는 `reference/기획/README.md` 나 `_archive/tech_log.md` 가 답할 몫이다.
과거의 기록을 현재에 맞춰 고치는 것이 애초에 이상한 일이다.

## 자바 enum 에 값을 더하면 마이그레이션이 필요하다

`prefer_native_enum_types=false` 인데도 MySQLDialect 는 **네이티브 ENUM** 을 만든다. V6 가 그렇게
만들었고 운영에 그대로 있다.

```sql
condition_type enum('CATEGORY_COUNT_MAX','LABELING_COUNT_MIN','NO_SPEND_STREAK_MIN') not null
```

그래서 자바 enum 에 상수를 더해도 **그 값은 저장되지 않는다** — 오류도 안 나고 잘린 채 들어갈 수
있다. 값을 늘렸으면 `MODIFY COLUMN` 마이그레이션을 함께 낸다(V17 이 그 예다).

```sql
ALTER TABLE guardian_weekly_mission
    MODIFY COLUMN condition_type
        ENUM('AVOID_SLOT','LABELING_COUNT_MIN','MAX_COUNT','NO_SPEND_STREAK_MIN') NOT NULL;
```

값 목록은 Hibernate 가 만드는 형식대로 **알파벳순**으로 적는다 — 순서가 다르면 `validate` 가
같은 타입으로 보지 않을 수 있다.

**자바 enum 을 눈으로 옮겨 적지 마라.** V17 초안이 칩 칼럼을
`ENUM('PLANNED','REDUCING','SETUP','SUCCESS')` 로 적었는데 뒤의 둘은 다른 enum(`ChallengeState`)의
값이었다. 컴파일도 통과하고 마이그레이션도 성공하며, 저장할 때가 되어서야 틀린 것이 드러난다.

## 엔티티를 고쳤으면 스키마도 고친다

개발은 H2 `ddl-auto=update` 라 칼럼이 늘어도 조용히 흡수한다. 운영은 `ddl-auto=validate` 라
**어긋나면 기동 자체가 막힌다.** 그래서 엔티티만 고치고 마이그레이션을 안 내면 로컬에서는
멀쩡하고 배포에서 처음 죽는다(V6·V8·V17 전례).

`.github/workflows/guard-main.yml` 이 "엔티티를 바꿨는데 Flyway 마이그레이션이 없다"를 잡는다.
스키마가 실제로 안 바뀌는 변경이면 PR 본문에 `스키마 변경 없음: <이유>` 를 적는다.

## 고칠 일이 정말 있다면

이미 적용된 파일을 고치지 말고 **새 마이그레이션을 하나 더 만든다**(다음 번호는 `V19__…`).
스키마를 되돌려야 하면 되돌리는 마이그레이션을 쓴다. 과거를 다시 쓰지 않는다.

`flyway repair` 로 체크섬을 갱신하는 길도 있지만, 그건 운영 DB에 사람이 직접 붙어야 하고
같은 일이 또 생기면 또 해야 한다 — **원인을 두고 증상을 지우는 쪽**이라 기본 대응이 아니다.
