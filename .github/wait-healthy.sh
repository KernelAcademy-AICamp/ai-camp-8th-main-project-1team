#!/usr/bin/env bash
# 컨테이너가 healthy가 될 때까지 기다린다. 되지 않으면 **실패로 끝낸다.**
#
#   ./.github/wait-healthy.sh <기대 개수> [최대 대기초]
#
# 배포 스크립트에도 같은 루프가 있었는데 그쪽은 시간이 다 되어도 그냥 다음 줄로 넘어갔다.
# 기동에 실패한 채 스모크를 지나 "배포 완료"까지 찍혔고, 운영이 502인 동안 아무도 몰랐다(V8).
# 판정을 삼키지 않는 것이 이 파일의 존재 이유다.
set -euo pipefail

WANT="${1:-4}"
DEADLINE="${2:-300}"
CO="${CO:?compose 인자를 CO 환경변수로 넘겨라}"

for ((i = 0; i < DEADLINE; i += 5)); do
  n=$(docker compose $CO ps --format '{{.Status}}' 2>/dev/null | grep -c healthy || true)
  if [ "$n" -ge "$WANT" ]; then
    echo "  ${WANT}개 healthy (${i}초)"
    exit 0
  fi
  # 재시작을 반복하면 더 기다려도 소용없다 — 기동 자체가 실패하는 중이다.
  if docker compose $CO ps --format '{{.Status}}' 2>/dev/null | grep -q 'Restarting'; then
    echo "  재시작 반복 감지 — 기동 실패로 판정한다"
    docker compose $CO ps
    exit 1
  fi
  sleep 5
done

echo "  ${DEADLINE}초 안에 ${WANT}개가 healthy가 되지 못했다"
docker compose $CO ps
exit 1
