#!/usr/bin/env bash
# 운영에 반영된 결과를 <b>로컬에서 검증한 것과 대조</b>한다.
#
#   ./scripts/audit-prod.sh
#
# 로컬에서 통과했다고 운영이 같으리라는 보장이 없다. 적재가 일부만 들어갔거나, 배포된 코드가
# 다른 커밋이거나, 환경변수가 빠져 조용히 폴백으로 도는 경우가 실제로 있었다
# (2026-08-04 — 적재가 시작조차 안 됐는데 옛 로그 때문에 '진행 중'으로 보였다).
#
# 그래서 **운영에서 직접 세어** 로컬 기대값과 맞춘다. 어긋나면 ✗ 를 찍고 0이 아닌 코드로 끝난다.
set -uo pipefail
cd "$(dirname "$0")/.."

INSTANCE="${INSTANCE:-i-0caa34b2587168188}"
REGION="${AWS_REGION:-ap-northeast-2}"
BASE="${BASE:-https://moaa.kro.kr}"

fail=0
ok()  { printf '  \033[32m✓\033[0m %-44s %s\n' "$1" "${2:-}"; }
bad() { printf '  \033[31m✗\033[0m %-44s %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

# 운영에서 SQL 한 줄. 결과는 표준출력 그대로.
psql() {
  local q="$1" json cid st
  json=$(python3 scripts/_ssm_sql.py "$q") || return 1
  cid=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
        --parameters "$json" --region "$REGION" --query 'Command.CommandId' --output text 2>/dev/null) || return 1
  for _ in $(seq 1 100); do
    sleep 3
    st=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" \
         --region "$REGION" --query 'Status' --output text 2>/dev/null | head -1)
    case "${st:-Pending}" in Pending|InProgress|Delayed) ;; *) break ;; esac
  done
  aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" --region "$REGION" \
    --query 'StandardOutputContent' --output text 2>/dev/null | tr -d '\r'
}

# 로컬(=기대값)에서 같은 것을 센다.
MYSQL_BIN="${MYSQL_BIN:-$HOME/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql}"
lsql() { "$MYSQL_BIN" --no-defaults -ufinntech -pfinntech --protocol=TCP -h127.0.0.1 -N -B -e "$1" 2>/dev/null; }

# 로컬과 운영을 같은 쿼리로 재서 맞춘다.
cmp_both() {
  local name="$1" q="$2" want got
  want=$(lsql "$q" | tr -d '[:space:]')
  got=$(psql "$q" | tr -d '[:space:]')
  if [ -n "$want" ] && [ "$want" = "$got" ]; then ok "$name" "$got"
  else bad "$name" "로컬 ${want:-?} ≠ 운영 ${got:-?}"; fi
}

# 운영이 로컬보다 **적지 않은가**. 규모 검사는 이쪽을 쓴다.
#
# 예전에는 규모도 `cmp_both`(같은가)로 봤는데, 그건 **시드를 막 올린 그 순간에만 참**이었다.
# 운영에는 실사용자의 명세서가 계속 얹히므로 로컬(생성분만)과 같을 수가 없다 — 실제로
# 결제 10,927,508 대 10,927,800 으로 어긋나 **늘 빨간불**이었고, 늘 실패하는 검사는
# 아무도 안 본다. 물어야 할 것은 "생성분이 온전히 올라갔는가"이지 "똑같은가"가 아니다.
cmp_atleast() {
  local name="$1" q="$2" want got
  want=$(lsql "$q" | tr -d '[:space:]')
  got=$(psql "$q" | tr -d '[:space:]')
  if [ -z "$want" ] || [ -z "$got" ]; then bad "$name" "로컬 ${want:-?} · 운영 ${got:-?}"; return; fi
  if [ "$got" -ge "$want" ] 2>/dev/null; then
    local extra=$((got - want))
    [ "$extra" -eq 0 ] && ok "$name" "$got" || ok "$name" "$got (생성 $want + 실사용자 $extra)"
  else
    bad "$name" "운영이 로컬보다 적다 — 로컬 $want > 운영 $got"
  fi
}

echo "=== 규모가 로컬과 같은가 ==="
cmp_atleast "결제 건수"   "SELECT COUNT(*) FROM finntech_mydata.mydata_payment;"
cmp_atleast "통장거래"    "SELECT COUNT(*) FROM finntech_mydata.mydata_account_txn;"
cmp_atleast "사용자"      "SELECT COUNT(*) FROM finntech_mydata.mydata_user;"
cmp_atleast "가맹점"      "SELECT COUNT(*) FROM finntech_mydata.mydata_merchant;"

echo
echo "=== 계약이 운영에서도 온전한가 ==="
cmp_atleast "구독 서비스 종수" "SELECT COUNT(DISTINCT mydata_payment_merchant_name) FROM finntech_mydata.mydata_payment WHERE mydata_payment_category2='스트리밍';"
n=$(psql "SELECT COUNT(*) FROM (SELECT c.mydata_user_id u FROM finntech_mydata.mydata_card c JOIN finntech_mydata.mydata_payment p ON p.mydata_card_id=c.mydata_card_id WHERE p.mydata_payment_category2='스트리밍' GROUP BY 1 HAVING COUNT(DISTINCT p.mydata_payment_merchant_name) > 10) t;" | tr -d '[:space:]')
[ "${n:-x}" = "0" ] && ok "구독 11곳 이상인 사용자" "0명" || bad "구독 11곳 이상인 사용자" "${n:-조회실패}명"

echo
echo "=== 스키마가 새 코드와 맞는가 ==="
# **기대값을 저장소에서 읽는다.** 예전에는 `15` 를 박아 뒀는데 그 사이 마이그레이션이
# 22개 늘어 늘 빨간불이었다. 상수로 두면 반드시 낡는다.
want_v=$(ls backend/src/main/resources/db/migration/V*__*.sql 2>/dev/null \
         | sed 's#.*/V##; s#__.*##' | sort -n | tail -1)
v=$(psql "SELECT MAX(CAST(version AS UNSIGNED)) FROM finntech.flyway_schema_history WHERE success=1;" | tr -d '[:space:]')
if [ -n "$want_v" ] && [ "${v:-0}" = "$want_v" ]; then ok "Flyway 최신 버전" "v$v (저장소와 같다)"
else bad "Flyway 최신 버전" "운영 v${v:-?} · 저장소 v${want_v:-?}"; fi
f=$(psql "SELECT COUNT(*) FROM finntech.flyway_schema_history WHERE success=0;" | tr -d '[:space:]')
[ "${f:-x}" = "0" ] && ok "실패한 마이그레이션" "0건" || bad "실패한 마이그레이션" "${f:-?}건"
c=$(psql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='finntech' AND TABLE_NAME='user_payment' AND COLUMN_NAME IN ('category2_llm','category2_source');" | tr -d '[:space:]')
[ "${c:-0}" = "2" ] && ok "user_payment 새 칸 2개" || bad "user_payment 새 칸" "${c:-?}개"

echo
echo "=== 배포된 코드가 이 커밋인가 ==="
# 서버 저장소는 root 소유라 git 이 'dubious ownership' 으로 거부한다. 소유권을 건드리지 않고
# safe.directory 를 그 명령에만 붙여 읽는다.
#
# **git HEAD 만 보면 안 된다.** 배포는 `git checkout` 뒤에 이미지를 다시 굽고 컨테이너를 갈아
# 끼우는데, 그 사이에는 **코드는 새것인데 도는 것은 옛것**이다. 실제로 그 구간에서 이 검사가
# 초록불이었고, 곧바로 부른 API 가 502 였다(2026-08-05). 그래서 컨테이너가 코드보다 나중에
# 떴는지도 함께 본다 — 배포의 성립 조건은 '받았다'가 아니라 '그것이 돌고 있다'이다.
sha=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
      --parameters '{"commands":["git -c safe.directory=/opt/finntech/app -C /opt/finntech/app rev-parse --short HEAD"]}' \
      --region "$REGION" --query 'Command.CommandId' --output text 2>/dev/null)
for _ in $(seq 1 20); do
  sleep 3
  st=$(aws ssm get-command-invocation --command-id "$sha" --instance-id "$INSTANCE" \
       --region "$REGION" --query 'Status' --output text 2>/dev/null || echo Pending)
  case "$st" in Pending|InProgress|Delayed) ;; *) break ;; esac
done
got=$(aws ssm get-command-invocation --command-id "$sha" --instance-id "$INSTANCE" --region "$REGION" \
      --query 'StandardOutputContent' --output text 2>/dev/null | tr -d '[:space:]')
want=$(git rev-parse --short origin/main 2>/dev/null | tr -d '[:space:]')
[ -n "$got" ] && [ "$got" = "$want" ] && ok "배포 커밋" "$got" || bad "배포 커밋" "운영 ${got:-?} ≠ main ${want:-?}"

# 4서비스가 전부 healthy 인가 — 재빌드 중이면 backend 가 통째로 빠져 있다.
cid=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
      --parameters '{"commands":["docker ps --format \"{{.Names}}|{{.Status}}\" | grep -c healthy"]}' \
      --region "$REGION" --query 'Command.CommandId' --output text 2>/dev/null)
for _ in $(seq 1 20); do
  sleep 3
  st=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" \
       --region "$REGION" --query 'Status' --output text 2>/dev/null || echo Pending)
  case "$st" in Pending|InProgress|Delayed) ;; *) break ;; esac
done
n=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" --region "$REGION" \
    --query 'StandardOutputContent' --output text 2>/dev/null | tr -d '[:space:]')
[ "${n:-0}" -ge 4 ] 2>/dev/null && ok "컨테이너 healthy" "${n}/4" || bad "컨테이너 healthy" "${n:-?}/4 — 배포가 아직 도는 중일 수 있다"

echo
echo "=== 앱이 실제로 답하는가 ==="
for p in /actuator/health /api/privacy/policy; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$BASE$p" || echo 000)
  [ "$code" = "200" ] && ok "GET $p" "$code" || bad "GET $p" "$code"
done

echo
if [ "$fail" -eq 0 ]; then printf '\033[32m운영이 로컬 검증과 일치한다\033[0m\n'
else printf '\033[31m%d개 항목이 어긋난다\033[0m\n' "$fail"; fi
exit "$fail"
