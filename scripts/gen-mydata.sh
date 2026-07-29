#!/usr/bin/env bash
# 마이데이터(mydata_*) 결정론 재생성 — 팀원 새 머신에서 개발자와 동일 규모 데이터를 재현한다.
# 데이터를 git에 올리지 않고(≈2GB) seed 20260721로 재생성하는 방식(재현성 문서: docs/RUNBOOK.md).
#
#   전제: MySQL(3306)에 finntech_mydata DB + finntech 유저가 있고, 루트에 .env 가 채워져 있을 것.
#   사용:  ./scripts/gen-mydata.sh            (빈 DB면 생성. 데이터가 이미 있으면 중단)
#          FORCE=1 ./scripts/gen-mydata.sh    (기존 mydata_* 를 비우고 재생성 — 주의: 파괴적)
#          TARGET_COUNT=10000000 ./scripts/gen-mydata.sh   (규모 조정)
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG="${LANG:-en_US.UTF-8}" LC_ALL="${LC_ALL:-en_US.UTF-8}"

# ── .env 주입 (Spring은 .env 자동 로드 안 함) ──
if [[ -f .env ]]; then set -a; # shellcheck disable=SC1091
  source .env; set +a; fi

# ── 파라미터(전부 오버라이드 가능) ──
MYSQL_BIN="${MYSQL_BIN:-$HOME/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql}"
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-finntech}"; DB_PASSWORD="${DB_PASSWORD:-finntech}"
DB_NAME="${MYDATA_DB_NAME:-finntech_mydata}"
SEED="${SEED:-20260721}"                       # 결정론 시드(고정)
HISTORY_DAYS="${HISTORY_DAYS:-280}"            # 사용자별 이력 일수(고정)
TARGET_COUNT="${TARGET_COUNT:-10000000}"       # 결제 상한 ≈ 규모. 개발자 참조본은 ~9.94M.
SHARED_SECRET="${MYDATA_SHARED_SECRET:-demo-mydata-shared-2026}"
JDBC="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false"

mysql_q() { "$MYSQL_BIN" --no-defaults -u"$DB_USER" -p"$DB_PASSWORD" --protocol=TCP \
              -h"$DB_HOST" -P"$DB_PORT" "$DB_NAME" -N -B -e "$1" 2>/dev/null; }
count_payments() { mysql_q "SELECT COUNT(*) FROM mydata_payment;" 2>/dev/null || echo 0; }

echo "[gen-mydata] DB=$DB_NAME seed=$SEED history-days=$HISTORY_DAYS target-count=$TARGET_COUNT"

# ── 0) 안전 가드: 데이터가 있으면 FORCE 없이는 중단(라이브 보호) ──
existing="$(count_payments)"; existing="${existing:-0}"
if [[ "$existing" -gt 0 ]]; then
  if [[ "${FORCE:-0}" != "1" ]]; then
    echo "[중단] mydata_payment 에 이미 ${existing}건 존재. 재생성하려면 FORCE=1 로 다시 실행(기존 데이터 삭제)." >&2
    exit 1
  fi
  echo "[FORCE] 기존 mydata_* 를 비운다(${existing}건)…"
  mysql_q "SET FOREIGN_KEY_CHECKS=0;
           TRUNCATE mydata_payment; TRUNCATE mydata_card; TRUNCATE mydata_account;
           TRUNCATE mydata_user; TRUNCATE mydata_merchant;
           SET FOREIGN_KEY_CHECKS=1;"
fi

# ── 1) backend-mydata 빌드 ──
echo "[gen-mydata] backend-mydata 빌드…"
(cd backend-mydata && ./mvnw -B -q -DskipTests package)
JAR="backend-mydata/target/backend-mydata-0.0.1-SNAPSHOT.jar"

# ── 2) 생성 기동(생성기 ON) ──
echo "[gen-mydata] 생성기 기동(백그라운드, 로그 /tmp/gen-mydata.log)…"
DB_USER="$DB_USER" DB_PASSWORD="$DB_PASSWORD" MYDATA_DB_NAME="$DB_NAME" \
MYDATA_SHARED_SECRET="$SHARED_SECRET" \
nohup java -jar "$JAR" \
  --spring.profiles.active=mysql --server.port=8085 \
  --mydata.seed.enabled=false --mydata.generation.enabled=true \
  --mydata.generation.seed="$SEED" \
  --mydata.generation.history-days="$HISTORY_DAYS" \
  --mydata.generation.target-count="$TARGET_COUNT" \
  "--spring.datasource.url=$JDBC" \
  > /tmp/gen-mydata.log 2>&1 < /dev/null &
GEN_PID=$!

# ── 3) 완료 대기(행수가 멈출 때까지) ──
echo "[gen-mydata] 생성 진행 대기…"
prev=-1; stable=0
while kill -0 "$GEN_PID" 2>/dev/null; do
  sleep 15
  cur="$(count_payments)"; cur="${cur:-0}"
  echo "  mydata_payment=$cur"
  if [[ "$cur" -gt 0 && "$cur" == "$prev" ]]; then
    stable=$((stable+1)); [[ "$stable" -ge 2 ]] && break
  else stable=0; fi
  prev="$cur"
done

# ── 4) 충돌 정리(사업자번호 → 주소 유일성 보장) ──
# 10자리 번호 공간의 순수 해시충돌로 같은 번호가 서로 다른 주소를 갖는 건을 제거(README 규칙).
echo "[gen-mydata] 충돌 정리(다중주소 사업자번호 제거)…"
mysql_q "
  CREATE TEMPORARY TABLE _collide AS
    SELECT mydata_payment_business_number AS bn
    FROM mydata_payment
    WHERE mydata_payment_business_number IS NOT NULL
    GROUP BY mydata_payment_business_number
    HAVING COUNT(DISTINCT mydata_payment_location_address) > 1;
  DELETE p FROM mydata_payment p JOIN _collide c ON p.mydata_payment_business_number = c.bn;
  DELETE m FROM mydata_merchant  m JOIN _collide c ON m.business_number = c.bn;
  DROP TEMPORARY TABLE _collide;"

# ── 5) 종료 + 검증 ──
kill "$GEN_PID" 2>/dev/null || true
final="$(count_payments)"
collide="$(mysql_q "SELECT COUNT(*) FROM (SELECT mydata_payment_business_number FROM mydata_payment
             WHERE mydata_payment_business_number IS NOT NULL
             GROUP BY mydata_payment_business_number
             HAVING COUNT(DISTINCT mydata_payment_location_address)>1) t;")"
echo "[gen-mydata] 완료: mydata_payment=${final}, 다중주소 사업자번호=${collide} (0이어야 정상)"
echo "[gen-mydata] 다음: ./scripts/run-stack-mysql.sh 로 풀스택 기동."
