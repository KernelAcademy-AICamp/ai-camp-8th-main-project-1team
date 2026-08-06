# W8 — 낭비/필수 해석가능 ML (EBM) 파이프라인

각 소비를 **낭비 vs 필수**로 분류하는 해석가능 ML로 규칙 FDS를 대체(주 판정)한다. 학습은 Python,
추론은 Java(백엔드) — 모델을 형상함수 테이블로 내보내 Java 스코어러가 정확 재현(오차 ~1e-16).

## 데이터
생성된 마이데이터(MySQL `finntech_mydata`, 10.5M) → TSV 덤프 → pandas. 사용자 단위 disjoint 분리
(TRAIN 60 / VAL 15 / TEST 15 / SERVICE 10). **SERVICE는 학습·평가에서 제외**(앱 시연 전용, 누수 방지).

## 특징 (추론 일치·누수 금지)
`cat2(= **우리 소비 중분류**) · log금액 · 시각(sin/cos)·심야 · 요일(sin/cos)·주말 · 개인 평소대비 금액(과다) ·
user_mean_log_amount · user_disc_ratio(페르소나 프록시)`.
**제외**: `discretionary_score`(=생성 시 p_waste, 누수) · `persona`(추론 미가용) · 절대날짜(tenure 누수).

> **cat2의 축이 바뀌었다(2026-07-29).** 예전에는 제공자가 준 소비맥락 52종이었다. 이제 제공자는
> **업종코드(KSIC 세분류)까지만** 넘기고 소비 카테고리는 앱이 붙이므로, 학습도 같은 대조표
> (`backend/src/main/resources/industry-mid.json`)를 거쳐 **중분류 16종**을 쓴다.
> `ESSENTIAL`도 같은 파일에서 읽는다 — 예전에는 이 목록이 네 곳에 손으로 복사돼 있었다.
> 재학습 전까지 `SpendingClassifier`가 체계 불일치를 감지해 **규칙 baseline으로 폴백**한다.

## 모델 (W8-2)
- **프로덕션 = EBM**(순수 GAM, interactions=0 → Java 정확 재현).
- 비교 = GBM(HistGradientBoosting)은 **정확도 상한을 재는 용도**다 — 학습만 하고 배포하지 않는다
  (원칙 1: 블랙박스 금지). 로지스틱은 최소 baseline.

**성능 수치는 여기 적지 않는다.** 회차마다 바뀌는 값을 문서에 박아 두면 반드시 낡는다 —
실제로 이 줄에 7/22 회차 값(0.438/0.462)이 2주 넘게 남아 있었다. 최신 실측은 언제나
[`metrics.json`](metrics.json) 을 본다.
- 전역 중요도(2026-08-04 회차): **cat2(1.19) > amt_vs_typical(0.50) > night(0.23) > user_disc_ratio(0.19) > log_amount(0.10)** — "왜 낭비"를 설명한다.
  이 줄도 회차마다 바뀐다. 정본은 `metrics.json` 의 `global_importance` 다.
- 라벨이 베르누이 draw라 상한이 존재(관찰 불가한 페르소나 충동성·시간곡선·취미보호는 원리상 예측 불가).

## 실행
1. 생성 데이터가 MySQL에 있어야 함(`backend-mydata` generation).
2. 덤프: **`bash ml/dump.sh`** → `user_split.tsv·card_user.tsv·payments.tsv`.
   (예전에는 이 단계가 `"SELECT ..."`로만 적혀 있어 실제 쿼리가 저장소에 없었다 —
   재학습할 때마다 쿼리를 다시 짜야 했고, 학습 때 쓴 것과 같은 데이터인지 확인할 방법이 없었다.)
3. 학습·내보내기: `python train.py` → `ebm_export.json`(형상함수 테이블)·`parity_samples.json`(Java 검증용)·`metrics.json`.
   **산출물은 `$FINNTECH_ML_DIR`(기본 `~/Downloads/finntech-ml`)에 떨어진다 — 저장소로 옮겨야 반영된다.**

   ```bash
   D="${FINNTECH_ML_DIR:-$HOME/Downloads/finntech-ml}"
   cp "$D/ebm_export.json"      backend/src/main/resources/ml/ebm_model.json   # 배포 모델
   cp "$D/parity_samples.json"  backend/src/test/resources/ml/parity_samples.json
   cp "$D/metrics.json"         ml/metrics.json                                # 성능 기록
   ```

   **셋을 함께 옮긴다.** 예전에 `ebm_export.json` 만 옮기고 `metrics.json` 을 두고 와서,
   배포된 모델의 성능을 아무도 모르는 상태가 됐다(2026-08-06 발각). 배포 모델의
   `decision_threshold` 와 `metrics.json` 의 EBM `threshold` 가 다르면 **다른 회차**라는 뜻이다.
4. 배치: `ebm_export.json` → `backend/src/main/resources/ml/ebm_model.json`.
   Java 스코어러 `com.finntech.ml.SpendingClassifier`가 로드해 추론.

## Java 통합 (W8-4·D3)
- `SpendingClassifier` — 형상함수 테이블 → 시그모이드. `ModelParityTest`가 Java==Python 일치 검증.
- `WasteFeatureExtractor` — UserPayment + 사용자 이력으로 특징 구성(백엔드 실가용 데이터만).
- `WasteScoringService` — 거래별 낭비 판정 + "왜"(기여 특징). 규칙 FDS(§12)는 baseline 병존.
- 개인화(W8-5): `UserSpendingOverride` — 사용자가 category2를 본인 기준 필수/낭비로 지정 시 덮어씀(파기 포함).
- 노출: `GET /api/ml/waste/{userId}` · `GET /api/ml/status` · `POST /api/ml/override`.

*작성 2026-07-22. 데이터·모델 아티팩트는 저장소 밖(`~/Downloads/finntech-ml/`).*
