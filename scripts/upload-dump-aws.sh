#!/usr/bin/env bash
# 덤프를 운영 서버로 옮긴다 — S3에 올리고 **presigned URL + curl** 로 받게 한다.
#
#   ./scripts/upload-dump-aws.sh
#
# 왜 presigned URL 인가: 서버에 `aws` CLI 가 없다(2026-07-30 확인). 인스턴스 역할에 S3 읽기를
# 붙이는 방법도 있지만, 한 번 쓰고 지울 파일 때문에 권한을 늘리지 않는다. presigned URL 은
# 만료가 있고 그 객체 하나만 열어 준다.
#
# 22번(SSH)은 닫아 둔다 — 배포가 SSM 을 쓰는 이유가 그것이다.
set -euo pipefail
cd "$(dirname "$0")/.."

INSTANCE="${INSTANCE:-i-0caa34b2587168188}"
REGION="${AWS_REGION:-ap-northeast-2}"
DUMP="${DUMP:-deploy/dump/finntech_mydata.sql.gz}"
TTL="${TTL:-7200}"                      # presigned URL 유효시간(초)

[ -f "$DUMP" ] || { echo "덤프가 없다: $DUMP"; exit 1; }
SIZE=$(du -h "$DUMP" | cut -f1)
echo "[1/4] 덤프 $DUMP ($SIZE)"

BUCKET="finntech-dump-$(date +%s)"
echo "[2/4] 버킷 생성 $BUCKET"
aws s3 mb "s3://$BUCKET" --region "$REGION" >/dev/null
# 끝나면 반드시 지운다 — 개인 금융 형식의 더미라도 공개 버킷에 남기지 않는다.
cleanup() { echo "[정리] 버킷 삭제"; aws s3 rb "s3://$BUCKET" --force >/dev/null 2>&1 || true; }
trap cleanup EXIT

aws s3 cp "$DUMP" "s3://$BUCKET/finntech_mydata.sql.gz" --region "$REGION"

echo "[3/4] presigned URL 발급 (${TTL}초)"
URL=$(aws s3 presign "s3://$BUCKET/finntech_mydata.sql.gz" --expires-in "$TTL" --region "$REGION")

echo "[4/4] 서버로 내려받기"
CMD=$(python3 - "$URL" <<'PY'
import json, sys
url = sys.argv[1]
cmds = [
  "set -e",
  "sudo mkdir -p /opt/finntech/restore && sudo chown ubuntu:ubuntu /opt/finntech/restore",
  f"curl -fsSL --retry 3 -o /opt/finntech/restore/finntech_mydata.sql.gz '{url}'",
  "ls -lh /opt/finntech/restore/finntech_mydata.sql.gz",
  "gzip -t /opt/finntech/restore/finntech_mydata.sql.gz && echo '압축 정상'",
]
print(json.dumps({"commands": cmds}))
PY
)
ID=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
      --parameters "$CMD" --region "$REGION" --query 'Command.CommandId' --output text)
for _ in $(seq 1 120); do
  S=$(aws ssm get-command-invocation --command-id "$ID" --instance-id "$INSTANCE" \
        --region "$REGION" --query 'Status' --output text 2>/dev/null || echo Pending)
  case "$S" in Pending|InProgress|Delayed) sleep 10;; *) break;; esac
done
aws ssm get-command-invocation --command-id "$ID" --instance-id "$INSTANCE" --region "$REGION" \
  --query '[Status,StandardOutputContent,StandardErrorContent]' --output text
