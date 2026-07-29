#!/usr/bin/env bash
# 학습 입력 TSV 3종을 MySQL에서 뽑는다 — 파이프라인 2단계.
#
#   bash ml/dump.sh [출력디렉터리]
#
# 왜 이 파일이 있는가: README가 이 단계를 `mysql ... -e "SELECT ..."` 로만 적어 두어
# **실제 쿼리가 저장소 어디에도 없었다.** 재학습하려면 매번 쿼리를 다시 짜야 했고,
# 그러면 학습 때 쓴 것과 다른 데이터가 들어가도 알 방법이 없다. 재현성이 곧 신뢰다.
#
# 출력(기본 ~/Downloads/finntech-ml/):
#   user_split.tsv   사용자 → 코드·분리군·페르소나
#   card_user.tsv    카드 → 사용자
#   payments.tsv     결제 본체(카드·중분류·금액·라벨·시각)
set -euo pipefail

OUT="${1:-$HOME/Downloads/finntech-ml}"
DB="${MYSQL_DB:-finntech_mydata}"
USER_="${MYSQL_USER:-root}"
HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

mkdir -p "$OUT"
q() { "$MYSQL_BIN" -h "$HOST" -P "$PORT" -u "$USER_" ${MYSQL_PWD:+} --batch --raw "$DB" -e "$1"; }

echo "=== 1/3 user_split.tsv ==="
# data_split은 사용자 단위 disjoint 분리(TRAIN/VAL/TEST/SERVICE). SERVICE는 학습·평가에서 뺀다.
q "SELECT mydata_user_id, mydata_user_data_split, mydata_user_persona
   FROM mydata_user
   WHERE mydata_user_data_split IS NOT NULL
   ORDER BY mydata_user_id;" > "$OUT/user_split.tsv"

echo "=== 2/3 card_user.tsv ==="
q "SELECT mydata_card_id, mydata_user_id
   FROM mydata_card
   ORDER BY mydata_card_id;" > "$OUT/card_user.tsv"

echo "=== 3/3 payments.tsv ==="
# cat2 = **우리 소비 중분류**다. 예전에는 제공자의 소비맥락 52종이었는데, 제공자가 업종코드까지만
# 넘기도록 경계를 바꾸면서 앱이 붙이는 축으로 옮겼다. 학습과 추론이 같은 축을 써야 한다.
#
# 업종코드 → 중분류는 ksic-mid.json이 정하지만 SQL에서 조인할 표가 없으므로,
# 여기서는 원본 두 값을 다 뽑고 train.py가 같은 표로 옮긴다(단일 원천 유지).
#
# discretionary_score는 뽑지 않는다 — 생성 시 p_waste 그 자체라 타깃 누수다.
q "SELECT p.mydata_card_id AS card_id,
          p.mydata_payment_ksic_code AS ksic,
          p.mydata_payment_amount AS amount,
          p.mydata_payment_waste_label AS label,
          p.mydata_payment_date AS dt
   FROM mydata_payment p
   WHERE p.mydata_payment_waste_label IS NOT NULL
   ORDER BY p.mydata_payment_id;" > "$OUT/payments.tsv"

echo
for f in user_split card_user payments; do
  printf "  %-14s %10s행  %s\n" "$f.tsv" \
    "$(( $(wc -l < "$OUT/$f.tsv") - 1 ))" "$(du -h "$OUT/$f.tsv" | cut -f1)"
done
echo "=== 완료 — 다음: python3 ml/train.py ==="
