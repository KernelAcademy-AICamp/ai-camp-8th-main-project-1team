#!/usr/bin/env bash
# 서버에 내려둔 덤프를 운영 MySQL 에 적재한다 — <b>분리 실행 + 진행 폴링</b>.
#
#   ./scripts/restore-mydata-aws.sh            # 적재하고 끝날 때까지 지켜본다
#   ./scripts/restore-mydata-aws.sh --status   # 지금 진행 상황만 본다
#
# `upload-dump-aws.sh` 는 **내려받기까지만** 한다. 적재가 따로인 이유는 오래 걸리기 때문이다 —
# 1,100만 행은 수십 분이고, SSM 명령은 그보다 먼저 타임아웃이 난다. 그래서 서버에서
# `nohup` 으로 떼어 놓고 로그를 폴링한다.
#
# **먼저 백업을 뜬다.** 적재는 되돌릴 수 없고(--add-drop-table 이 기존 표를 지운다), 실패하면
# 운영에 데이터가 없는 상태로 남는다. 2026-07-30 에 재연동으로 801행을 잃은 적이 있다.
set -uo pipefail
cd "$(dirname "$0")/.."

INSTANCE="${INSTANCE:-i-0caa34b2587168188}"
REGION="${AWS_REGION:-ap-northeast-2}"
DB="${MYDATA_DB_NAME:-finntech_mydata}"
GZ="/opt/finntech/restore/finntech_mydata.sql.gz"
LOG="/opt/finntech/restore/restore.log"
DONE="/opt/finntech/restore/restore.done"

# SSM 으로 셸 한 줄. 결과는 "상태<TAB>표준출력<TAB>표준오류".
ssm() {
  local json cid status
  json=$(python3 -c 'import json,sys; print(json.dumps({"commands":[sys.argv[1]]}))' "$1")
  cid=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
        --parameters "$json" --region "$REGION" --query 'Command.CommandId' --output text) || return 1
  for _ in $(seq 1 60); do
    sleep 3
    status=$(aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" \
             --region "$REGION" --query 'Status' --output text 2>/dev/null || echo Pending)
    case "$status" in Pending|InProgress|Delayed) ;; *) break ;; esac
  done
  aws ssm get-command-invocation --command-id "$cid" --instance-id "$INSTANCE" --region "$REGION" \
    --query '[Status,StandardOutputContent,StandardErrorContent]' --output text
}

if [ "${1:-}" = "--status" ]; then
  ssm "tail -5 $LOG 2>/dev/null; echo ---; [ -f $DONE ] && cat $DONE || echo '진행 중'"
  exit 0
fi

echo "[1/4] 덤프 확인"
ssm "ls -lh $GZ && gzip -t $GZ && echo 압축정상" | tail -2

echo
echo "[2/4] 적재 전 백업 (되돌릴 길을 먼저 만든다)"
ssm "set -e
set -a; . /opt/finntech/.env; set +a
docker exec -e MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" app-mysql-1 mysqldump -uroot --single-transaction --quick --no-tablespaces $DB \
  | gzip -1 > /opt/finntech/restore/${DB}.before.sql.gz
ls -lh /opt/finntech/restore/${DB}.before.sql.gz" | tail -2

echo
echo "[3/4] 적재 시작 (분리 실행)"
# 외래키·유니크 검사를 끄면 대량 적재가 몇 배 빠르다. 덤프 자체가 일관된 스냅샷이라 안전하다.
ssm "rm -f $DONE $LOG
set -a; . /opt/finntech/.env; set +a
nohup sh -c \"
  date '+시작 %H:%M:%S' > $LOG
  { echo 'SET FOREIGN_KEY_CHECKS=0; SET UNIQUE_CHECKS=0; SET SESSION sql_log_bin=0;'; gunzip -c $GZ; } \
    | docker exec -i -e MYSQL_PWD='\$MYSQL_ROOT_PASSWORD' app-mysql-1 mysql -uroot --binary-mode $DB >> $LOG 2>&1
  echo \\\$? > $DONE
  date '+끝 %H:%M:%S' >> $LOG
\" >/dev/null 2>&1 &
echo '분리 실행 시작'" | tail -1

echo
echo "[4/4] 완료 대기 (최대 90분)"
for i in $(seq 1 180); do
  sleep 30
  out=$(ssm "[ -f $DONE ] && cat $DONE || echo RUNNING; docker exec app-mysql-1 sh -c 'echo' 2>/dev/null || true" | awk -F'\t' '{print $2}')
  code=$(echo "$out" | head -1 | tr -d '[:space:]')
  if [ "$code" != "RUNNING" ] && [ -n "$code" ]; then
    if [ "$code" = "0" ]; then echo "  적재 성공"; break
    else echo "  ✗ 적재 실패 (종료코드 $code)"; ssm "tail -20 $LOG"; exit 1; fi
  fi
  [ $((i % 4)) -eq 0 ] && echo "  … $((i / 2))분 경과"
done

echo
echo "[검증] 적재 결과"
SQL="SELECT CONCAT('결제 ', FORMAT(COUNT(*),0)) FROM $DB.mydata_payment;
     SELECT CONCAT('사용자 ', COUNT(*)) FROM $DB.mydata_user;
     SELECT CONCAT('가맹점 ', FORMAT(COUNT(*),0)) FROM $DB.mydata_merchant;"
ssm "set -a; . /opt/finntech/.env; set +a
printf '%s' '$SQL' | docker exec -i -e MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" app-mysql-1 mysql -uroot -N -B" | tail -2
