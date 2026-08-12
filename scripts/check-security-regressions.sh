#!/usr/bin/env bash
# 보안 회귀 검사 — CI 가 매번 돌린다 (설계서 Phase 5).
#
#   ./scripts/check-security-regressions.sh
#   종료: 0 깨끗함 · 1 발견(머지하면 안 된다)
#
# ## 왜 필요한가
#
# 지금 이 저장소의 보안은 **없는 것으로 지켜지는 것**이 많다. SQL Injection 표면이 없는 이유는
# 네이티브 쿼리를 아무도 안 썼기 때문이고, 관리 화면이 사용자에게 안 새는 이유는 번들이
# 갈려 있기 때문이다. 둘 다 **한 줄이면 무너진다.**
#
# 사람의 주의력에 기대지 않고 기계가 지킨다.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
note() { printf '  %s\n' "$1"; }
bad()  { printf '✗ %s\n' "$1"; fail=1; }
ok()   { printf '✓ %s\n' "$1"; }

echo "── 보안 회귀 검사 ──────────────────────────────────────────"

# ── 1. SQL Injection 표면 ────────────────────────────────────────────────────
# 지금은 전부 Spring Data JPA 파라미터 바인딩이라 표면이 0이다. 그 상태를 고정한다.
hits=$(grep -rn "nativeQuery *= *true\|createNativeQuery" \
        backend/src/main/java backend-mydata/src/main/java 2>/dev/null | wc -l | tr -d ' ')
if [ "$hits" != "0" ]; then
  bad "네이티브 쿼리가 들어왔다 ($hits 곳) — 파라미터 바인딩을 벗어나면 SQLi 표면이 생긴다"
  grep -rn "nativeQuery *= *true\|createNativeQuery" \
      backend/src/main/java backend-mydata/src/main/java 2>/dev/null | head -5 | while read -r l; do note "$l"; done
else
  ok "네이티브 쿼리 없음 — SQLi 표면이 열리지 않았다"
fi

# ── 2. 인증을 끄고 커밋했는가 ────────────────────────────────────────────────
# 운영에서 이 값이 false 면 `?userId=N` 으로 남의 데이터가 그대로 열린다.
if grep -q 'FINNTECH_AUTH_ENABLED: "true"' docker-compose.prod.yml; then
  ok "운영 인증이 켜져 있다"
else
  bad "docker-compose.prod.yml 에서 인증이 꺼졌다 — 남의 데이터가 열린다"
fi

# ── 3. 관리자 무방비 입구가 열렸는가 ─────────────────────────────────────────
# /admin/realdata 는 인증이 없고 본문의 신원을 그대로 믿는다. 기본값이 true 로 바뀌면 안 된다.
if grep -q 'MYDATA_REALDATA_ENABLED: "${MYDATA_REALDATA_ENABLED:-false}"' docker-compose.prod.yml; then
  ok "관리자 적재구(/admin/realdata)가 기본 꺼짐"
else
  bad "관리자 적재구의 기본값이 바뀌었다 — 인증 없는 쓰기 경로다"
fi

# ── 4. 번들 격리 ─────────────────────────────────────────────────────────────
# 관리 화면이 사용자 SPA 안으로 들어오면, 그 경로 이름과 코드가 모든 방문자의 JS 에 실린다.
if [ -d frontend/dist/assets ]; then
  leaked=0
  for word in admin intake totp; do
    n=$(grep -oil "$word" frontend/dist/assets/index-*.js 2>/dev/null | wc -l | tr -d ' ')
    [ "$n" != "0" ] && { bad "사용자 번들에 '$word' 가 들어 있다 — 번들 격리가 깨졌다"; leaked=1; }
  done
  [ "$leaked" = "0" ] && ok "사용자 번들에 관리 화면 흔적 없음"
else
  note "frontend/dist 가 없어 번들 격리 검사를 건너뛴다 (npm run build 후 다시)"
fi

# ── 5. 관리 화면이 사용자 앱에서 연결됐는가 ──────────────────────────────────
# 링크·매직 문자열로 관리 화면에 가게 만들면 번들을 가른 의미가 사라진다.
if grep -rn "admin\.html\|/ops/" frontend/src/screens frontend/src/state frontend/src/components 2>/dev/null | grep -q .; then
  bad "사용자 앱 코드가 관리 화면을 가리킨다 — 접근은 URL 직접 입력만이어야 한다"
else
  ok "사용자 앱에 관리 화면 진입점 없음"
fi

# ── 6. 토큰·비밀번호를 평문으로 저장하는가 ───────────────────────────────────
# 마이그레이션에 `token VARCHAR` 같은 칸이 새로 생기면 지문이 아니라 원문을 저장한다는 뜻이다.
if grep -rn "token_hash\|password_hash" backend/src/main/resources/db/migration/*.sql >/dev/null 2>&1; then
  ok "토큰·비밀번호가 해시 칸으로 정의돼 있다"
else
  bad "token_hash/password_hash 칸을 찾지 못했다 — 원문 저장으로 되돌아갔는지 확인하라"
fi

echo "─────────────────────────────────────────────────────────────"
[ "$fail" = "0" ] && echo "깨끗함" || echo "발견 — 머지하면 안 된다"
exit "$fail"
