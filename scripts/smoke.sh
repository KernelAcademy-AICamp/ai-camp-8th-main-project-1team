#!/usr/bin/env bash
# 운영 스택 스모크 — "떴다"가 아니라 "제대로 떴다"를 본다.
#
#   ./scripts/smoke.sh                     # 로컬 총연습(127.0.0.1:5173)
#   BASE=https://<도메인> ./scripts/smoke.sh   # 배포 후 원격
#
# 두 가지를 함께 확인한다.
#  ① 살아 있는가 — 프론트가 뜨고, /api/ 프록시가 본체에 닿고, 본체가 마이데이터까지 왕복하는가.
#  ② 닫혀 있는가 — 마이데이터(8082)·본체(8080)·DB(3306)가 밖에서 안 열리는가.
# ②가 없으면 "동작한다"는 확인은 절반짜리다. 격리는 자르지 않기로 한 항목이다.
set -uo pipefail

BASE="${BASE:-http://127.0.0.1:5173}"
HOST="${HOST:-127.0.0.1}"
pass=0; fail=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=$((fail+1)); }

# 200이 나와야 하는 경로
expect_ok() {
  local path="$1" desc="$2"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$BASE$path" || echo 000)
  [ "$code" = "200" ] && ok "$desc ($path → 200)" || bad "$desc ($path → $code, 200 기대)"
}

# 응답 본문에 특정 문자열이 있어야 하는 경로
expect_body() {
  local path="$1" needle="$2" desc="$3"
  local body
  body=$(curl -s --max-time 20 "$BASE$path" || true)
  case "$body" in
    *"$needle"*) ok "$desc" ;;
    *)           bad "$desc (본문에 '$needle' 없음: ${body:0:80})" ;;
  esac
}

# 호스트에서 열려 있으면 안 되는 포트
# 로컬 총연습에서는 스택과 무관한 프로세스(예전에 띄워둔 개발 서버)가 같은 포트를 잡고 있을 수 있다.
# 그때는 이 검사가 실패해도 컨테이너 격리와는 별개다 — 실패 메시지에서 그 가능성을 알린다.
expect_closed() {
  local port="$1" desc="$2"
  if curl -s -o /dev/null --max-time 3 "http://$HOST:$port/" 2>/dev/null; then
    bad "$desc — $HOST:$port 가 열려 있다 (스택 밖 프로세스일 수 있다: lsof -nP -iTCP:$port -sTCP:LISTEN)"
  else
    ok "$desc ($HOST:$port 도달 불가)"
  fi
}

echo "스모크 대상: $BASE"
echo
echo "[1/3] 살아 있는가"
expect_ok   "/"                      "SPA 서빙"
# 이 한 방이 프론트 nginx → backend → (공유시크릿) → backend-mydata → DB 전 구간을 지난다.
# 카드사 목록은 마이데이터 서버가 DB에서 읽어 주는 값이라, 200이면 사슬 전체가 이어졌다는 뜻이다.
expect_body "/api/mydata/companies"  "name"  "본체→마이데이터 왕복 (카드사 목록)"

# 브라우저는 POST에 Origin을 붙인다. curl은 안 붙이므로, Origin 없이만 확인하면
# CORS 오판(같은 오리진인데 스킴이 달라 403)을 통과시켜 버린다 — 배포에서 실제로 놓쳤다.
ORIGIN="${ORIGIN:-$BASE}"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
  -X POST "$BASE/api/mydata/verify" -H 'Content-Type: application/json' \
  -H "Origin: $ORIGIN" \
  -d '{"userId":1,"name":"스모크","social7":"0000000","phone":"01000000000"}' || echo 000)
[ "$code" = "403" ] && bad "브라우저 오리진이 CORS에 막힌다 (Origin: $ORIGIN → 403)" \
                    || ok "브라우저 오리진 허용 (POST with Origin → $code)"

# 개편(MOA)으로 늘어난 경로 — 화면이 늘어도 스모크가 그대로면 새 사슬은 아무도 안 지킨다.
#
# **404를 실패로 보지 않는다.** 이 경로들은 대부분 '진행 중 챌린지가 있는 사용자'를 전제하는데,
# CI의 갓 띄운 스택에는 챌린지가 없다. 여기서 봐야 하는 것은 "라우팅이 살아 있고 서버가
# 500으로 죽지 않는가"다 — 200/404는 통과, 5xx와 연결 실패는 실패.
expect_routed() {
  local path="$1" desc="$2"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$BASE$path" || echo 000)
  case "$code" in
    2*|404|400) ok "$desc ($path → $code)" ;;
    *)          bad "$desc ($path → $code, 2xx/4xx 기대)" ;;
  esac
}

expect_routed "/api/guardian/collection?userId=1"     "도감"
expect_routed "/api/guardian/shop?userId=1"           "포인트샵"
expect_routed "/api/guardian/room?userId=1"           "마이룸"
expect_routed "/api/guardian/settlement?userId=1"     "월간 결산"
expect_routed "/api/guardian/renewal?userId=1"        "다음 달 갱신"
expect_routed "/api/guardian/report/weekly?userId=1"  "주간 리포트"

echo
echo "[2/3] 닫혀 있는가"
expect_closed 8082 "마이데이터 서버 비공개"
expect_closed 8080 "본체 직접 접근 차단"
expect_closed 3306 "DB 직접 접근 차단"
# actuator는 /api 밖 경로라 프론트 nginx의 location /api/ 프록시로 갈 수 없다.
# 주의: 상태 코드로 판정하면 안 된다 — nginx가 `try_files ... /index.html`로 SPA를 돌려주므로
# 모르는 경로도 전부 200이다. 노출 여부는 **본문이 actuator 응답인가**로만 가려진다.
body=$(curl -s --max-time 10 "$BASE/actuator/health" || true)
case "$body" in
  *'"status":"UP"'*|*'"status": "UP"'*) bad "actuator가 공개 오리진에 노출됐다 (본문이 actuator 응답)" ;;
  *)                                    ok  "actuator 비공개 (SPA 폴백만 돌아온다)" ;;
esac

echo
echo "[3/3] 요약"
printf '  통과 %d · 실패 %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
