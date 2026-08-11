# CLAUDE.md
마이데이터 기반 소비/저축 어드바이저 MOA(모아) 설계 서비스

## ★ 필수 규칙

- **코딩 규칙**: 코딩 전 `reference/forcoding.md`를 확인한다.

- **규칙 1 — tech_log 갱신**: `_archive`는 GitHub에 업로드하지 않고 ignore하는 폴더이고, `_archive/tech_log.md`는 서브시스템별 "구조→요구특성→기술선택(+대안 기각)→조합원리" 서술 문서다. 코드 수정으로 **기술 구조·조합 원리가 바뀌면** 해당 절 근거를 갱신하고 말미에 날짜를 남긴다. (상수 값의 근거는 마스터 문서 소관. 단순 버그 수정은 규칙 2만.)
- **규칙 2 — 수정 보고**: 버그 수정·리팩터링·자동 수정 포함, **무엇을 어떻게 고쳤는지 반드시 사용자에게 알린다.**
- **규칙 3 — 적용된 마이그레이션 불변**: `*/db/migration/*.sql`은 **주석 한 글자도 고치지 않는다.** Flyway가 파일 내용 전체로 체크섬을 계산하므로, 운영에 이미 적용된 파일이 바뀌면 다음 기동이 `FlywayValidateException`으로 막힌다. **저장소 전체 치환·포매팅의 예외 대상**이다. 고칠 일이 생기면 새 마이그레이션을 하나 더 만든다. (자세한 근거: `backend/src/main/resources/db/migration/README.md`)
  - **아직 적용되지 않은 파일은 번호를 옮겨도 된다** — 규칙 3이 보호하는 것은 *이미 적용된* 파일이다. 두 브랜치가 같은 번호를 만들면 git은 **파일 이름이 달라 충돌로 보지 않고**, 시험도 Flyway를 안 타서(H2 + `ddl-auto: create-drop`) **운영 기동에서만 죽는다**(`Found more than one migration with version 28`). 그 구멍은 `MigrationVersionTest`가 막는다. 번호를 옮긴 뒤에는 **develop을 먼저 병합하고 배포한다**(낮은 번호가 나중에 오면 Flyway가 또 막는다. `outOfOrder=true`로 우회하지 않는다).

## 프로젝트 오리엔테이션

- 웹 사이트 수정이 필요할 경우에는 `/reference/`의 `guide1.pdf`, `guide2.pdf`, `guide3.pdf`을 확인한다.
- **마스터 문서**: `reference/finntech_things.md` — 결정·구현의 단일 기준(Part I/II/III). 코드와 다르면 문서가 기준.
- **실행 계획서**: `reference/launch_plan.md` — 최종 배포까지의 작업 계획(진단·W1~W8·Phase 5~11). 마스터와 어긋나면 마스터가 우선.
- **스택**: Spring Boot 4.0.7(Java 17, Maven/mvnw, Jackson 3) + JPA / 개발 H2·운영 MySQL / React+Vite / RFC 3161은 BouncyCastle.
- **마이데이터(§13)**: 별도 서버 `backend-mydata`(8082)에서 본인인증(**가상 CI**)로 카드사용내역을 불러와 `Consumption(MYDATA)`로 기존 엔진에 재사용. 현재는 '가상 인증' 스텁(전화번호 미저장), 실 coolsms는 후속 개발. **생성 1,100만 건 + 실제 사람들의 명세서**가 운영에 올라가 있다.
- **소비 분류(§13-12)**: 실 명세서에는 **업종코드가 없다**. 그래서 ①확정 분류 사전(`merchant_category`) → ②업종코드 대조표 → ②-b 등록 업종 조회(사실) → ②-c 임시 분류(무료 모델, DB 미저장) → ③LLM 추정(표시만) → ④카테고리없음/기타 순으로 답을 찾고, **순위는 `MerchantCategoryService` 한 곳에만** 둔다. **PG 번호는 키가 아니다** — 한 번호에 업종이 제각각인 가맹점이 붙으므로 번호를 버리고 이름으로 본다(읽기·쓰기 양쪽). 사전은 확정/추정 두 층이고 **추정은 판정에 참여하지 않는다**(원칙 1). 기준표는 `reference/D24_데이터분류기준표.md`.
- **실 개인정보**: 실제 사람의 명세서는 **저장소 밖**(`_archive/`, gitignore)에 두고 `scripts/import-realperson.py` 로 넣는다. 제공자의 유입구는 `mydata.realdata.enabled` 로 **기본 꺼져 있다** — 켤 일이 있으면 넣고 **반드시 다시 끈다**.
- **실행**: `./scripts/dev-up.sh`(빌드→기동→시드) · 시연 `./scripts/demo-tamper.sh` · 테스트 `cd backend && ./mvnw test`.
- **법무 정본**: `legal/` 아래 다섯(방침·약관·동의 3종)이 정본이고 **고치지 않는다** — 어긋나면 코드 사본(`PrivacyService`)을 맞춘다.

## 변경 불가 설계 원칙 (마스터 §4)
1. **판단은 설명가능한 모델이, 표현은 AI가.** 판단은 규칙 엔진이거나 해석가능 ML(EBM)이며 **블랙박스는 쓰지 않는다**. AI(Gemini)에 나가는 것은 **리포트·알림 문장용 집계 수치**와 **업종 분류·브랜드 추출용 가맹점명**뿐이다 — 이용자 식별자·금액·일시·사업자번호는 안 나가고, 모델의 답은 확정이 아니라 `LLM_GUESS` 추정층에 머문다. *(2026-07-29 개정 확정 — ML 판정 전환. 2026-08-08 개정 — 가맹점명 예외를 명시. 마스터 §4 원칙 1, `legal/privacy-policy.md` 정합)*
2. **하나의 `AnalysisResult`를 세 서비스가 재사용.** 서비스는 임계치를 재계산하지 않는다.
3. **재현성**: 엔진은 `now()`를 직접 읽지 않고 `Clock`·`referenceTime` 주입. 조회 정렬 고정, Map은 `TreeMap`.
4. **세그먼트 비의존**: 카테고리 이름을 코드에 박지 않는다. 임계치는 전부 `application.yml`.
