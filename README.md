[![Review Assignment Due Date](https://classroom.github.com/assets/deadline-readme-button-22041afd0340ce965d47ae6ef1cefeee28c7c493a6346c4f15d667ab976d596c.svg)](https://classroom.github.com/a/yBcYDqOF)

# MOA

이번 달 **지킬 돈**을 스스로 정하고, 실제 소비로부터 끝까지 지켜내도록 돕는 소비 관리 서비스입니다.

## 📖목차

- [MOA](#moa)
  - [📖목차](#목차)
  - [프로젝트 진행 기간](#프로젝트-진행-기간)
  - [❤ 팀 소개](#-팀-소개)
    - [팀명](#팀명)
    - [팀원 소개](#팀원-소개)
  - [🎉 프로젝트 요약](#-프로젝트-요약)
  - [✨주요 기능 및 구현](#주요-기능-및-구현)
  - [🖥 서비스 화면](#-서비스-화면)
  - [🏗️ 아키텍쳐](#️-아키텍쳐)
  - [🛠 기술 스택](#-기술-스택)
    - [스택 선택 사유](#스택-선택-사유)
  - [📂 파일 구조](#-파일-구조)
  - [📝 설계 문서](#-설계-문서)
    - [ERD](#erd)
    - [API](#api)
    - [기획 문서](#기획-문서)
  - [📚 컨벤션](#-컨벤션)
    - [Git Commit](#git-commit)
    - [Git Branch](#git-branch)
    - [Codding](#codding)
  - [💻 구동 방법](#-구동-방법)
  - [💾 결과물](#-결과물)
    - [시연 영상](#시연-영상)

---

## 프로젝트 진행 기간

`2026.07.21 ~ 2026.08.31 (약 6주)`

중간 데모 8/7 · 최종 데모 8/31

---

## ❤ 팀 소개

### 팀명

> 📢 안녕하세요! 핀테크 주제로 프로젝트를 진행한 팀 《쌍토끼클럽》입니다.

### 팀원 소개

세 명이 각자 하나씩 맡아서 만들었습니다. 서로 주고받는 데이터를 미리 약속으로 정해두고 따로 개발했습니다.

| 담당 | 하는 일 | 팀원 |
| :---: | :--- | :---: |
| ① 소비 분석 | 카드 내역을 카테고리로 나누고, 줄여볼 만한 항목을 찾아 근거와 함께 제안합니다 | **(이름)** |
| ② 지킴·성장 | 지킬 돈을 정하고, 쓴 만큼 깎고, 남긴 만큼 캐릭터를 키웁니다 | **(이름)** |
| ③ 취향·추천 | 소비 성향을 읽어 리포트를 쓰고, 아낀 돈을 둘 통장을 비교해 보여줍니다 | **(이름)** |

---

## 🎉 프로젝트 요약

💡 **프로젝트 명**: MOA

**목적**: 충동 소비를 한 뒤 무엇을 줄여야 할지 몰라 절약을 오래 못 가는 사람이, 이번 달 지킬 돈을 정하고 끝까지 지켜내도록 돕는다

**타깃**: 배달·쇼핑·택시를 쓰고 후회하지만 무엇을 줄일지 모르는 20~30대 직장인. 절약할 마음이 없는 사람이 아니라, 가계부와 챌린지를 해봤지만 정착하지 못한 사람입니다.

**기대효과**:

- 무엇을 줄일지 스스로 고를 수 있다.
- 월말이 아니라 지금 얼마를 지키고 있는지 바로 안다.
- 절약을 하다 말지 않고 이어갈 수 있다.

**차별점**:

- **사후 분석이 아니라 사전 선택** — 이미 쓴 돈을 보여주는 대신, 이번 달 줄여볼 카테고리를 먼저 고르게 합니다.
- **전부 아니면 전무가 아님** — 카테고리마다 얼마나 줄일지 강도를 정하고, 정한 한도를 넘긴 만큼만 깎입니다.
- **잔소리하지 않음** — 고른 카테고리만 지켜보고, 나머지 소비는 평가하지 않습니다.
- **새 소비를 부르지 않음** — 여행·물건 같은 목표와 보상을 없애고, 캐릭터가 자라는 것으로 대신했습니다.

**서비스 공식**:

```text
지킬 돈 = 기준 소비 × 절약 강도
지킨 돈 = 지킬 돈 − (한도를 넘긴 금액)
```

한도를 넘긴 만큼만 깎입니다. 깎이는 규칙은 이것 하나뿐이라, 왜 깎였는지 항상 설명할 수 있습니다.

---

## ✨주요 기능 및 구현

💡 **온보딩**:

1. 가상 본인인증 (이름 · 주민번호 앞 7자리 · 휴대폰 번호)
2. 연동할 카드사·은행 선택
3. 카드 내역과 통장 내역 불러오기

💡 **마이데이터 (더미 데이터)**:

1. 더미 데이터 생성
   1. 사용자 (5가지 소비 유형)
   2. 카드 · 통장
   3. 결제 내역 (약 1,100만 건)
2. 별도 서버에서 제공 — 실제 마이데이터 사업자처럼 나눠서 만들었습니다

💡 **① 소비 분석**:

1. 카테고리별 소비 조회
2. 줄여볼 만한 항목 제안 (근거 함께 표시)
3. 이상 소비 탐지

💡 **② 지킴·성장**:

1. 이번 달 지킬 돈 정하기 (카테고리별 절약 강도)
2. 실시간 차감 — 한도를 넘긴 순간부터만 깎임
3. 월말 결산 후 캐릭터 성장

💡 **③ 취향·추천**:

1. 소비 성향 분석
2. 주간 · 월간 리포트
3. 통장 비교 (실제 금리 · 판매나 중개는 하지 않음)

💡 **내 카드 · 내 통장**:

1. 카드별 결제 내역
2. 통장 입출금 내역 (급여 · 이자 · 이체 · 카드값)
3. 거래마다 그때의 잔액 표시

---

## 🖥 서비스 화면

<summary>온보딩</summary>
<div markdown="1">
(시연 gif 추가 예정)
</div>

<summary>홈</summary>
<div markdown="1">
(시연 gif 추가 예정)
</div>

<summary>리포트</summary>
<div markdown="1">
(시연 gif 추가 예정)
</div>

<summary>마이</summary>
<div markdown="1">
(시연 gif 추가 예정)
</div>

---

## 🏗️ 아키텍쳐

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

## 🛠 기술 스택

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

## 📂 파일 구조

<details style="margin-left: 5px;">
<summary><b>프론트 프로젝트 구조</b></summary>
<div>

```
📦src
 ┣ 📂assets
 ┣ 📂components        공용 UI
 ┣ 📂lib               서버 호출 · 포맷
 ┣ 📂screens           화면 27개 (온보딩 · 홈 · 리포트 · 마이)
 ┣ 📂state             로그인 · 지킴이 상태
 ┣ 📂styles
 ┣ 📜App.tsx
 ┗ 📜main.tsx
```

</div>
</details>
<br>
<details style="margin-left: 5px;">
<summary><b>백엔드 프로젝트 구조</b></summary>
<div>

```
📦finntech
 ┣ 📂audit             기록이 바뀌지 않았음을 증명
 ┣ 📂config
 ┣ 📂domain            테이블과 짝이 되는 클래스
 ┣ 📂engine            소비 분석 계산
 ┣ 📂guardian          ② 지킴·성장
 ┣ 📂ml                낭비 판정 모델
 ┣ 📂repository
 ┣ 📂seed              더미 데이터 생성
 ┣ 📂service           ① 소비 분석 · ③ 취향·추천 · 마이데이터 연동
 ┣ 📂util
 ┣ 📂web               API
 ┗ 📜BackendApplication.java
```

</div>
</details>
<br>
<details style="margin-left: 5px;">
<summary><b>마이데이터 서버 구조</b></summary>
<div>

```
📦mydata
 ┣ 📂config
 ┣ 📂domain
 ┣ 📂dto
 ┣ 📂generation        1,100만 건 생성
 ┣ 📂repository
 ┣ 📂seed
 ┣ 📂service
 ┣ 📂util
 ┣ 📂web
 ┗ 📜MydataApplication.java
```

</div>
</details>

---

## 📝 설계 문서

### ERD

<details>
<summary>테이블 정의</summary>
<div markdown="1">

테이블은 Flyway가 관리합니다. 전체 구조는 [`V1__baseline.sql`](backend/src/main/resources/db/migration/V1__baseline.sql)에 있고, 이후 변경은 `V2` 이후 파일에 하나씩 쌓입니다.

</div>
</details>

### API

<details>
<summary>API 목록</summary>
<div markdown="1">

총 79개입니다. 컨트롤러 파일에서 확인할 수 있습니다.

| 묶음 | 경로 |
| --- | --- |
| 사용자 · 개인정보 | `/api/users/**` · `/api/privacy/**` |
| 마이데이터 | `/api/mydata/**` |
| 소비 분석 | `/api/analysis/**` · `/api/report/**` · `/api/score/**` · `/api/alert/**` |
| 지킴·성장 | `/api/guardian/**` |
| 저축 · 절약통 | `/api/points/**` · `/api/impulse/**` |
| 취향 · 추천 | `/api/taste` · `/api/savings/compare` |

</div>
</details>

### 기획 문서

- [기획 자료 전체](reference/기획/README.md) — 고객 · 시장 · 솔루션 · 스펙
- [서비스 개요](reference/기획/00_서비스개요.md) — 10분 안에 서비스를 파악하는 문서
- [화면 설계(IA)](reference/기획/04_스펙/09_IA.md)

---

## 📚 컨벤션

### Git Commit

<details>
  <summary>클릭하여 내용 표시/숨기기</summary>

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
  <summary>클릭하여 내용 표시/숨기기</summary>

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
  <summary>클릭하여 내용 표시/숨기기</summary>

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

## 💻 구동 방법

**로컬에서 실행**

```bash
./scripts/dev-up.sh          # 백엔드 2개 빌드 · 실행 · 데이터 준비
cd frontend && npm run dev   # http://localhost:5173
```

**테스트**

```bash
cd backend && ./mvnw test
cd backend-mydata && ./mvnw test
cd frontend && npx tsc --noEmit
```

**시연용 로그인 정보**

인증번호는 아무 6자리나 넣으면 됩니다.

| 소비 유형 | 이름 | 주민번호 앞 7자리 | 휴대폰 | 통신사 |
| --- | --- | --- | --- | --- |
| 과소비형 | 김우진 | 0309303 | 010-3913-6360 | LG U+ |
| 구독과다형 | 허서하 | 9502142 | 010-4747-5400 | SKT |
| 균형형 | 손채환 | 9606162 | 010-3697-8442 | SKT |
| 외식형 | 류소민 | 9710241 | 010-9835-5456 | KT |
| 절약형 | 이지우 | 9306061 | 010-7906-9834 | LG U+ |

> 통신사도 맞춰야 넘어갑니다. 번호의 가운데 4자리(국번)가 어느 통신사 대역인지 서버가 대조하기 때문입니다. 알뜰폰을 고르면 대조를 건너뜁니다.

> 실제 사람의 정보가 아니라 전부 만들어낸 값입니다.

---

## 💾 결과물

### 시연 영상

(추가 예정)
