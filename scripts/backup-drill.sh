#!/usr/bin/env bash
# 백업 복원 리허설 — **서버에서 실행한다** (DoD 9).
#
#   bash /opt/finntech/app/scripts/backup-drill.sh
#
# 왜 필요한가: 계획서 표현대로 **복원해 보기 전까지는 백업이 아니다**. 덤프 파일이 생겼다는 것과
# 그것으로 서비스를 되살릴 수 있다는 것은 다른 얘기다. 여기서 실제로 되살려 본다.
#
# 왜 `dump-mydata.sh`를 그대로 쓰지 않는가: 그 스크립트는 호스트에 mysqldump가 있는 로컬 전제다.
# 서버는 MySQL이 컨테이너 안에 있고 호스트에 클라이언트가 없어 `docker exec`로 우회한다.
# 덤프 옵션은 그 스크립트에서 그대로 가져왔다(둘이 갈라지면 안 된다).
#
# 안전장치
#   · 운영 DB는 **읽기만** 한다. 복원은 이름이 다른 임시 컨테이너·임시 DB에서만 이뤄진다.
#   · 임시 컨테이너는 포트를 발행하지 않는다 — 리허설이 외부에 열리면 안 된다.
#   · 끝나면 임시 컨테이너·볼륨·덤프를 지운다(--keep 을 주면 남긴다).
set -euo pipefail

APP=/opt/finntech/app
ENVFILE=/opt/finntech/.env
PROD_DB=app-mysql-1
DRILL=finntech-drill
OUT=${OUT:-/tmp/backup-drill}
KEEP=${1:-}

set -a; . "$ENVFILE"; set +a
mkdir -p "$OUT"

cleanup() {
  [ "$KEEP" = "--keep" ] && { echo "  (--keep) 임시 컨테이너·덤프를 남긴다: $DRILL · $OUT"; return; }
  docker rm -f "$DRILL" >/dev/null 2>&1 || true
  docker volume rm "${DRILL}-data" >/dev/null 2>&1 || true
  rm -rf "$OUT"
  echo "  임시 자원 정리 완료"
}
trap cleanup EXIT

echo "=== 1/5 덤프 ==="
# 옵션은 scripts/dump-mydata.sh 와 같아야 한다 — 한쪽만 고치면 리허설이 실물과 달라진다.
DUMP_OPTS="--single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4"
for db in finntech finntech_mydata; do
  t0=$(date +%s)
  docker exec "$PROD_DB" sh -lc "mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $DUMP_OPTS $db" \
    | gzip -1 > "$OUT/$db.sql.gz"
  echo "  $db → $(du -h "$OUT/$db.sql.gz" | cut -f1) ($(( $(date +%s) - t0 ))초)"
done

echo "=== 2/5 임시 MySQL 기동 ==="
docker rm -f "$DRILL" >/dev/null 2>&1 || true
docker volume rm "${DRILL}-data" >/dev/null 2>&1 || true
# 포트를 발행하지 않는다. 같은 도커 망에 붙여 백엔드가 이름으로 찾아갈 수 있게만 한다.
NET=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$PROD_DB" | head -1)
docker run -d --name "$DRILL" --network "$NET" \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -v "${DRILL}-data:/var/lib/mysql" \
  mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null
for i in $(seq 1 60); do
  docker exec "$DRILL" mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" >/dev/null 2>&1 && break
  sleep 2
done
echo "  기동 확인"

echo "=== 3/5 복원 ==="
for db in finntech finntech_mydata; do
  t0=$(date +%s)
  docker exec -i "$DRILL" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
    -e "create database if not exists $db character set utf8mb4 collate utf8mb4_unicode_ci;"
  gunzip -c "$OUT/$db.sql.gz" | docker exec -i "$DRILL" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$db"
  n=$(docker exec "$DRILL" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$db" \
        -e "select count(*) from information_schema.tables where table_schema='$db';" 2>/dev/null)
  echo "  $db 복원 완료 — 테이블 ${n}개 ($(( $(date +%s) - t0 ))초)"
done

echo "=== 4/5 복원본으로 기동 (validate) ==="
# 백엔드를 복원 DB만 바라보게 띄운다. 스키마와 엔티티가 어긋나면 여기서 죽는다 —
# 덤프가 '열리는지'가 아니라 '서비스가 뜨는지'를 봐야 백업이라 할 수 있다.
docker run --rm --network "$NET" \
  -e SPRING_PROFILES_ACTIVE=mysql -e DB_HOST="$DRILL" -e DB_PORT=3306 \
  -e DB_NAME=finntech -e DB_USER=root -e DB_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e MYDATA_BASE_URL=http://backend-mydata:8082 -e MYDATA_SHARED_SECRET="$MYDATA_SHARED_SECRET" \
  -e TSA_ENABLED=false \
  app-backend java -jar /app/app.jar --server.port=8099 > "$OUT/boot.log" 2>&1 &
BOOT=$!
ok=no
for i in $(seq 1 90); do
  grep -q "Started BackendApplication" "$OUT/boot.log" 2>/dev/null && { ok=yes; break; }
  grep -qE "Application run failed|APPLICATION FAILED" "$OUT/boot.log" 2>/dev/null && break
  sleep 2
done
if [ "$ok" = yes ]; then
  echo "  validate 통과 · 기동 성공"
else
  echo "  ✗ 기동 실패 — 아래 원인"
  grep -iE "Caused by|Schema validation" "$OUT/boot.log" | head -3 | sed 's/^/    /'
fi
kill $BOOT 2>/dev/null || true

echo "=== 5/5 감사로그 무결 ==="
# 감사 사슬은 재생성으로 되살릴 수 없다(체인·TSA 앵커가 달라진다). 복원본에서 직접 검증한다.
docker exec "$DRILL" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B finntech -e "
  select concat('  감사 항목 ', count(*), '건 · 배치 ',
    (select count(*) from audit_batch), '건') from audit_log;" 2>/dev/null

[ "$ok" = yes ] || exit 1
echo "=== 리허설 통과 ==="
