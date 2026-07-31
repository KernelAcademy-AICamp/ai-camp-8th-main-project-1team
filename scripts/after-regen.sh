#!/usr/bin/env bash
# 재생성 직후 한 벌 — 검증 → ML 재학습 → 테스트 → 덤프.
#
#   ./scripts/after-regen.sh
#
# 단계를 사람이 손으로 이으면 하나를 빼먹는다. 실제로 2026-07-30 에 모델만 바꾸고
# parity_samples.json 을 안 갱신해 테스트가 깨졌고, 2026-07-31 에는 CI 재배정 뒤
# birth_year 를 안 옮겨 12명 중 11명의 생년이 어긋났다. 순서를 코드로 고정한다.
set -uo pipefail
cd "$(dirname "$0")/.."

step() { printf '\n\033[1m━━ %s ━━\033[0m\n' "$1"; }

step "1/5 데이터 검증"
bash scripts/verify-regen.sh
verify=$?

step "2/5 ML 재학습 + 정답 대조"
bash scripts/retrain-ml.sh || { echo "재학습 실패"; exit 1; }

step "3/5 백엔드 테스트 (새 모델·새 대조표)"
(cd backend && ./mvnw -B -q test 2>&1 | tail -20) || { echo "backend 테스트 실패"; exit 1; }
echo "  backend ✓"

step "4/5 생성기 테스트"
(cd backend-mydata && ./mvnw -B -q test 2>&1 | tail -20) || { echo "backend-mydata 테스트 실패"; exit 1; }
echo "  backend-mydata ✓"

step "5/5 덤프"
./scripts/dump-mydata.sh
ls -lh deploy/dump/*.gz

printf '\n'
if [ "$verify" -eq 0 ]; then
  printf '\033[32m전 단계 통과 — AWS 반영 준비 완료\033[0m\n'
else
  printf '\033[33m검증에서 %d개 항목이 걸렸다. 위 로그를 보고 판단한다.\033[0m\n' "$verify"
fi
