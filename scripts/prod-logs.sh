#!/usr/bin/env bash
# 운영 컨테이너 로그를 본다 — SSH 없이 SSM 으로.
#
#   ./scripts/prod-logs.sh                 # 백엔드 오류만 (기본 6시간)
#   ./scripts/prod-logs.sh mydata          # 제공자(8082) 오류만
#   ./scripts/prod-logs.sh mydata 30m all  # 제공자 30분치 전부
#
# **왜 두 곳을 따로 보나.** 본체(8080)는 제공자가 준 500 만 적는다 — "왜"는 제공자 로그에만
# 있다. 2026-08-20 로그인 장애가 그랬다: 본체에는 `500 /bank/mydata/identity-match` 뿐이었고
# 진짜 원인(`NonUniqueResultException`)은 제공자 쪽에 있었다.
set -euo pipefail
INSTANCE="${FINNTECH_INSTANCE:-i-0caa34b2587168188}"
case "${1:-backend}" in
  mydata|provider) NAME=app-backend-mydata-1 ;;
  front|frontend)  NAME=app-frontend-1 ;;
  mysql|db)        NAME=app-mysql-1 ;;
  *)               NAME=app-backend-1 ;;
esac
SINCE="${2:-6h}"
FILTER="${3:-error}"
if [ "$FILTER" = "all" ]; then CMD="docker logs --since $SINCE $NAME 2>&1 | tail -200"
else CMD="docker logs --since $SINCE $NAME 2>&1 | grep -iE 'ERROR|Exception|Caused by' | tail -80"; fi

ID=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
  --parameters "{\"commands\":[$(python3 -c "import json,sys;print(json.dumps(sys.argv[1]))" "$CMD")]}" \
  --query "Command.CommandId" --output text)
for _ in $(seq 1 60); do
  S=$(aws ssm get-command-invocation --command-id "$ID" --instance-id "$INSTANCE" \
        --query Status --output text 2>/dev/null || echo Pending)
  [ "$S" = "InProgress" ] || [ "$S" = "Pending" ] || break
  sleep 2
done
aws ssm get-command-invocation --command-id "$ID" --instance-id "$INSTANCE" \
  --query "StandardOutputContent" --output text
