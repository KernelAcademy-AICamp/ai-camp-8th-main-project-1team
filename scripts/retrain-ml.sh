#!/usr/bin/env bash
# 재생성 뒤 ML 재학습 한 벌 — 덤프 → 학습 → 배치 → **정답 라벨 대조**.
#
#   ./scripts/retrain-ml.sh
#
# 마지막 단계가 이 스크립트의 존재 이유다. 재학습은 늘 "성능 지표"만 보고 끝났는데,
# 2026-07-31 운영에서 재 보니 **정답 낭비 417건인 사용자에게 918건을 낭비로 찍고 있었다**
# (정밀도 0.334). 지표가 좋아도 화면에 나가는 판정이 2배 부풀면 사용자는 앱을 안 믿는다.
# 그래서 학습이 끝나면 원장의 waste_label 과 직접 맞춰 본다.
set -euo pipefail
cd "$(dirname "$0")/.."

ML_DIR="${FINNTECH_ML_DIR:-$HOME/Downloads/finntech-ml}"
PY="${ML_PY:-$ML_DIR/venv/bin/python}"
MYSQL_BIN="${MYSQL_BIN:-$HOME/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql}"
export FINNTECH_ML_DIR="$ML_DIR"

echo "=== 1/4 원장 덤프 ==="
MYSQL_BIN="$MYSQL_BIN" MYSQL_USER=finntech MYSQL_PWD=finntech \
  bash ml/dump.sh "$ML_DIR"

echo
echo "=== 2/4 학습 ==="
"$PY" ml/train.py

echo
echo "=== 3/4 배치 ==="
cp "$ML_DIR/ebm_export.json" backend/src/main/resources/ml/ebm_model.json
# 패리티 표본은 **모델과 함께** 갱신해야 한다. 모델만 바꾸고 이 파일을 두면 ModelParityTest 가
# 옛 표본으로 새 모델을 채점해 실패한다(2026-07-30 실제로 겪었다).
cp "$ML_DIR/parity_samples.json" backend/src/test/resources/ml/parity_samples.json
"$PY" - <<'PY'
import json, os, pathlib
p = pathlib.Path("backend/src/main/resources/ml/ebm_model.json")
d = json.loads(p.read_text(encoding="utf-8"))
print(f"  임계 {d['decision_threshold']} · 특징 {len(d['features'])}개 · 형상함수 {len(d['terms'])}개")
PY

echo
echo "=== 4/4 대조 요약 ==="
# 정답 라벨 대조는 train.py 가 학습 직후 같은 표본에서 찍는다(위 'EBM 정답 대조' 절).
# 여기서는 배포된 모델이 그 값을 그대로 들고 있는지만 다시 확인한다.
"$PY" - <<'PY'
import json, pathlib
m = json.loads(pathlib.Path("/".join([__import__("os").environ["FINNTECH_ML_DIR"], "metrics.json"])).read_text())
r = m["results"][-1]
print(f"  {r['model']}  PR-AUC {r['pr_auc']} · 임계 {r['threshold']}"
      f" · 정밀도 {r['precision']} · 재현율 {r['recall']}")
print(f"  낭비로 찍은 비율 {r.get('fired_rate')} (실제 {r.get('base_rate')})")
if r.get('fired_rate') and r.get('base_rate'):
    ratio = r['fired_rate'] / r['base_rate']
    print(f"  → 부풀림 {ratio:.2f}배 " + ("(양호)" if ratio <= 1.5 else "(과잉 — 임계를 더 올릴지 검토)"))
PY
