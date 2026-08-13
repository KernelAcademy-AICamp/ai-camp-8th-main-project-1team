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
# 되돌아갈 곳을 **정렬 전에** 잡아 둔다. reset --hard 뒤에는 이 값을 알 방법이 없다.
PREV=$(git rev-parse HEAD)
DRIFT=$(git status --porcelain | head -20)
[ -n "$DRIFT" ] && { echo "  버려질 서버 로컬 변경:"; echo "$DRIFT" | sed 's/^/    /'; } || echo "  드리프트 없음"

echo "=== $BRANCH 로 정렬 ==="
git fetch --quiet origin "$BRANCH"
# 순서가 중요하다. checkout을 먼저 하면 "local changes would be overwritten"으로 **막힌다** —
# 정렬하려는 대상이 바로 그 로컬 변경인데도 그렇다. 먼저 버리고 나서 브랜치를 옮긴다.
# (첫 배포에서 실제로 밟았다: checkout이 중단되고 브랜치 이름만 예전 것으로 남았다.)
git reset -q --hard FETCH_HEAD
git clean -qfd                                    # 추적 밖 잔재도 지운다(빌드 산출물·손댄 파일)
git checkout -q -B "$BRANCH" "origin/$BRANCH"
git log --oneline -1

CO=(docker compose
    -f docker-compose.prod.yml
    -f docker-compose.prod.local-db.yml
    -f docker-compose.prod.large.yml
    --profile local-db --env-file "$ENVFILE")

# 4서비스가 healthy가 될 때까지 기다린다. 성공 0 / 실패 1 — **판정을 호출자에게 넘긴다.**
# 예전에는 이 루프가 60회를 다 돌고도 그냥 다음 줄로 넘어갔다. 기동에 실패한 채로 스모크를
# 지나 "배포 완료"까지 찍혔고, 운영이 502인 동안 아무도 몰랐다(V8 사고).
wait_healthy() {
  for i in $(seq 1 60); do
    n=$("${CO[@]}" ps --format '{{.Status}}' | grep -c healthy || true)
    [ "$n" -ge 4 ] && { echo "  4서비스 healthy (${i}회차)"; return 0; }
    sleep 5
  done
  echo "  healthy 대기 초과(5분)"
  "${CO[@]}" ps --format 'table {{.Service}}\t{{.Status}}'
  return 1
}

# 되돌린다. 새 코드가 기동하지 못하면 **운영을 그 상태로 두지 않는다** — 배포를 빨간불로
# 만드는 것만으로는 서비스가 돌아오지 않기 때문이다. 이전 커밋은 방금 전까지 돌던 것이므로
# 다시 뜨는 것이 거의 확실하고, 되돌린 뒤에도 실패하면 사람이 붙어야 하는 상황이다.
#
# **한계를 분명히 해 둔다: 되돌아가는 것은 코드뿐이고 스키마는 그대로다.**
# Flyway는 앞으로만 간다. 컬럼이 늘어난 정도는 옛 코드도 `validate`를 통과하지만
# (엔티티가 요구하는 것이 스키마에 있기만 하면 된다), 컬럼을 **지우는** 마이그레이션이
# 한 번 돌면 옛 코드는 없는 컬럼을 찾다가 죽어 롤백해도 살아나지 않는다.
# 그래서 그런 마이그레이션은 guard-main이 PR 단계에서 막는다.
rollback() {
  echo "=== 롤백: $PREV 로 되돌린다 ==="
  git reset -q --hard "$PREV"
  git clean -qfd
  "${CO[@]}" up -d --build || true
  if wait_healthy; then
    echo "  롤백 성공 — 운영은 이전 커밋($(git log --oneline -1))으로 돌아왔다"
  else
    echo "  ::롤백까지 실패했다. 서버에 직접 붙어야 한다.::"
  fi
  exit 1
}

# **망을 열기 전에 방화벽을 먼저 건다.** `kms-egress` 는 평범한 브리지라 규칙이 없으면
# 그 순간 제공자에게 인터넷이 열린다 — 실 개인정보가 있는 서버다. 순서가 곧 방어다.
#
# 규칙은 `DOCKER-USER` 에 들어가 컨테이너가 오르내려도 남지만, **재부팅으로는 사라진다.**
# 그래서 배포마다 다시 건다(멱등). 부팅 직후의 빈 구간은 아래 systemd 유닛이 메운다.
echo "=== KMS egress 방화벽 ==="
if ! sudo bash scripts/kms-egress-guard.sh; then
  echo "  방화벽을 못 걸었다 — 격리가 없는 채로 띄우지 않는다"
  exit 1
fi

echo "=== 재빌드·기동 ==="
if ! "${CO[@]}" up -d --build; then
  echo "  빌드·기동 명령 자체가 실패했다"
  rollback
fi

echo "=== healthy 대기 ==="
wait_healthy || rollback
"${CO[@]}" ps --format 'table {{.Service}}\t{{.Status}}'

echo "=== 스모크 ==="
# 도메인으로 돌린다 — 인증서·프록시·CORS까지 실제 경로를 지난다.
if ! BASE="${SMOKE_BASE:-https://moaa.kro.kr}" HOST="${SMOKE_HOST:-moaa.kro.kr}" bash scripts/smoke.sh; then
  echo "  스모크 실패"
  rollback
fi

echo "=== 배포 완료: $(git log --oneline -1) ==="
