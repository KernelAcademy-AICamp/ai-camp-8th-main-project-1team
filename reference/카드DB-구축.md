# 카드 상품 DB 구축 — 작업 지시서

- 작성 2026-08-11 · 앞선 세션의 핸드오프
- 관련 PR [#163](https://github.com/KernelAcademy-AICamp/ai-camp-8th-main-project-1team/pull/163)
- 정본은 마스터(`finntech_things.md`)와 `기획/04_스펙/07_취향분석및추천_Agent_설계.md` §4.4 —
  이 문서는 **다음에 뭘 할지**만 적는다. 결정의 근거는 저 둘에 있다.

---

## 0. 왜 이 문서가 있나

카드 추천(FP-03)의 **로직 설계는 끝났고 원문도 확보했는데, 그 사이에 그릇이 없다.**

```
backend-mydata (제공자·8082)   card_product · card_benefit 테이블 있음   ← 더미 생성용
backend (추천 엔진·8080)        카드 테이블 없음
                               CardRecommendService 가 application.yml 의 [더미] 3장을 읽는다
```

넣을 자리가 없으니 추출을 자동화할 이유도 안 생긴다. **테이블이 첫 단추다.**

---

## 1. 이미 정해진 것 (다시 논의하지 말 것)

| | 결정 | 근거 위치 |
|---|---|---|
| 상품 데이터 | **실제 카드를 쓴다** (원칙 5 재개정) | 마스터 §제외 항목 🔁 개정 2026-08-10 |
| 규제 | CTA·제휴·수수료·광고비 순위 **넷 다 금지** | 위와 같음 · `CardRecommendService` 주석 |
| 갱신 | **혜택 개정 추적은 스코프 밖** → 수집 기준일(`as_of`) 병기가 유일한 방어 | 07 §4.4 |
| 혜택 축 | 뱅크샐러드 21종 + `혜택축없음` = `nts-mid.tsv` 4번째 칸(534행) | `scripts/industry/nts-mid.tsv` 머리말 |
| 판정 순위 | **브랜드 1순위 · 업종축 2순위** (배달·디지털구독은 업종으로 못 품) | 07 §4.4 |
| 보험 축 | **안 만든다** — 우리 카드이용내역에 보험 결제가 없어 계산에 영향 없음 | 07 §4.4 |
| 대상 기준 | 낭비 판정 아님. **지속성**(성역 → 반복빈도) | 07 §4.4 |
| 절감액 | 이전율 α 없음. 전달 실측 → 실적제외 빼기 → 구간 → min(소비×요율, 한도) → 통합한도 → ×12 − 연회비 | 07 §4.4 |
| 스키마 | **3장으로 v1 확정.** 못 담는 게 나오면 칸을 더한다(마이그레이션 추가는 싸다) | 이 문서 §2 |

---

## 2. 다음 작업 — 테이블 만들기 (backend `V29`)

재료는 [`scripts/collect-cards/schema-draft.json`](../scripts/collect-cards/schema-draft.json) 하나다.
BC 3장(ZONE·페이북·KaPick)을 원문 대조로 채웠고 `uncertain` 은 0 이다.
그 파일의 `_추가할_칸`(12개)과 `_스키마_노트`(5줄)가 설계 근거다.

### 왜 3장으로 확정해도 되나

셋이 서로 충분히 다르다 — 스키마가 흔들릴 축이 이미 다 나왔다.

```
ZONE     할인 · 2구간 · 통합한도 없음 · 원 단위      · 실적제외 10
페이북    적립 · 2구간 · 통합한도 있음 · 페이북머니   · 실적제외  9
KaPick   적립 · 4구간 · 그룹별 다른 한도 · 카카오페이포인트(제3자) · 실적제외 12
```

### 표 다섯

```
card_product               카드 1장
  카드사 · 상품명 · product_id · 신용/체크 · 상태(active/stopped)
  as_of(심의필 날짜) · review_no · posted_at · source_url
  benefit_style(할인적립/마일리지/프리미엄) · policy_card · has_transit
  grade(정밀/참고)          ← 게이트 통과 여부. 참고면 숫자를 안 보여준다

card_annual_fee            브랜드별 연회비
  scope(국내전용/해외겸용) · brand · total · base · affiliate
  ※ 검산: total == base + affiliate

card_performance_rule      실적 산정
  period · basis(승인일) · basis_exception(매입일 — 해외·무승인)
  tiers[] (가변 길이! 2~4단) · includes_family_card
  new_member_grace_until · new_member_applied_tier

card_performance_exclusion 실적 제외 — **카드마다 다르다**
  code · label
  ※ ZONE 은 대중교통을 빼는데 KaPick 은 안 뺀다. 공통 목록으로 뭉치면 안 된다.

card_benefit               혜택 묶음
  group · kind(할인/적립) · settle · rate_percent · rate_conditional
  amount_krw(정액) · min_amount_per_txn · requires_tier
  monthly_cap_by_tier(구간별) · combined_cap_group · exclusive_with
  unit(원/포인트) · unit_third_party(카카오페이 등)
  scope(브랜드지정/업종지정/모든가맹점) · targets_complete · pay_channel · is_headline

card_benefit_target        혜택 대상 ★ 별도 표여야 한다
  benefit_id · kind(BRAND/INDUSTRY) · value
  channel(온라인/오프라인/공식앱) · exclude_place[] · note
  ※ 한 혜택에 브랜드 5개 + 업종 3개가 붙는다.
  ※ INDUSTRY 는 국세청 6자리라 user_payment.ksic_code 와 바로 조인된다.
```

### 놓치기 쉬운 것 넷

1. **혜택 제외 ≠ 실적 제외.** ZONE 에 두 목록이 따로 있고 무이자할부가 그 차이다 —
   혜택은 못 받는데 실적에는 들어간다. `card_benefit_exclusion` 을 따로 둘지 결정할 것.
2. **통합한도.** 페이북은 개별 합 15,000 > 통합 13,000. 절삭 순서(건당→월→통합)가 결과를 바꾼다.
3. **비금전 혜택.** ZONE 의 Mastercard Platinum 등급 서비스는 금액 환산이 안 된다 —
   계산에서 빼되 표시는 한다.
4. **판정 불가 조건.** "간편결제 경유 시 제외"는 승인내역에 결제수단이 없어 판정 불가다.
   칸은 만들되 **하한 계산에서 뺀다**.

---

## 3. 그다음 (순서대로)

```
2  적재 경로     schema-draft.json → DB 시드. 출처·as_of 필수
3  추출 자동화   원문 전문 → LLM → 스키마 JSON
                ★ grep 발췌 금지. 3단 편집이라 첫 매치 주변에 다른 칼럼이 섞인다
                  (실제로 이것 때문에 KaPick 실적제외를 1개로 잘못 읽었다. 12개였다)
4  엔진 연결     CardRecommendService 가 application.yml 대신 DB 를 읽게. [더미] 3장 제거
```

### 3번에 붙일 게이트

| 게이트 | 무엇 | 비용 |
|---|---|---|
| 1 · 스캔 판별 | 쪽당 200자 미만 → PDF 포기, HTML 공시로 | 0 |
| 2 · LLM 추출 | **전문**을 넣는다 (카드당 약 5K 토큰) | 낮음 |
| 3 · 규칙 검산 | 아래 | 0 |

**게이트 3 규칙** — 사람이 안 봐도 이상을 잡는다.

```
실적 제외 항목 수 < 5           → 의심   ← 이번 KaPick 사고를 잡았을 규칙
총연회비 != 기본 + 제휴          → 오류
실적 구간이 오르는데 한도가 내려감  → 오류
monthly_cap_by_tier 키 != tiers → 오류
rate 는 있는데 targets 가 빔     → 의심   ← ZONE &-ZONE 도 잡혔을 것
as_of 없음                     → 참고 모드
```

하나라도 걸리면 `card_product.grade = 참고` 로 두고 숫자를 안 보여준다.

---

## 4. 원문 수집 현황

원문은 **저장소 밖**이다 — `개인카드-로컬전용/` (git 이 안 본다).
카드사별 진입 방법·갱신일 필드·페이지네이션 제약은 `개인카드-로컬전용/카드사별-수집경로.md`.

| | 목록(대기) | 받은 PDF |
|---|---:|---:|
| BC (`completed`) | — | 111 |
| 신한 (`completed`) | — | 24 |
| 우리·삼성·현대·농협·국민·롯데 (`metadata_only`) | 16,253 | 7 |

- **PDF 의 38%가 스캔 이미지**라 텍스트가 안 나온다(`active` 기준 46%).
  스캔본이 옛날 카드가 아니다 — 2022년이 15건으로 최다.
- 텍스트가 나오는 것 중에도 실적 제외까지 갖춘 것은 `active` 54건 중 5건(9%)뿐이다.
  상품설명서를 디자이너가 카드마다 따로 그린 리플렛으로 내기 때문이다(Illustrator·InDesign).
- → 스캔인 카드는 OCR 대신 **HTML 공시로 메우는 편이 싸다**(`scripts/collect-cards/out/cards.json`).

**16,253건을 마저 받을지는 게이트를 BC 29건(텍스트)에 돌려 통과율을 재고 정한다.**
통과율이 높으면 전수가 의미 있고, 낮으면 좁혀야 한다. 지금은 3장 표본이라 모른다.

---

## 5. 전수를 할 이유는 없다

우리는 카드 목록 서비스가 아니라 개인화 추천이다(07 §4.4 "카탈로그 규모로 경쟁하지 않는다").

```
1순위   우리 사용자가 실제 보유한 카드   ← 마이데이터 카드-001 로 산출
2순위   추천 후보 상위에 자주 오르는 카드
3순위   나머지                        → 참고 모드로 두고 안 건드림
```

> ⚠️ **1순위 집계는 지금 하면 무의미하다.** 생성 사용자 1,100만 건의 카드는 우리 카탈로그가
> 만든 것이라 집계해봐야 우리가 넣은 분포가 나온다. 실사용자는 1명뿐이다.
> 실사용자가 늘면 그때가 제일 정확하다.
