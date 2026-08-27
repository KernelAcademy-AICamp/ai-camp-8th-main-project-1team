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

# **크기 오버레이는 인스턴스에 맞춘다.** base(`docker-compose.prod.yml`)의 한도 합계는
# 주석대로 **t3.medium(4GB) 기준**이고, `prod.large.yml` 은 8GB 인스턴스용으로 그것을 덮는다.
#
# 2026-08-26 에 `m7i-flex.large`(8GB) → `t3a.medium`(4GB) 로 내리면서 large 를 뺐다.
# 크레딧이 $110 남았는데 월 $94 로 9월 말이면 끊겼다 — 인스턴스가 비용의 81% 인데
# 자원은 8GB 중 3GB, 2코어에 부하 0.26 이었다. 내려서 월 $42 로 11월 중순까지 늘렸다.
#
# **8GB 로 되돌릴 때는 `-f docker-compose.prod.large.yml` 한 줄을 되살린다.**
CO=(docker compose
    -f docker-compose.prod.yml
    -f docker-compose.prod.local-db.yml
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

# **설정을 SSM 에서 받아 `.env` 를 만든다.**
#
# 예전에는 `/opt/finntech/.env` 가 디스크에 평문으로 늘 있었다 — DB 비밀번호·공유 시크릿·
# KMS 암호문·외부 API 키가 38줄, 백업본까지 10개. EBS 를 암호화해 디스크 도난은 막았지만
# 서버에 들어올 수 있는 사람에게는 그대로 읽혔다.
#
# 이제 Parameter Store 가 원본이고(SecureString·CloudTrail 에 열람 기록), 파일은 **컨테이너를
# 만드는 동안만** 존재한다. 아래에서 지운다.
#
# 못 받으면 멈춘다 — 빈 `.env` 로 띄우면 DB 비밀번호가 비고 공유 시크릿이 없는 채로 서비스가
# 도는 최악이 된다. 스크립트 자신도 10개 미만이면 쓰지 않는다.
echo "=== 설정 받기 (SSM) ==="
if ! sudo bash scripts/env-from-ssm.sh; then
  echo "  설정을 못 받았다 — 옛 .env 로 띄우지 않는다"
  exit 1
fi

# **인증서를 컨테이너보다 먼저 만든다.** 없으면 제공자가 TLS 로 못 뜨고, 본체는 신뢰저장소를
# 못 읽어 기동에서 멈춘다. 이미 있고 만료가 멀면 그대로 둔다(멱등).
echo "=== 내부 구간 인증서 ==="
if ! sudo bash scripts/internal-tls-cert.sh; then
  echo "  인증서를 못 만들었다 — 평문으로 띄우지 않는다"
  exit 1
fi

# **망을 열기 전에 방화벽을 먼저 건다.** `kms-egress` 는 평범한 브리지라 규칙이 없으면
# 그 순간 제공자에게 인터넷이 열린다 — 실 개인정보가 있는 서버다. 순서가 곧 방어다.
#
# 규칙은 `DOCKER-USER` 에 들어가 컨테이너가 오르내려도 남지만, **재부팅으로는 사라진다.**
# 그래서 배포마다 다시 건다(멱등). 부팅 직후의 빈 구간은 아래 systemd 유닛이 메운다.
# **유닛도 배포가 옮긴다.** 예전에는 손으로 설치하게 돼 있었는데, 그러면 고친 유닛이
# 저장소에만 있고 기계에는 영영 안 간다 — 2026-08-27 재부팅에서 방화벽이 통째로 안 걸린 뒤
# 유닛을 고쳤지만, 배포가 옮기지 않으면 다음 재부팅에도 똑같이 뚫린다. 멱등이라 매번 돌려도
# 된다: 내용이 같으면 아무 일도 안 일어난다.
echo "=== 부팅 유닛 갱신 ==="
for U in kms-egress-guard.service finntech-backup.service finntech-backup.timer; do
  if [ -f "deploy/$U" ] && ! sudo cmp -s "deploy/$U" "/etc/systemd/system/$U"; then
    sudo cp "deploy/$U" "/etc/systemd/system/$U" && echo "  갱신 $U"
    NEED_RELOAD=1
  fi
done
if [ "${NEED_RELOAD:-0}" = "1" ]; then
  sudo systemctl daemon-reload
  sudo systemctl enable kms-egress-guard.service finntech-backup.timer >/dev/null 2>&1 || true
  echo "  daemon-reload 완료"
fi

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

# **여기서야 지운다.** 롤백 경로도 `.env` 를 쓰므로(`up -d --build`) 그 전에 지우면
# 되돌리기가 막힌다. 스모크까지 통과한 뒤가 유일하게 안전한 자리다.
#
# 만들어진 컨테이너는 환경변수를 자기 안에 들고 있어, 재부팅으로 다시 떠도 이 파일이 필요 없다.
# 손으로 compose 를 돌려야 할 때는 `scripts/env-from-ssm.sh` 를 먼저 실행한다.
# **디스크는 배포가 채운다.** 매 배포가 이미지 셋을 새로 굽고, 밀려난 옛 이미지와 빌드
# 캐시는 아무도 안 치운다. 런북에 손으로 하라고 적어 두었지만 손은 잊는다 — 8/26 에
# 39GB 중 32GB(81%)까지 차 있었고 10.1GB 를 회수했다. **디스크가 차면 MySQL 이 쓰기를
# 멈추고 서비스가 죽는다.** 배포가 만든 것은 배포가 치운다.
#
# 스모크까지 통과한 뒤에 한다 — 그 전에 지우면 되돌릴 자리를 스스로 없앤다.
#
# **이미지와 빌드 캐시를 다르게 다룬다.** 처음에는 둘 다 `until=48h` 로 뒀다가 실측하고
# 고쳤다(8/27): 되돌리기를 빠르게 하는 것은 **빌드 캐시**이지 밀려난 이미지가 아니다.
# 롤백은 소스에서 다시 굽는데(`up -d --build`) 그때 쓰는 것이 캐시다. 태그를 잃은 옛
# 이미지는 아무도 안 본다 — 그저 자리만 차지한다. 하루에 배포가 여섯 번이던 날
# 이틀치가 12GB 로 불어 여유 15GB 를 위협했다.
#
# `-a` 를 **빼는** 것이 핵심이다. `-a` 는 태그가 붙어 있어도 안 쓰이면 지우는데, 그러면
# 백업이 매일 쓰는 `amazon/aws-cli` 가 사라져 날마다 400MB 를 다시 받는다. `-f` 만 주면
# **태그를 잃은 것(배포 쓰레기)만** 지운다.
echo "=== 디스크 정리 ==="
docker image prune -f 2>/dev/null | tail -1
docker builder prune -af --filter "until=48h" 2>/dev/null | tail -1
df -h / | awk 'NR==2 {print "  루트 " $5 " 사용 (" $4 " 남음)"}'

echo "=== 설정 파일 지우기 ==="
sudo shred -u /opt/finntech/.env 2>/dev/null || sudo rm -f /opt/finntech/.env
sudo rm -f /opt/finntech/.env.bak.* 2>/dev/null || true
echo "  .env 와 옛 백업들을 지웠다 — 원본은 SSM Parameter Store 에 있다"

echo "=== 배포 완료: $(git log --oneline -1) ==="
