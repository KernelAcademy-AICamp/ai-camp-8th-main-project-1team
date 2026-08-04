#!/usr/bin/env bash
# 재생성 뒤 앱 사용자 재연동 — **CI 재배정 → birth_year 갱신 → 연동** 순서를 코드로 고정한다.
#
#   BASE=https://moaa.kro.kr ./scripts/relink-app-users.sh --dry-run   # 무엇을 할지만 보여준다
#   BASE=https://moaa.kro.kr ./scripts/relink-app-users.sh             # 실제 실행
#
# ── 왜 스크립트인가 ─────────────────────────────────────────────────────────
# 이 절차는 두 번 사고를 냈다.
#   · 2026-07-30 — CI 가 끊긴 채로 `POST /api/mydata/link` 를 불러 userId=2 의 801행이 사라졌다.
#     linkCardCompanies 는 **먼저 지우고 나중에 채운다**. CI 가 안 맞으면 삭제만 되고 0건이 들어온다.
#   · 2026-07-31 — CI 만 SQL 로 옮기고 birth_year 를 안 옮겨 12명 중 11명의 생년이 어긋났다.
#     금융상품 나이 자격 판정이 그 값을 본다.
#
# 그래서 **연동 전에 반드시 CI 가 원장에 있는지 센다.** 하나라도 0이면 연동을 시작하지 않는다.
set -uo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-https://moaa.kro.kr}"
INSTANCE="${INSTANCE:-i-0caa34b2587168188}"
REGION="${AWS_REGION:-ap-northeast-2}"
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1

# 서버에서 SQL 한 덩어리를 돌리고 표준출력을 그대로 돌려준다.
remote_sql() {
  local sql="$1"
  local payload
  payload=$(python3 - "$sql" <<'PY'
import json, sys
sql = sys.argv[1]
script = [
  "set -a; . /opt/finntech/.env; set +a",
  "docker exec -i app-mysql-1 mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" "
  "--default-character-set=utf8mb4 -N -B <<'SQLEOF' 2>/dev/null",
  sql,
  "SQLEOF",
]
print(json.dumps({"commands": script}))
PY
)
  local id
  id=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
        --parameters "$payload" --region "$REGION" --query 'Command.CommandId' --output text) || return 1
  for _ in $(seq 1 60); do
    local s
    s=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE" \
          --region "$REGION" --query 'Status' --output text 2>/dev/null || echo Pending)
    case "$s" in Pending|InProgress|Delayed) sleep 5;; *) break;; esac
  done
  aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE" --region "$REGION" \
    --query 'StandardOutputContent' --output text
  # 표준오류도 함께 본다 — 삼키면 SQL 실패가 '0명'으로 보인다(2026-08-04 실측:
  # CREATE TEMPORARY TABLE 이 'No database selected' 로 죽었는데 재배정 0명으로만 보였다).
  local err
  err=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE" \
        --region "$REGION" --query 'StandardErrorContent' --output text 2>/dev/null)
  if [ -n "$err" ] && [ "$err" != "None" ]; then
    printf '  \033[31mSQL 오류\033[0m %s\n' "$err" >&2
  fi
}

echo "━━ 1/5 현재 신원 상태 ━━"
remote_sql "SELECT CONCAT('  ', LPAD(a.id,3,' '), '  ', RPAD(IFNULL(m.mydata_user_name,'(원장에 없음)'),8,' '), '  결제 ',
       FORMAT((SELECT COUNT(*) FROM finntech.user_payment p WHERE p.user_id=a.id),0))
  FROM finntech.app_user a
  LEFT JOIN finntech_mydata.mydata_user m ON m.mydata_user_id=a.ci
 ORDER BY a.id;"

echo
echo "━━ 2/5 CI 재배정 (원장에 없는 사용자에게 아직 아무도 안 쓴 사람을 id 순으로) ━━"
# 결정론: app_user.id 순 ↔ 카드 4장 이상인 미사용 mydata_user 를 id 순으로 짝짓는다.
REASSIGN="
-- 기본 DB 를 정하지 않으면 CREATE TEMPORARY TABLE 이 'No database selected'(1046)로 죽는다.
-- mysql 을 DB 인자 없이 부르기 때문이다. 2026-08-04 에 이것 때문에 재배정이 조용히 0명이었다.
USE finntech;
SET @rank := 0;
CREATE TEMPORARY TABLE _free AS
  SELECT mydata_user_id, (@rank := @rank + 1) AS rn
    FROM (SELECT u.mydata_user_id
            FROM finntech_mydata.mydata_user u
            JOIN finntech_mydata.mydata_card c ON c.mydata_user_id = u.mydata_user_id
           WHERE u.mydata_user_id NOT IN (SELECT ci FROM finntech.app_user WHERE ci IS NOT NULL)
           GROUP BY u.mydata_user_id HAVING COUNT(*) >= 4
           ORDER BY u.mydata_user_id) x;
SET @rank := 0;
CREATE TEMPORARY TABLE _need AS
  SELECT id, (@rank := @rank + 1) AS rn FROM (
    SELECT a.id FROM finntech.app_user a
     WHERE a.ci IS NULL OR NOT EXISTS (SELECT 1 FROM finntech_mydata.mydata_user m
                                        WHERE m.mydata_user_id = a.ci)
     ORDER BY a.id) y;
UPDATE finntech.app_user a JOIN _need n ON n.id = a.id JOIN _free f ON f.rn = n.rn
   SET a.ci = f.mydata_user_id;
SELECT CONCAT('재배정 ', ROW_COUNT(), '명');
"
if [ "$DRY" = "1" ]; then echo "  (--dry-run: 건너뜀)"; else remote_sql "$REASSIGN"; fi

echo
echo "━━ 3/5 birth_year 를 신원에 맞춘다 ━━"
BIRTH="
UPDATE finntech.app_user a
  JOIN finntech_mydata.mydata_user m ON m.mydata_user_id = a.ci
   SET a.birth_year = CASE SUBSTR(m.mydata_user_social_number,7,1)
                        WHEN '1' THEN 1900 WHEN '2' THEN 1900 WHEN '5' THEN 1900 WHEN '6' THEN 1900
                        WHEN '3' THEN 2000 WHEN '4' THEN 2000 WHEN '7' THEN 2000 WHEN '8' THEN 2000
                        ELSE 1800 END
                      + CAST(SUBSTR(m.mydata_user_social_number,1,2) AS UNSIGNED),
       a.consent_given = 1;
SELECT CONCAT('생년 갱신 ', ROW_COUNT(), '명');
"
if [ "$DRY" = "1" ]; then echo "  (--dry-run: 건너뜀)"; else remote_sql "$BIRTH"; fi

echo
echo "━━ 4/5 안전 확인 — CI 가 원장에 없는 사용자가 하나라도 있으면 중단 ━━"
MISSING=$(remote_sql "SELECT COUNT(*) FROM finntech.app_user a
  WHERE a.ci IS NULL OR NOT EXISTS (SELECT 1 FROM finntech_mydata.mydata_user m
                                     WHERE m.mydata_user_id = a.ci);" | tr -d '[:space:]')
echo "  원장에 없는 사용자: ${MISSING:-확인실패}명"
if [ "${MISSING:-1}" != "0" ]; then
  echo "  ✗ 연동을 시작하지 않는다 — 이대로 부르면 결제가 통째로 삭제된다(2026-07-30 실측)."
  exit 1
fi
echo "  ✓ 전원 신원 확보"

echo
echo "━━ 5/5 재연동 ━━"
if [ "$DRY" = "1" ]; then echo "  (--dry-run: 건너뜀)"; exit 0; fi
C=$(curl -s -m 30 "$BASE/api/mydata/companies" | python3 -c "import json,sys; print(json.dumps([c['id'] for c in json.load(sys.stdin)]))")
B=$(curl -s -m 30 "$BASE/api/mydata/banks"     | python3 -c "import json,sys; print(json.dumps([b['id'] for b in json.load(sys.stdin)]))")
IDS=$(remote_sql "SELECT id FROM finntech.app_user ORDER BY id;" | tr -d '\r')
while read -r uid; do
  [ -n "$uid" ] || continue
  printf '  userId=%-4s ' "$uid"
  curl -s -m 900 -X POST "$BASE/api/mydata/link" -H 'Content-Type: application/json' \
    -d "{\"userId\":$uid,\"companyIds\":$C,\"bankIds\":$B}" | head -c 200
  echo
done <<< "$IDS"
