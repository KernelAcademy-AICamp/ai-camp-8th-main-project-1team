[![Review Assignment Due Date](https://classroom.github.com/assets/deadline-readme-button-22041afd0340ce965d47ae6ef1cefeee28c7c493a6346c4f15d667ab976d596c.svg)](https://classroom.github.com/a/yBcYDqOF)

# MOA

이번 달 **지킬 돈**을 스스로 정하고, 실제 소비로부터 끝까지 지켜내도록 돕는 소비 관리 서비스입니다.

## 목차

- [프로젝트 진행 기간](#프로젝트-진행-기간) · [팀 소개](#팀-소개) · [프로젝트 요약](#프로젝트-요약)
- [주요 기능 및 구현](#주요-기능-및-구현) · [서비스 화면](#서비스-화면) · [아키텍쳐](#아키텍쳐)
- [기술 스택](#기술-스택) · [파일 구조](#파일-구조) · [설계 문서](#설계-문서) · [컨벤션](#컨벤션)
- [구동 방법](#구동-방법) · [결과물](#결과물)

---

## 프로젝트 진행 기간

`2026.07.21 ~ 2026.08.31 (약 6주)`

중간 데모 8/7 · 최종 데모 8/31

---

## 팀 소개

### 팀명

핀테크 주제로 프로젝트를 진행한 **쌍토끼클럽**입니다.

### 팀원 소개

세 명이 각자 하나씩 맡아서 만들었습니다. 서로 주고받는 데이터를 미리 약속으로 정해두고 따로 개발했습니다.

<!-- 사진은 docs/team/ 에 두고 아래 주석을 풀어 끼웁니다. 권장: 정사각형 · 240px -->

| <!-- <img src="docs/team/이상현.png" width="140"> --> | <!-- <img src="docs/team/지윤정.png" width="140"> --> | <!-- <img src="docs/team/이가원.png" width="140"> --> |
| :---: | :---: | :---: |
| **이상현** | **지윤정** | **이가원** |

---

## 프로젝트 요약

**프로젝트 명** : MOA

이번 달 지킬 돈을 스스로 정하고, 실제 소비로부터 끝까지 지켜내도록 돕는 소비 관리 서비스입니다.

**목표** : 충동 소비를 하고 나서 무엇을 줄여야 할지 몰라 절약이 오래 못 가는 사람이, 이번 달 지킬 돈을 정하고 끝까지 지켜내도록 돕는다.

**타겟** : 배달·쇼핑·택시에 돈을 쓰고 후회하지만 정작 무엇을 줄일지는 모르는 20~30대 직장인. 절약할 마음이 없는 사람이 아니라, 가계부와 챌린지를 해봤지만 정착하지 못한 사람.

**차별점** :

1. **사후 분석이 아니라 사전 선택** — 이미 쓴 돈을 보여주는 대신, 이번 달 줄여볼 카테고리를 먼저 고르게 합니다.
2. **전부 아니면 전무가 아님** — 카테고리마다 얼마나 줄일지 강도를 정하고, 정한 예산을 넘긴 만큼만 깎입니다.
3. **잔소리하지 않음** — 고른 카테고리만 지켜보고, 나머지 소비는 평가하지 않습니다.
4. **새 소비를 부르지 않음** — 여행·물건 같은 목표와 보상을 없애고, 캐릭터가 자라는 것으로 대신했습니다.

---

## 주요 기능 및 구현

### 온보딩

1. 휴대폰 본인인증 (이름 · 주민번호 앞 7자리 · 휴대폰 번호)
2. 연동할 카드사·은행 선택
3. 카드 내역과 통장 내역 불러오기

### 마이데이터 (더미 데이터)

1. 더미 데이터 생성. 컴퓨터가 '세상'을 하나 만들어 둡니다

   | 무엇 | 얼마나 |
   | --- | ---: |
   | 사용자 (5가지 소비 유형) | 4,512명 |
   | 가맹점 (실주소 · 업종코드) | 3,285,845곳 |
   | 카드 결제 내역 | 10,927,508건 |
   | 통장 입출금 내역 | 12,638,854건 |

2. 격리된 마이데이터 서버에서 마이데이터 연동하여 카드 사용내역과 같은 마이데이터를 받아옵니다.

### ① 소비 분석

1. 카테고리별 소비 조회
2. 줄여볼 만한 항목 제안
3. 낭비(이상소비) 탐지하여 제시.

### 소비 분류

카드 명세서에는 가맹점 이름 위주로 남습니다. 무엇을 산 것인지 정하려면 위에서부터 순서대로
확인하고, 답을 찾으면 거기서 멈춥니다.

```text
1  확정 분류 DB 조회   사람이 확인했거나 사용자가 직접 고친 것
2  브랜드 조회        브랜드를 알 수 있다면 소분류까지 확인 가능
3  업종코드 대조표     업종코드를 가지고 소분류부터 중분류까지 분류절차 수행
4  등록 업종 조회      사업자번호로 실제 등록 업종을 확인
5  LLM 추정          가맹점명을 가지고 LLM이 분류를 추정
6  모름             여기까지 와도 모르면 모르는 것임.
```

판정 시 예외적으로 판단하는 것

1. 결제대행사(PG) 사업자번호는 다른 가게의 오분류를 막기 위해 제외
2. 같은 사업자번호에 입점 브랜드가 여럿인, PG사가 아닌 복합 사업자도 오분류를 막기 위해 제외.
3. LLM이 추정한 것은 화면에 AI 추정이라고 표기.

### ② 지킴·성장

1. 이번 달 지킬 돈 정하기
2. 실시간 차감. 예산을 넘긴 순간부터만 깎임
3. 월말 결산 후 캐릭터 성장

### ③ 취향·추천

1. 소비 성향 분석
2. 주간 · 월간 리포트
3. 통장 비교

### 내 카드 · 내 통장

1. 카드별 결제 내역
2. 통장 입출금 내역 (급여 · 이자 · 이체 · 카드값)
3. 거래마다 그때의 잔액 표시

### 기록이 바뀌지 않았음을 증명

1. 판정 기록을 사슬처럼 이어 붙입니다. 중간의 한 건만 고쳐도 그 뒤가 전부 어긋나서 바로 드러납니다.
2. 하루치 요약을 바깥 시각 인증 기관(RFC 3161)에 맡깁니다. 그러면 그 기록이 언제부터 있었는지까지 우리가 아닌 제3자가 보증합니다.
3. `./scripts/demo-tamper.sh` 를 돌리면 일부러 기록을 훼손해 보고, 그것이 잡히는 과정을 직접 확인할 수 있습니다.

### 접근성

웹 접근성 지침을 지켜 만들었습니다. 지켰다고 적어 두는 데 그치지 않고, 빌드할 때마다 화면을
실제로 그려서 색 대비와 터치 영역 크기, 초점이 옮겨 가는 순서를 값으로 재고 하나라도 미달하면
빌드를 멈춥니다.

---

## 서비스 화면

화면은 모두 45개입니다. 흐름별로 대표 화면을 둡니다.

<!-- gif 는 docs/demo/ 에 두고 아래 경로만 바꿔 끼웁니다.
     권장: 폭 300px · 10초 이내 · 파일당 5MB 이하 (GitHub 이 10MB 를 넘기면 안 보여줍니다) -->

<details open>
<summary><b>온보딩</b> — 본인인증 → 카드·은행 연동 → 줄일 카테고리 고르기</summary>
<div markdown="1">

<!-- <img src="docs/demo/01-onboarding.gif" width="300" alt="온보딩 — 본인인증부터 챌린지 시작까지"> -->

| 화면 | 무엇을 보여주나 |
| --- | --- |
| 본인인증 | 이름 · 주민번호 앞 7자리 · 통신사 · 휴대폰 (가상 인증) |
| 자산 연결 | 카드사·은행을 골라 마이데이터를 불러옵니다 |
| 온보딩 1~3 | 줄일 카테고리 고르기 → 낭비 금액 확인 → 절약 강도 정하기 |

</div>
</details>

<details>
<summary><b>홈</b> — 지금 얼마를 지키고 있는지</summary>
<div markdown="1">

<!-- <img src="docs/demo/02-home.gif" width="300" alt="홈 — 지킨 돈과 예산 소진 현황"> -->

| 화면 | 무엇을 보여주나 |
| --- | --- |
| 홈 | 지킨 돈 · 방어율 · 카테고리별 소진 · 잔디 · 한마디 |
| 알림함 | 개입 알림과 침묵 기록 |
| 소비내역 | 12개월치 결제를 중분류와 함께, 그 자리에서 분류 수정 |

</div>
</details>

<details>
<summary><b>리포트</b> — 무엇을 어떻게 썼는지</summary>
<div markdown="1">

<!-- <img src="docs/demo/03-report.gif" width="300" alt="리포트 — 카테고리별 소비와 낭비 판정"> -->

| 화면 | 무엇을 보여주나 |
| --- | --- |
| 카테고리별 소비 | 중분류별 금액과 추이 |
| 내 소비 분석 | 소비 성향과 근거 |
| 이상 소비 | 평소와 다른 결제 |
| 통장 비교 | 실제 금리 (판매·중개 없음) |

</div>
</details>

<details>
<summary><b>마이</b> — 내 기록과 설정</summary>
<div markdown="1">

<!-- <img src="docs/demo/04-my.gif" width="300" alt="마이 — 절약통·목표·분류 정리"> -->

| 화면 | 무엇을 보여주나 |
| --- | --- |
| 충동예산 절약통 | 참을수록 저절로 커지는 절약통 |
| 목표 통장 | 참은 돈이 목표로 쌓이는 과정 |
| 분류 정리 | 카테고리없음으로 남은 결제를 직접 채우기 |
| 개인정보 | 방침 열람 · 동의 철회 · 파기 |

</div>
</details>

---

## 아키텍쳐

```text
                    브라우저 / 앱
                         │
                         │ https://moaa.kro.kr
                         ▼
              ┌──────────────────────┐
              │  프론트 (nginx)       │   화면 + /api 를 뒤로 넘겨줌
              └──────────┬───────────┘
                         │
              ┌──────────▼───────────┐
              │  본체 (Spring Boot)   │   분석 · 지킴 · 리포트 · 감사기록
              └─────┬──────────┬─────┘
                    │          │
        ┌───────────▼──┐   ┌───▼──────────┐
        │ 마이데이터 서버 │   │   MySQL      │
        │ (카드·통장 제공)│   │              │
        └───────────────┘   └──────────────┘
             밖에서 접속 불가      밖에서 접속 불가
```

밖에서 들어올 수 있는 문은 **프론트 하나**뿐입니다. 마이데이터 서버와 데이터베이스는 바깥에서 아예 닿을 수 없게 막아뒀습니다. 실제 마이데이터 사업자도 그렇게 나눠져 있어서 같은 모양으로 만들었습니다.

---

## 기술 스택

<div align=center>
<!-- 백엔드 -->
<img src="https://img.shields.io/badge/-Java-007396?style=flat-square&logo=java&logoColor=white">
<img src="https://img.shields.io/badge/-SpringBoot-6DB33F?style=flat-square&logo=spring&logoColor=white">
<img src="https://img.shields.io/badge/-JPA-FFCA28?style=flat-square&logo=java&logoColor=white">
<img src="https://img.shields.io/badge/-Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white">
<!-- 데이터베이스 -->
<img src="https://img.shields.io/badge/-MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white">
<img src="https://img.shields.io/badge/-H2-1021FF?style=flat-square">
<!-- 프론트엔드 -->
<img src="https://img.shields.io/badge/-React-61DAFB?style=flat-square&logo=react&logoColor=white">
<img src="https://img.shields.io/badge/-TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white">
<img src="https://img.shields.io/badge/-Vite-646CFF?style=flat-square&logo=vite&logoColor=white">
<!-- 인프라 -->
<img src="https://img.shields.io/badge/-Docker-2496ED?style=flat-square&logo=docker&logoColor=white">
<img src="https://img.shields.io/badge/-AWS_EC2-FF9900?style=flat-square&logo=amazonec2&logoColor=white">
<img src="https://img.shields.io/badge/-nginx-009639?style=flat-square&logo=nginx&logoColor=white">
<img src="https://img.shields.io/badge/-GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white">
<!-- AI -->
<img src="https://img.shields.io/badge/-Gemini-8E75B2?style=flat-square&logo=googlegemini&logoColor=white">
</div>

### 스택 선택 사유

**마이데이터 사업자 경계를 그대로 모사해야 했습니다.** 실제 마이데이터에서 카드·통장 정보를 주는 쪽은 사업자 망 안에 있고, 밖에서 접속할 수도 없고 밖으로 나갈 일도 없습니다. 그래서 이 프로젝트도 마이데이터 서버를 별도 네트워크에 격리하고, 들어오는 문뿐 아니라 **나가는 길까지 막았습니다.**

이 경계가 실제로 지켜지는지 배포할 때마다 확인합니다. 검사는 "서버가 떴는가"만 보지 않고 **"밖에서 닿으면 안 되는 것이 정말 안 닿는가"**를 함께 봅니다.

```
[1/3] 살아 있는가
  ✓ 화면이 뜨는가
  ✓ 본체 → 마이데이터 서버 왕복이 되는가

[2/3] 닫혀 있는가
  ✓ 마이데이터 서버(8082) 밖에서 도달 불가
  ✓ 본체(8080) 밖에서 도달 불가
  ✓ 데이터베이스(3306) 밖에서 도달 불가
  ✓ 서버 상태 확인 경로 비공개
```

Supabase나 Firebase는 관리형 서버 한 개를 쓰는 구조라, 이렇게 숨겨진 두 번째 서버를 두고 그 경계를 검사하는 구성을 만들기 어렵습니다. 경계 자체가 이 프로젝트에서 보여주려는 핵심이라 포기할 수 없었습니다.

**데이터가 많고 계산이 복잡합니다.** 결제 내역이 약 1,100만 건이고, 화면 하나를 그리는 데 한 사람의 결제 2,400건을 합산합니다. Firebase는 데이터를 한 건 읽을 때마다 요금이 붙어서 이런 화면과 맞지 않고, Supabase 무료 요금제는 저장 용량이 500MB라 넣을 수가 없습니다.

**서버가 계속 켜져 있어야 했습니다.** 기록이 위조되지 않았다는 것을 증명하는 시각 인증, 5분마다 새 결제를 가져오는 작업처럼 끊기지 않고 돌아가야 하는 일이 있습니다.

**직접 배포해보는 것도 목표였습니다.** Docker로 묶고 AWS에 올리고 자동 배포까지 만들어보는 경험 자체가 이번 프로젝트에서 얻고 싶었던 것입니다.

**대신 포기한 것도 있습니다.** 로그인·실시간 기능을 공짜로 얻을 수 있었는데 직접 만들었고, 서버 관리와 배포 사고 대응을 저희가 떠안았습니다.

---

## 파일 구조

<details style="margin-left: 5px;">
<summary><b>프론트 프로젝트 구조</b></summary>
<div>

```
src/
 ┣ assets/
 ┣ components/        공용 UI
 ┣ lib/               서버 호출 · 포맷
 ┣ screens/           화면 45개 (온보딩 · 홈 · 리포트 · 마이)
 ┣ state/             로그인 · 지킴이 상태
 ┣ styles/
 ┣ App.tsx
 ┗ main.tsx
```

</div>
</details>
<br>
<details style="margin-left: 5px;">
<summary><b>백엔드 프로젝트 구조</b></summary>
<div>

```
finntech/
 ┣ audit/             기록이 바뀌지 않았음을 증명
 ┣ config/
 ┣ domain/            테이블과 짝이 되는 클래스
 ┣ engine/            소비 분석 계산
 ┣ guardian/          ② 지킴·성장
 ┣ ml/                낭비 판정 모델
 ┣ repository/
 ┣ seed/              더미 데이터 생성
 ┣ service/           ① 소비 분석 · ③ 취향·추천 · 마이데이터 연동
 ┣ util/
 ┣ web/               API
 ┗ BackendApplication.java
```

</div>
</details>
<br>
<details style="margin-left: 5px;">
<summary><b>마이데이터 서버 구조</b></summary>
<div>

```
mydata/
 ┣ config/
 ┣ domain/
 ┣ dto/
 ┣ generation/        1,100만 건 생성
 ┣ repository/
 ┣ seed/
 ┣ service/
 ┣ util/
 ┣ web/
 ┗ MydataApplication.java
```

</div>
</details>

---

## 설계 문서

### ERD

<details>
<summary>테이블 정의</summary>
<div markdown="1">

테이블은 Flyway가 관리합니다. 전체 구조는 [`V1__baseline.sql`](backend/src/main/resources/db/migration/V1__baseline.sql)에 있고, 이후 변경은 `V2` 이후 파일에 하나씩 쌓입니다. 지금은 **V48**까지 왔습니다.

한 번 배포된 마이그레이션 파일은 **주석 한 글자도 고치지 않습니다.** Flyway가 파일 내용 전체로 체크섬을 내기 때문에, 이미 적용된 파일이 바뀌면 다음 기동이 막힙니다. 고칠 일이 생기면 새 파일을 하나 더 만듭니다.

</div>
</details>

### API

<details>
<summary>API 목록</summary>
<div markdown="1">

본체 **108개**(컨트롤러 25개), 마이데이터 서버 **16개**입니다.

| 묶음 | 경로 |
| --- | --- |
| 사용자 · 개인정보 | `/api/users/**` · `/api/privacy/**` |
| 마이데이터 | `/api/mydata/**` |
| 온보딩 | `/api/onboarding` |
| 소비 분석 | `/api/analysis/**` · `/api/report/**` · `/api/score/**` · `/api/alert/**` · `/api/categories` |
| 소비 분류 사전 | `/api/merchant-category/**` · `/api/merchant-stance/**` |
| 지킴·성장 | `/api/guardian/**` |
| 저축 · 절약통 | `/api/points/**` · `/api/points/wishlist/**` · `/api/impulse/**` |
| 취향 · 추천 | `/api/taste` · `/api/savings/compare` · `/api/products/recommend` |
| 낭비 판정 모델 | `/api/ml/**` |
| 감사기록 | `/api/audit/**` |
| 계측 · 운영 | `/api/analytics/**` · `/api/ops/**` · `/api/dev/**` |

</div>
</details>

### 기획 문서

- [기획 자료 전체](reference/기획/README.md) — 고객 · 시장 · 솔루션 · 스펙
- [서비스 개요](reference/기획/00_서비스개요.md) — 10분 안에 서비스를 파악하는 문서
- [화면 설계(IA)](reference/기획/04_스펙/04_IA.md)

---

## 컨벤션

### Git Commit

<details>
  <summary>커밋 메시지 형식</summary>

> COMMIT CONVENTION

- **Commit 메세지 구조**
  - ex) feat : Add sign in page

```
<type> : <subject> // 필수
// 빈 행으로 구분
<body>      // 생략가능
// 빈 행으로 구분
<footer>    // 생략가능
```

</details>

### Git Branch

<details>
  <summary>브랜치 이름 규칙과 main 보호 규칙</summary>

> BRANCH NAMING CONVENTION

- ex) **feat/{BE/FE}-{이슈 요약}**

- **main** - 제품으로 출시 및 배포가 가능한 상태인 브랜치 → 최종 결과물 제출 용도
- **develop** - 다음 출시 버전을 개발하는 브랜치 → 기능 완성 후 중간에 취합하는 용도
- **feature** - 각종 기능을 개발하는 브랜치 → feat/login, feat/join 등으로 기능 분류 후 작업
- **hotfix** - 출시 버전에서 발생한 버그를 수정하는 브랜치

**main 규칙** — main에 올라가면 그대로 서비스에 반영되므로 잠가두었습니다.

- 직접 올릴 수 없고 PR로만 들어갑니다.
- 검사 4개가 모두 통과해야 합칠 수 있습니다. (테스트 2개 · 실제로 서버가 뜨는지 · 규칙 검사)
- main으로 보내는 PR은 `develop` 또는 `hotfix/` 에서 온 것만 받습니다.

</details>

### Codding

<details>
  <summary>이름 짓는 규칙</summary>

> CODING CONVENTION

- 1문자의 이름은 사용하지 않는다.
- 네임스페이스, 오브젝트, 함수 그리고 인스턴스에는 camelCase를 사용한다 `ex) camelCase`
- 클래스나 constructor에는 PascalCase를 사용한다. `ex) PascalCase`
- 약어 및 이니셜은 항상 모두 대문자이거나 모두 소문자여야 한다. `ex) NFT`
- 클래스명과 변수명은 `명사 사용`
- 메서드명은 `동사 사용`
- 상수명은 대문자를 사용하고, 단어와 단어 사이는 \_로 연결한다.
- component는 PascalCase를 사용한다.

</details>

---

## 구동 방법

**로컬에서 실행**

```bash
./scripts/dev-up.sh          # 백엔드 2개 빌드 · 실행 · 데이터 준비
cd frontend && npm run dev   # http://localhost:5173
```

**테스트**

```bash
cd backend && ./mvnw test          # 987건
cd backend-mydata && ./mvnw test
cd frontend && npm run build       # CSS 토큰 · 명도대비 · 접근성 · 타입 · 번들
```

`npm run build` 는 빌드만 하는 것이 아니라 **네 가지를 먼저 검사하고** 하나라도 걸리면 멈춥니다 —
정의되지 않은 CSS 토큰, 명도대비 미달, 웹 접근성 지침 위반, 타입 오류.

외부 시각 인증 기관에 실제로 붙여 보려면 `./mvnw test -Dtsa.live=true` 로 켭니다.

**시연용 로그인 정보**

인증번호는 아무 6자리나 넣으면 됩니다.

| 소비 유형 | 이름 | 주민번호 앞 7자리 | 휴대폰 | 통신사 |
| --- | --- | --- | --- | --- |
| 과소비형 | 임나아 | 8709012 | 010-9246-0227 | SKT |
| 구독과다형 | 김대섭 | 0412203 | 010-7588-1946 | LG U+ |
| 균형형 | 이효준 | 9804221 | 010-2453-9665 | LG U+ |
| 외식형 | 황정현 | 9008192 | 010-6498-2709 | SKT |
| 절약형 | 정시희 | 0411104 | 010-8588-3820 | SKT |

> 통신사도 맞춰야 넘어갑니다. 번호의 가운데 4자리(국번)가 어느 통신사 대역인지 서버가 대조하기 때문입니다. 알뜰폰을 고르면 대조를 건너뜁니다.

> **실제 사람의 정보가 아니라 전부 만들어낸 값입니다.** 성씨는 인구 비율대로(김 22% · 이 15% · 박 9%), 이름은 주민등록번호 7번째 자리의 성별에 맞춰 만듭니다 — 표는 [`scripts/identity/`](scripts/identity/)에 있습니다.

> 목록은 [`frontend/src/lib/demoUsers.ts`](frontend/src/lib/demoUsers.ts)가 정본이고 `python3 scripts/build-demo-users.py`가 만듭니다. **마이데이터를 다시 생성하면 반드시 다시 돌립니다** — 안 돌리면 여기 적힌 사람이 제공자에 없어 로그인이 막힙니다.

---

## 결과물

### 중간 데모 (2026-08-07)

<!-- 영상을 올린 뒤 아래 주석을 풀고 링크만 바꿉니다.
     · 유튜브: 썸네일 이미지에 영상 링크를 겁니다(GitHub 은 iframe 을 막습니다)
     · 파일 직접: docs/demo/ 에 mp4 를 두고 <video> 대신 링크로 겁니다(용량 100MB 제한) -->

<!-- [![중간 데모](https://img.youtube.com/vi/VIDEO_ID/0.jpg)](https://youtu.be/VIDEO_ID) -->

| 항목 | 내용 |
| --- | --- |
| 영상 | (추가 예정) |
| 발표 자료 | (추가 예정) |
| 다루는 범위 | (추가 예정) |

<details>
<summary>시연 순서</summary>
<div markdown="1">

1. (추가 예정)

</div>
</details>

### 최종 발표 (2026-08-31)

<!-- [![최종 발표](https://img.youtube.com/vi/VIDEO_ID/0.jpg)](https://youtu.be/VIDEO_ID) -->

| 항목 | 내용 |
| --- | --- |
| 영상 | (추가 예정) |
| 발표 자료 | (추가 예정) |
| 중간 데모 이후 달라진 점 | (추가 예정) |

<details>
<summary>시연 순서</summary>
<div markdown="1">

1. (추가 예정)

</div>
</details>
