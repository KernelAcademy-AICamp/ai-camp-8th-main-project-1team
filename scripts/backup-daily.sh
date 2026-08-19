#!/usr/bin/env bash
# 되돌릴 수 없는 데이터를 매일 뜬다 — **서버에서 systemd 타이머가 부른다.**
#
#   bash /opt/finntech/app/scripts/backup-daily.sh
#
# ## 왜 만들었나
#
# 2026-08-18 에 확인해 보니 **자동 백업이 하나도 없었다.** cron 에는 certbot·e2scrub·dpkg 뿐이고
# S3 에도 없다 — `upload-dump-aws.sh` 는 버킷을 만들어 올린 뒤 **끝나면 그 버킷을 지운다**
# (전송 수단이지 보관소가 아니다). 서버에 남아 있던 10MB 이상 덤프 넷은 전부 7월에 손으로 뜬
# **더미 DB** 것이었다. 즉 실사용자 데이터의 백업이 세상에 없었다.
#
# ## 무엇을 뜨고 무엇을 안 뜨나 — 이것이 이 스크립트의 전부다
#
#   ✅ finntech 통째                     실사용자 데이터 전부. 실측 1.5 MB(gz)
#   ✅ finntech_mydata.mydata_user       실사람 신원(암호문). 전체를 뜬다 — 4,513행으로 작고,
#                                        누가 실물인지 고르는 조건이 데이터에 없다
#   ✅ finntech_mydata.mydata_card       실사람 카드. 같은 이유로 전체(22,348행)
#   ✅ finntech_mydata.mydata_payment    **`real-` 접두만.** 실사람 명세서다(실측 1,042행)
#
#   ❌ 생성 결제 1,092만 건              `scripts/gen-mydata.sh` 로 다시 만들 수 있다.
#                                        매일 1.1 GB 를 뜨는 것은 값을 못 한다
#   ❌ mydata_account_txn 1,279만 건     같은 이유(생성물)
#
# **되돌릴 수 없는 것만 뜬다**가 기준이다. 다시 만들 수 있는 것은 백업이 아니라 생성 절차가 지킨다.
# 합쳐 하루 수 MB 라 14벌을 남겨도 100 MB 를 안 넘는다.
#
# ## 덤프 옵션은 가져다 쓴다
#
# `dump-mydata.sh`·`backup-drill.sh` 와 **같은 옵션**이어야 한다. 갈라지면 복원 리허설이 통과한
# 덤프와 실제 백업이 다른 물건이 된다. 고칠 일이 생기면 세 곳을 함께 고친다.
#
# ## S3 — 붙기 전까지는 절반짜리다
#
# `BACKUP_S3_BUCKET` 를 주면 올린다. **2026-08-19 부터 운영에 붙어 있다** —
# 버킷 `finntech-backup-996270854767`, systemd 서비스 파일의 `Environment=` 로 주입한다.
# 호스트에 `aws` CLI 가 없으므로 이미 받아져 있는 `amazon/aws-cli` 이미지를 쓴다.
#
# 로컬 사본만으로는 절반이다 — 디스크가 통째로 날아가는 경우가 백업이 막아야 할 대표적인
# 경우인데 그때 로컬 사본도 함께 사라진다. 그래서 값이 비면 건너뛰되 로그에 그 사실을 남긴다.
#
# 인스턴스 역할(`finntech-ec2-ssm`)에 준 권한은 그 버킷에 대한 **`s3:PutObject` 하나뿐**이다.
# 읽기도 삭제도 없다 — 서버가 뚫려도 백업을 지우거나 훔쳐 갈 수 없다. 랜섬웨어가 제일 먼저
# 노리는 것이 백업이다. 버킷은 버저닝을 켜 뒀고(덮어써도 옛 버전 7일) 30일 뒤 수명주기가 지운다.
set -uo pipefail

DB_CONTAINER="${DB_CONTAINER:-app-mysql-1}"
OUT_DIR="${BACKUP_DIR:-/opt/finntech/backup}"
KEEP="${BACKUP_KEEP:-14}"
S3_BUCKET="${BACKUP_S3_BUCKET:-}"
REGION="${AWS_REGION:-ap-northeast-2}"

# 세 스크립트가 공유하는 옵션. 여기만 고치면 안 된다(머리말 참조).
DUMP_OPTS='--single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4'

STAMP=$(date +%Y%m%d-%H%M)
APP_OUT="$OUT_DIR/finntech-$STAMP.sql.gz"
PROVIDER_OUT="$OUT_DIR/finntech_mydata-real-$STAMP.sql.gz"

log()  { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { log "::실패:: $*"; exit 1; }

mkdir -p "$OUT_DIR" || fail "$OUT_DIR 를 만들 수 없다"

# 디스크가 없으면 덤프가 반쯤 쓰이다 끊긴다 — **그 반쪽이 정상 백업처럼 보이는 것이 제일 나쁘다.**
# 넉넉히 200 MB 를 요구한다(실측 하루 수 MB).
avail=$(df -Pk "$OUT_DIR" | awk 'NR==2 {print $4}')
[ "${avail:-0}" -ge 204800 ] || fail "여유 공간이 ${avail}KB 뿐이다 — 백업을 시작하지 않는다"

# 컨테이너 안에서 돌릴 mysqldump 한 줄을 만든다. 비밀번호는 컨테이너가 이미 들고 있는
# 환경변수에서 꺼내므로 **호스트 명령줄에도 프로세스 목록에도 안 남는다**(`$` 를 그대로 넘긴다).
MYSQLDUMP='mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" '"$DUMP_OPTS"

# 떴다는 것과 쓸 수 있다는 것은 다르다 — gzip 무결성과 최소 크기까지 본다.
# mysqldump 가 중간에 죽으면 잘린 gz 가 남는데, 파일 존재만 보면 그것이 통과해 버린다.
verify() {
  local out="$1" what="$2"
  gzip -t "$out" 2>/dev/null || { rm -f "$out"; fail "$what 덤프가 깨졌다(gzip)"; }
  local bytes; bytes=$(stat -c %s "$out" 2>/dev/null || stat -f %z "$out" 2>/dev/null)
  [ "${bytes:-0}" -ge 1024 ] || { rm -f "$out"; fail "$what 덤프가 너무 작다(${bytes}B)"; }
  # **끝까지 쓰였는지**를 본다. gzip -t 는 gz 스트림이 잘린 것만 잡는데, mysqldump 가
  # 중간에 죽으면 *온전한 gz 안에 반쪽 덤프*가 들어가고 그건 통과해 버린다. mysqldump 는
  # 정상 종료할 때만 마지막 줄에 완료 표시를 남기므로 그것을 확인한다.
  #
  # 예전에는 앞 200바이트가 비었는지만 봤다(`zcat | head -c 200`). 그러면 반쪽 덤프를 못
  # 잡는 데다, head 가 먼저 파이프를 닫아 **매일 밤 로그에 `gzip: stdout: Broken pipe` 가
  # 찍혔다** — 백업은 멀쩡한데 로그만 고장 난 것처럼 보였다.
  zcat "$out" 2>/dev/null | tail -5 | grep -q "Dump completed on" \
    || { rm -f "$out"; fail "$what 덤프가 끝까지 안 쓰였다"; }
  log "  $what → $(du -h "$out" | cut -f1)"
}

log "=== 1/4 실사용자 데이터(finntech) ==="
docker exec "$DB_CONTAINER" sh -lc "$MYSQLDUMP finntech" 2>/dev/null | gzip -1 > "$APP_OUT" \
  || { rm -f "$APP_OUT"; fail "finntech 덤프 실패"; }
verify "$APP_OUT" "finntech"

log "=== 2/4 제공자의 실사람 몫 ==="
# 두 번 떠서 한 파일에 잇는다. 결제는 `real-` 접두만, 신원·카드는 통째.
#
# **문자열 리터럴을 작은따옴표로 쓴다.** 큰따옴표(`"real-%"`)로 쓰면 `sql_mode` 에
# `ANSI_QUOTES` 가 켜지는 순간 그것이 <b>식별자</b>로 읽혀 조용히 실패한다.
# 여기서는 bash 의 `\"` 가 큰따옴표 한 겹만 만들고, 안쪽 작은따옴표는 그대로 지나간다.
PROVIDER_CMD="$MYSQLDUMP --where=\"mydata_payment_id LIKE 'real-%'\" finntech_mydata mydata_payment
$MYSQLDUMP finntech_mydata mydata_user mydata_card"
docker exec "$DB_CONTAINER" sh -lc "$PROVIDER_CMD" 2>/dev/null | gzip -1 > "$PROVIDER_OUT" \
  || { rm -f "$PROVIDER_OUT"; fail "finntech_mydata(실사람) 덤프 실패"; }
verify "$PROVIDER_OUT" "finntech_mydata(실사람)"

log "=== 3/4 오래된 것 정리(최근 $KEEP 벌 유지) ==="
for prefix in finntech- finntech_mydata-real-; do
  # 이름에 시각이 박혀 있어 사전순 = 시간순이다. 뒤에서 KEEP 개를 남기고 앞을 지운다.
  ls -1 "$OUT_DIR/$prefix"*.sql.gz 2>/dev/null | sort | head -n -"$KEEP" | while read -r old; do
    log "  지움 $(basename "$old")"
    rm -f "$old"
  done
done

log "=== 4/4 S3 ==="
if [ -z "$S3_BUCKET" ]; then
  log "  건너뜀 — BACKUP_S3_BUCKET 이 없다. 로컬 사본만으로는 절반짜리다(머리말 참조)"
else
  for f in "$APP_OUT" "$PROVIDER_OUT"; do
    if docker run --rm -v "$OUT_DIR:/b:ro" amazon/aws-cli:latest \
         s3 cp "/b/$(basename "$f")" "s3://$S3_BUCKET/$(basename "$f")" --region "$REGION" >/dev/null 2>&1
    then log "  올림 $(basename "$f")"
    else log "  ::경고:: $(basename "$f") 올리기 실패 — 로컬 사본은 남아 있다"
    fi
  done
fi

log "끝 — $OUT_DIR 에 $(ls -1 "$OUT_DIR"/*.sql.gz 2>/dev/null | wc -l) 벌, 합계 $(du -sh "$OUT_DIR" | cut -f1)"
