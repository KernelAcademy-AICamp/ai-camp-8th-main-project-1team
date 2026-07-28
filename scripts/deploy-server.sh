#!/usr/bin/env bash
# 서버에서 실행되는 배포 스크립트. GitHub Actions가 SSM으로 이 파일을 호출한다.
#
#   sudo bash /opt/finntech/app/scripts/deploy-server.sh [브랜치]
#
# 무엇을 하는가: origin/<브랜치>로 **강제 정렬** → 재빌드 → healthy 대기 → 스모크.
#
# `git pull`이 아니라 `reset --hard`인 이유: 서버에서 급히 손댄 파일이 남아 있으면 pull이
# 충돌하거나(멈춤) 조용히 남아(드리프트) 저장소와 다른 것이 돌아간다. 배포된 서버는
# 저장소의 사본이어야 하고, 서버에서만 고친 것은 **없는 게 맞다** — 있다면 그건 사고다.
# 시크릿(/opt/finntech/.env)은 저장소 밖에 있어 이 정렬에 영향받지 않는다.
set -euo pipefail
export HOME=/root

BRANCH="${1:-main}"
APP=/opt/finntech/app
ENVFILE=/opt/finntech/.env

cd "$APP"
git config --global --add safe.directory "$APP" 2>/dev/null || true

echo "=== 이전 상태 ==="
git log --oneline -1
DRIFT=$(git status --porcelain | head -20)
[ -n "$DRIFT" ] && { echo "  버려질 서버 로컬 변경:"; echo "$DRIFT" | sed 's/^/    /'; } || echo "  드리프트 없음"

echo "=== $BRANCH 로 정렬 ==="
git fetch --quiet origin "$BRANCH"
git checkout -q -B "$BRANCH" "origin/$BRANCH"
git reset -q --hard "origin/$BRANCH"
git clean -qfd                                    # 추적 밖 잔재도 지운다(빌드 산출물·손댄 파일)
git log --oneline -1

CO=(docker compose
    -f docker-compose.prod.yml
    -f docker-compose.prod.local-db.yml
    -f docker-compose.prod.large.yml
    --profile local-db --env-file "$ENVFILE")

echo "=== 재빌드·기동 ==="
"${CO[@]}" up -d --build

echo "=== healthy 대기 ==="
for i in $(seq 1 60); do
  n=$("${CO[@]}" ps --format '{{.Status}}' | grep -c healthy || true)
  [ "$n" -ge 4 ] && { echo "  4서비스 healthy (${i}회차)"; break; }
  sleep 5
done
"${CO[@]}" ps --format 'table {{.Service}}\t{{.Status}}'

echo "=== 스모크 ==="
# 도메인으로 돌린다 — 인증서·프록시·CORS까지 실제 경로를 지난다.
BASE="${SMOKE_BASE:-https://moaa.kro.kr}" HOST="${SMOKE_HOST:-moaa.kro.kr}" bash scripts/smoke.sh

echo "=== 배포 완료: $(git log --oneline -1) ==="
