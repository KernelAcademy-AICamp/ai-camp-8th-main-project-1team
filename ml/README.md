# W8 — 낭비/필수 해석가능 ML (EBM) 파이프라인

각 소비를 **낭비 vs 필수**로 분류하는 해석가능 ML로 규칙 FDS를 대체(주 판정)한다. 학습은 Python,
추론은 Java(백엔드) — 모델을 형상함수 테이블로 내보내 Java 스코어러가 정확 재현(오차 ~1e-16).

## 데이터
생성된 마이데이터(MySQL `finntech_mydata`, 10.5M) → TSV 덤프 → pandas. 사용자 단위 disjoint 분리
(TRAIN 60 / VAL 15 / TEST 15 / SERVICE 10). **SERVICE는 학습·평가에서 제외**(앱 시연 전용, 누수 방지).

## 특징 (추론 일치·누수 금지)
`category2 · log금액 · 시각(sin/cos)·심야 · 요일(sin/cos)·주말 · 개인 평소대비 금액(과다) ·
user_mean_log_amount · user_disc_ratio(페르소나 프록시)`.
**제외**: `discretionary_score`(=생성 시 p_waste, 누수) · `persona`(추론 미가용) · 절대날짜(tenure 누수).

## 모델 (W8-2)
- **프로덕션 = EBM**(순수 GAM, interactions=0 → Java 정확 재현). PR-AUC **0.438** · ROC-AUC 0.795 · Brier 0.110.
- 비교 = GBM(HistGradientBoosting) PR-AUC 0.462(블랙박스 상한) → **글래스박스가 상한의 ~95%**.
- baseline = 로지스틱 0.407.
- 전역 중요도: **cat2(0.89) > 금액(0.47) > 재량지출성향(0.33) > 심야(0.30) > 소비규모(0.22)** — "왜 낭비"를 설명.
- 라벨이 베르누이 draw라 상한이 존재(관찰 불가한 페르소나 충동성·시간곡선·취미보호는 원리상 예측 불가).

## 실행
1. 생성 데이터가 MySQL에 있어야 함(`backend-mydata` generation).
2. 덤프: `mysql ... -e "SELECT ..."`로 `card_user.tsv·user_split.tsv·payments.tsv` 생성(경로는 train.py 참조).
3. 학습·내보내기: `python train.py` → `ebm_export.json`(형상함수 테이블)·`parity_samples.json`(Java 검증용)·`metrics.json`.
4. 배치: `ebm_export.json` → `backend/src/main/resources/ml/ebm_model.json`.
   Java 스코어러 `com.finntech.ml.SpendingClassifier`가 로드해 추론.

## Java 통합 (W8-4·D3)
- `SpendingClassifier` — 형상함수 테이블 → 시그모이드. `ModelParityTest`가 Java==Python 일치 검증.
- `WasteFeatureExtractor` — UserPayment + 사용자 이력으로 특징 구성(백엔드 실가용 데이터만).
- `WasteScoringService` — 거래별 낭비 판정 + "왜"(기여 특징). 규칙 FDS(§12)는 baseline 병존.
- 개인화(W8-5): `UserSpendingOverride` — 사용자가 category2를 본인 기준 필수/낭비로 지정 시 덮어씀(파기 포함).
- 노출: `GET /api/ml/waste/{userId}` · `GET /api/ml/status` · `POST /api/ml/override`.

*작성 2026-07-22. 데이터·모델 아티팩트는 저장소 밖(`~/Downloads/finntech-ml/`).*
