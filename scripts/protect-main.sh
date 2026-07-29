#!/usr/bin/env bash
# main 브랜치 보호 규칙을 GitHub에 건다. **사용자가 직접 실행한다.**
#
#   GITHUB_TOKEN=ghp_xxx ./scripts/protect-main.sh          # 적용
#   GITHUB_TOKEN=ghp_xxx ./scripts/protect-main.sh --show   # 현재 상태만 확인
#
# 토큰: Settings → Developer settings → Personal access tokens → Fine-grained.
#       이 저장소에 Administration(write) 권한만 주면 된다. 다른 권한은 필요 없다.
#
# ── 왜 스크립트인가 ──────────────────────────────────────────────────────────
# 브랜치 보호는 **저장소 파일이 아니라 설정 API에만** 존재한다. 커밋으로 남길 수 없어서,
# 누가 언제 무엇을 풀었는지 이력이 남지 않는다. 이 스크립트는 걸어야 할 상태를 코드로 적어 두고,
# --show 로 지금 상태를 읽어 비교할 수 있게 한다. 클릭 열몇 번을 대신하는 것은 부수 효과다.
#
# ── 왜 이 조합인가 ──────────────────────────────────────────────────────────
# 저장소 안 워크플로는 '빨간불'까지만 만든다. **빨간불이면 머지 불가**로 바꾸는 판정은
# GitHub만 내린다. 그래서 아래 둘이 짝이어야 실제로 막힌다.
#   · 저장소: ci.yml(운영 조건 기동) · guard-main.yml(출처·체크리스트)
#   · 설정  : 그 검사들을 required 로 지정 + PR 없는 직접 push 금지
set -euo pipefail

REPO="${REPO:-KernelAcademy-AICamp/ai-camp-8th-main-project-1team}"
API="https://api.github.com"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"

[ -n "$TOKEN" ] || { echo "GITHUB_TOKEN 이 필요하다 (Administration: write)"; exit 1; }

api() {
  local method="$1" path="$2" data="${3:-}"
  if [ -n "$data" ]; then
    curl -sS -X "$method" "$API$path" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      -d "$data"
  else
    curl -sS -X "$method" "$API$path" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28"
  fi
}

if [ "${1:-}" = "--show" ]; then
  echo "=== 기본 브랜치 · 자동 머지 ==="
  api GET "/repos/$REPO" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("  기본 브랜치      :", d.get("default_branch"), "  (develop 이어야 한다)")
print("  자동 머지 허용    :", d.get("allow_auto_merge"), "  (false 여야 한다)")
print("  머지 후 브랜치 삭제:", d.get("delete_branch_on_merge"))
'
  echo "=== main 보호 ==="
  api GET "/repos/$REPO/branches/main/protection" | python3 -c '
import json,sys
d=json.load(sys.stdin)
if d.get("message"):
    print("  보호 없음 —", d["message"]); raise SystemExit
checks=(d.get("required_status_checks") or {}).get("contexts", [])
pr=d.get("required_pull_request_reviews") or {}
print("  PR 필수          :", bool(pr))
print("  필수 승인 수      :", pr.get("required_approving_review_count", 0))
print("  코드오너 승인 필수 :", pr.get("require_code_owner_reviews", False))
print("  강제 푸시 허용    :", (d.get("allow_force_pushes") or {}).get("enabled"), "  (false 여야 한다)")
print("  관리자에게도 적용  :", (d.get("enforce_admins") or {}).get("enabled"), "  (true 여야 한다)")
print("  필수 검사        :")
for c in checks: print("    -", c)

'
  exit 0
fi

# 필수 상태 검사 이름 = 워크플로의 job name(한글 그대로).
# 이름이 하나라도 어긋나면 '영원히 대기 중'이 되어 머지가 아예 막히므로, 워크플로를 고칠 때 함께 옮긴다.
# 실제로 한 번 어긋났다(워크플로에서 job 이름을 '운영 조건 기동'→'운영 중지 검사'로 바꾸고 여기를 안 옮겼다).
# 적용 전에 아래로 대조한다:  git show origin/main:.github/workflows/ci.yml | grep 'name:'
CHECKS='["main 진입 검사","운영 중지 검사","테스트 (backend)","테스트 (backend-mydata)"]'

# 필수 승인 수는 0으로 둔다 — 소수 인원이라 서로의 작업을 멈추게 하지 않기 위해서다.
# 대신 코드오너 리뷰 요청은 자동으로 가고(CODEOWNERS), 기계가 판정할 수 있는 것은 검사가 막는다.
# 승인을 필수로 올리려면 required_approving_review_count 를 1 이상으로 바꾼다.
REVIEWS="${REQUIRED_APPROVALS:-0}"

echo "=== main 보호 규칙 적용 ==="
api PUT "/repos/$REPO/branches/main/protection" "$(cat <<JSON
{
  "required_status_checks": { "strict": true, "contexts": $CHECKS },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": $REVIEWS,
    "require_code_owner_reviews": false,
    "dismiss_stale_reviews": true,
    "require_last_push_approval": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "required_linear_history": false,
  "block_creations": false,
  "lock_branch": false
}
JSON
)" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  실패:", d["message"]) if d.get("message") else print("  적용 완료")'

echo "=== 자동 머지 끄기 ==="
# 켜져 있으면 검사가 초록이 되는 순간 **사람 없이** 머지되고 배포까지 나간다.
api PATCH "/repos/$REPO" '{"allow_auto_merge": false}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  실패:", d["message"]) if d.get("message") else print("  자동 머지 off")'

cat <<'EOF'

기본 브랜치는 이 스크립트로 바꾸지 않는다 — 진행 중인 PR들의 base가 한꺼번에 흔들려서,
사람이 상황을 보고 눌러야 한다.

  Settings → General → Default branch → develop

적용 결과 확인:  GITHUB_TOKEN=... ./scripts/protect-main.sh --show
EOF
