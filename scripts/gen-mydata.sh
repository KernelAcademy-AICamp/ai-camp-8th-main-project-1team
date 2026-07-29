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
  # 있는 테이블만 비운다. 새 마이그레이션이 도입한 테이블(mydata_account_txn 등)은 아직
  # Flyway가 안 돌았을 수 있는데, 없는 테이블을 TRUNCATE 하면 스크립트가 통째로 멈춘다.
  # 반대로 목록에서 빼면 옛 데이터가 새 계좌를 참조한 채 남는다(FK를 꺼 두어 조용히 통과).
  for t in mydata_account_txn mydata_payment mydata_card mydata_account mydata_user mydata_merchant; do
    exists="$(mysql_q "SELECT COUNT(*) FROM information_schema.tables
                       WHERE table_schema='$DB_NAME' AND table_name='$t';")"
    if [[ "${exists:-0}" -gt 0 ]]; then
      mysql_q "SET FOREIGN_KEY_CHECKS=0; TRUNCATE $t; SET FOREIGN_KEY_CHECKS=1;"
      echo "  비움: $t"
    else
      echo "  건너뜀(아직 없음): $t"
    fi
  done
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

# ── 3-B) 후처리까지 기다린다 ──
# 결제 삽입이 멈춰도 생성기는 아직 끝난 게 아니다 — mydata_merchant 집계(결제 전량 스캔)와
# 정리 CSV 쓰기가 남아 있다. 예전에는 이 대기가 없어서 아래 4단계 충돌 정리가 집계와 **동시에**
# 돌았고, 그러면 집계가 지워질 결제를 담거나 충돌 삭제가 집계보다 먼저 끝나 가맹점 표에
# 충돌 번호가 남았다. 결제가 1,000만을 넘으며 집계가 길어지자 실제로 겹쳤다.
echo "[gen-mydata] 가맹점 집계·후처리 대기…"
for _ in $(seq 1 240); do            # 최대 60분
  grep -q '\[generation\] 고유 가맹점' /tmp/gen-mydata.log && break
  kill -0 "$GEN_PID" 2>/dev/null || break     # 생성기가 죽었으면 더 기다릴 것이 없다
  sleep 15
done
# 집계 로그가 떴어도 CSV 쓰기가 남을 수 있으니, 관련 쿼리가 완전히 빠질 때까지 한 번 더 확인한다.
for _ in $(seq 1 60); do
  # id<>CONNECTION_ID() 가 없으면 **이 쿼리가 자기를 센다** — 쿼리문 안에 'mydata_merchant'가
  # 들어 있어 LIKE 에 자기 자신이 걸린다. 그러면 busy 가 0이 될 수 없어 대기가 안 풀린다.
  busy="$(mysql_q "SELECT COUNT(*) FROM information_schema.processlist
                   WHERE command<>'Sleep' AND info IS NOT NULL
                     AND id<>CONNECTION_ID() AND info LIKE '%mydata_merchant%';")"
  [[ "${busy:-0}" -eq 0 ]] && break
  sleep 5
done

# ── 4) 충돌 정리(사업자번호 → 주소 유일성 보장) ──
# 10자리 번호 공간의 순수 해시충돌로 같은 번호가 서로 다른 주소를 갖는 건을 제거(README 규칙).
# 정리는 사업자번호로 조인한다 — 인덱스가 없으면 1,000만 행을 통째로 훑어 사실상 끝나지 않는다
# (실측: 8분간 0행 삭제. 인덱스를 만드니 27초 생성 + 3분 삭제로 끝났다).
# 생성 중에는 이 인덱스가 없는 편이 낫다(삽입마다 갱신 비용) — 그래서 여기서 만든다.
echo "[gen-mydata] 정리용 인덱스 생성…"
mysql_q "CREATE INDEX idx_mydata_payment_bizno
         ON mydata_payment (mydata_payment_business_number);" 2>/dev/null   || echo "  (이미 있음 — 건너뜀)"

echo "[gen-mydata] 충돌 정리(다중주소 사업자번호 제거)…"
# 통장의 카드 출금은 결제의 **사본**이다. 결제만 지우면 통장에 그 출금만 남아 둘이 갈라진다
# — 사본을 결제ID로 묶어 두었으므로 같은 조건으로 함께 지운다.
mysql_q "
  CREATE TEMPORARY TABLE _collide AS
    SELECT mydata_payment_business_number AS bn
    FROM mydata_payment
    WHERE mydata_payment_business_number IS NOT NULL
    GROUP BY mydata_payment_business_number
    HAVING COUNT(DISTINCT mydata_payment_location_address) > 1;
  CREATE TEMPORARY TABLE _dead AS
    SELECT p.mydata_payment_id AS pid FROM mydata_payment p
    JOIN _collide c ON p.mydata_payment_business_number = c.bn;
  DELETE t FROM mydata_account_txn t JOIN _dead d ON t.mydata_account_txn_payment_id = d.pid;
  DELETE p FROM mydata_payment p JOIN _collide c ON p.mydata_payment_business_number = c.bn;
  DELETE m FROM mydata_merchant  m JOIN _collide c ON m.business_number = c.bn;
  DROP TEMPORARY TABLE _dead;
  DROP TEMPORARY TABLE _collide;"

# ── 5) 종료 + 검증 ──
kill "$GEN_PID" 2>/dev/null || true
final="$(count_payments)"
collide="$(mysql_q "SELECT COUNT(*) FROM (SELECT mydata_payment_business_number FROM mydata_payment
             WHERE mydata_payment_business_number IS NOT NULL
             GROUP BY mydata_payment_business_number
             HAVING COUNT(DISTINCT mydata_payment_location_address)>1) t;")"
# 결제와 통장 사본이 1:1인지 확인한다. 정리 단계가 한쪽만 지웠으면 여기서 드러난다.
txns="$(mysql_q "SELECT COUNT(*) FROM mydata_account_txn;")"
orphan="$(mysql_q "SELECT COUNT(*) FROM mydata_account_txn t
             LEFT JOIN mydata_payment p ON p.mydata_payment_id = t.mydata_account_txn_payment_id
             WHERE t.mydata_account_txn_source='CARD' AND p.mydata_payment_id IS NULL;")"
echo "[gen-mydata] 완료: mydata_payment=${final}, 통장거래=${txns}, 다중주소 사업자번호=${collide} (0이어야 정상)"
echo "[gen-mydata] 결제 없는 통장 카드출금=${orphan} (0이어야 정상)"
echo "[gen-mydata] 다음: ./scripts/run-stack-mysql.sh 로 풀스택 기동."
