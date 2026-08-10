# 카드 상품 공시 수집

③ 취향·추천 Agent의 **카드 추천**(FP-03)에 쓸 카드 혜택 데이터를 모은다.
소비 카테고리별 할인율·실적조건·월한도를 `card_benefit` 스키마에 넣는 것이 목표다.

```text
seeds.txt  →  fetch.py  →  out/raw/*.html  →  extract.py  →  out/cards.json
        └─ 추가 PDF 수집: fetch_pdf.py  →  out/pdf/*.pdf  →  extract_pdf.py  →  out/pdf_cards.json
```

## 왜 이렇게 만들었나

**수집과 추출을 나눈 이유** — 사이트 구조가 바뀌어도 원문이 남아 있으면 다시 긁지 않고
`extract.py`만 고치면 된다. 서버에도 부담이 덜하다.

**URL을 사람이 관리하는 이유** — 신한카드 목록 페이지는 직접 요청 시 404다. 그리고 필요한 건
20~30건이지 전수가 아니다. 수집량을 최소로 두는 편이 안전하다.

**카테고리 매핑을 자동화하지 않은 이유** — `음식점·카페·편의점·온라인쇼핑 5%`를 우리 카테고리
넷으로 쪼개는 건 판단이 필요하다. 자동으로 찍으면 틀린 줄 모르고 넘어간다. 스크립트는 후보만
제안하고 `mapped_categories`를 비워 둔다. 비어 있으면 아직 미검수라는 뜻이다.

## 데이터 출처와 그 선택

| 후보 | 상태 | 판단 |
|---|---|---|
| 여신금융협회 **카드다모아** | 데이터 API가 **HTTP 403** (세션 붙여도 동일) | **제외.** 운영자가 프로그램 접근을 막아 둔 것이라 우회하지 않는다 |
| 여신금융협회 기타 공시 | 수수료율·통계만 있고 상품 혜택 없음 | 해당 없음 |
| 공공데이터포털 | 카드사 재무통계·개별 은행 상품뿐 | 해당 없음 |
| **카드사 자사 공시** | robots.txt가 **명시적으로 허용** | **채택** |

`robots.txt` 확인 결과 (2026-07-28)

```text
신한카드    User-agent: *  Allow: /pconts/html/card/   Allow: /pconts/html/benefit/   ✅
KB국민카드  User-agent: *  Allow: /CRD/  Allow: /SVC/                                  ✅
```

카드사 자사 상품 페이지는 **법정 공시 의무 정보**이고, 운영자가 robots.txt로 공개 허용을
선언한 경로다. 남이 모아 정규화한 DB(비교 사이트)를 가져오는 것과는 성격이 다르다.

## 지키는 것

- 호스트마다 `robots.txt`를 읽고 **불허 경로는 받지 않는다.** `*`과 우리 UA 둘 다 확인해 더 엄격한 쪽을 따른다.
- **확인할 수 없으면 받지 않는다.** robots.txt를 못 읽었을 때 허용으로 넘어가면 안전장치가 아니다(아래).
- 요청 간격 **1.5초**, 동시 요청 없음.
- 이미 받은 URL은 **다시 받지 않는다**(`--force`로만 강제).
- User-Agent에 목적을 밝힌다. 브라우저인 척하지 않는다.
- 출처 URL·수집 시각·응답 상태·해시를 `out/meta.json`에 남긴다.

### 안전장치는 닫히는 쪽으로 실패해야 한다

`urllib.robotparser.RobotFileParser.read()`를 그대로 쓰면 안 된다. 내부에서 **UTF-8로만 디코딩**해서,
주석이 EUC-KR인 robots.txt(국내 사이트에 흔하다)를 만나면 예외가 난다. 그걸 "파일 없음"으로 처리하면
**실제로는 금지된 경로를 허용해 버린다.**

우리카드에서 실제로 이 일이 있었다. 그래서 직접 받아 `utf-8 → cp949 → euc-kr` 순으로 해석하고,
그래도 안 되면 **수집을 건너뛴다.** 404만 "제약 없음"으로 보고 허용한다.

## 재수집은 월 1회면 충분하다

카드 부가서비스는 규제로 묶여 있어 카탈로그가 빨리 낡지 않는다.

> 부가서비스를 **3년 이상** 제공한 상태에서 상품 수익성이 현저히 낮아진 경우에만 변경 가능하고,
> 변경 시 **변경일 6개월 전까지** 사유와 내용을 서로 다른 2가지 이상의 방법으로 고지해야 한다.
> — 신용카드 표준약관 (금융감독원장 승인). BC카드 `부가서비스 변경 근거 및 절차` 안내에서 확인.

즉 **혜택은 하루 단위로 안 바뀌고, 바뀔 땐 반드시 6개월 전에 예고된다.** 매일 긁을 이유가 없다.
`fetch.py`가 기존 파일을 건너뛰는 기본 동작이 이 사실에 기대고 있다.

## 이 층은 원래 수작업이다

금융 데이터는 조달 경로가 층마다 다르다.

| 층 | 내용 | 조달 |
|---|---|---|
| 1층 | 금리·수수료 (공시 의무) | 금감원 오픈API — 구조화된 값이 바로 온다 |
| **2층** | **카드 세부 혜택** | **공개돼 있지만 비정형 → 사람이 정제** ← 이 스크립트 |
| 3층 | 내 계좌·카드 이용내역 | 마이데이터 표준API (`backend-mydata`) |
| 4층 | 대출·보험 실시간 견적 | 금융사 계약 + 중개업 등록 — 범위 밖 |

2층이 자동화가 안 되는 건 우리 역량 문제가 아니라 층의 성격이다. 카드 비교 서비스들도
카드사 **제휴 피드 + 에디터 수작업**으로 카탈로그를 유지한다. 그래서 우리가 규모로 경쟁할 이유는 없고,
**3층(마이데이터)과 결합해야 나오는 문장**이 우리 자리다 — 자세한 건 `07_취향분석및추천_Agent_설계.md` §4.4.

## 사용법

```bash
# 1. 수집할 카드 URL을 seeds.txt에 추가 (브라우저 주소창 복사)
# 2. HTML 원문 수집
python3 scripts/collect-cards/fetch.py
python3 scripts/collect-cards/fetch.py --limit 5   # 이번엔 5건만
python3 scripts/collect-cards/fetch.py --force     # 기존 것도 다시

# 3. PDF 수집 (추가 사이트 목록 포함)
python3 scripts/collect-cards/fetch_pdf.py
python3 scripts/collect-cards/fetch_pdf.py --limit 5
python3 scripts/collect-cards/fetch_pdf.py --force

# 4. PDF 파싱
python3 scripts/collect-cards/extract_pdf.py

# 5. HTML 기반 추출
python3 scripts/collect-cards/extract.py

# 6. out/cards.json / out/pdf_cards.json을 열어 검수
```

## 검수 단계에서 채울 것

`extract.py`가 남긴 각 혜택 항목의 빈칸을 채운다.

| 필드 | 채우는 법 |
|---|---|
| `mapped_categories` | `category_candidates`를 참고해 우리 카테고리로 확정 |
| `discount_percent` | `rates_percent_found`에서 해당 카테고리의 값 |
| `performance_start` / `performance_end` | `performance_notes_raw` 참고 (전월실적 구간, 원) |
| `monthly_limit` | `limit_notes_raw` 참고 (월 한도, 원) |
| `annual_fee` | **연회비는 JS로 채워져 원문 HTML에 없다.** 페이지를 열어 직접 확인 |

다 채우면 `review_status`를 `"done"`으로 바꾼다.

## PDF 파이프라인 추가 사항

- `fetch_pdf.py`는 HTML 원문에서 PDF 링크를 수집하고 `out/pdf/`에 저장하며 `out/pdf_meta.json`(또는 기존 `out/meta.json`를 fallback)로 메타데이터를 남긴다.
- `extract_pdf.py`는 `pdftotext -layout`로 텍스트를 변환해 카드명·발급사·연회비·전월실적 조건·가맹점 목록·예외 조건을 추출한다.
- `manual_pdf/` 폴더에 사람이 직접 내려받은 PDF도 자동으로 파싱 대상에 포함된다.
- `mapped_categories`는 미검수 상태 의미로 항상 빈 배열로 유지한다.

## 검증 결과 요약 (2026-08-04)

- 수집 확인: BC카드 PDF 5건을 수집했고, KB 카드 샘플 PDF 2건을 `manual_pdf/`로 추가해 파싱 검증에 포함했다.
- 파싱 확인: `pdftotext -layout` 기반으로 8건을 파싱했고, 카드명·발급사·전월실적 조건·예외조건 필드를 추출했다.
- 주의: 현재 파싱은 PDF 텍스트의 형태에 크게 의존하므로, 연회비와 가맹점 목록은 후속 사람이 검수해야 한다.

## 산출물은 커밋하지 않는다

`out/`은 `.gitignore` 대상이다. 원문 HTML은 남의 저작물이고 재수집이 가능하다.
검수를 마친 최종 시드만 저장소에 넣는다.
