# CLAUDE.md — 이 저장소에서 반드시 지킬 규칙

마이데이터 기반 소비/저축 어드바이저 (더미 데이터 학습용 포트폴리오).

## ★ 필수 규칙

- **코딩 규칙**: 코딩 전 `reference/forcoding.md`를 확인한다(네이밍·컨벤션). 

- **규칙 1 — tech_log 갱신**: `_archive`는 GitHub에 업로드하지 않고 ignore하는 폴더이고, `_archive/tech_log.md`는 서브시스템별 "구조→요구특성→기술선택(+대안 기각)→조합원리" 서술 문서다. 코드 수정으로 **기술 구조·조합 원리가 바뀌면** 해당 절 근거를 갱신하고 말미에 날짜를 남긴다. (상수 값의 근거는 마스터 문서 소관. 단순 버그 수정은 규칙 2만.)
- **규칙 2 — 수정 보고**: 버그 수정·리팩터링·자동 수정 포함, **무엇을 어떻게 고쳤는지 반드시 사용자에게 알린다.**

## 프로젝트 오리엔테이션

- 웹 사이트 수정이 필요할 경우에는 `/reference/`의 `guide1.pdf`, `guide2.pdf`, `guide3.pdf`을 확인한다.
- **마스터 문서**: `reference/finntech_things.md` — 결정·구현의 단일 기준(Part I/II/III). 코드와 다르면 문서가 기준.
- **실행 계획서**: `reference/launch_plan.md` — 최종 배포까지의 작업 계획(진단·W1~W7·Phase 5~11). 마스터와 어긋나면 마스터가 우선.
- **스택**: Spring Boot 4.0.7(Java 17, Maven/mvnw, Jackson 3) + JPA / 개발 H2·운영 MySQL / React+Vite / RFC 3161은 BouncyCastle.
- **마이데이터(진행 중, §13)**: 별도 서버 `backend-mydata`(8082, 더미 제공자)에서 본인인증(**가상 CI**, 실 NICE 아님)으로 카드사용내역을 불러와 `Consumption(MYDATA)`로 기존 엔진에 재사용. 현 단계는 실 SMS 없는 '가상 인증' 스텁(전화번호 미저장), 실 coolsms는 후속.
- **실행**: `./scripts/dev-up.sh`(빌드→기동→시드). 시연: `./scripts/demo-tamper.sh break|restore|regenerate`. 테스트: `cd backend && ./mvnw test`(외부 TSA는 `-Dtsa.live=true`).
- **법무 정본**: `legal/privacy-policy.md`·`legal/terms-of-service.md`. 앱은 `GET /api/privacy/policy`로 방침을 읽으므로, 정본을 고치면 `PrivacyService.policy()`도 정합시킨다.

## 변경 불가 설계 원칙 (마스터 §4)

1. **판단은 코드가, 표현은 AI가.** AI(Gemini)에는 집계 수치만 보낸다(개별 소비 원문 금지). *(개정 예정: 낭비/필수 판정을 해석가능 ML로 대체 — "판단은 설명가능한 모델(규칙 또는 해석가능 ML)이", `launch_plan.md` W8·마스터 §4 마커. 착수 전까진 현행 유효.)*
2. **하나의 `AnalysisResult`를 세 서비스가 재사용.** 서비스는 임계치를 재계산하지 않는다.
3. **재현성**: 엔진은 `now()`를 직접 읽지 않고 `Clock`·`referenceTime` 주입. 조회 정렬 고정, Map은 `TreeMap`.
4. **세그먼트 비의존**: 카테고리 이름을 코드에 박지 않는다. 임계치는 전부 `application.yml`.
5. **규제**: 추천/판매 흐름의 금융상품은 **전부 더미**(금소법 회피). 단 **정보성 비교**는 예외 — 무판매목적·무제휴·가입편의 없음이면 중개업 아님(금융위·금감원 유권해석 2022.6.15) → 실 금리 표시 허용(`SavingsCompareService`, "가입은 각 사로"). 
