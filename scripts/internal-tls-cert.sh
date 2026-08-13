#!/usr/bin/env bash
# backend ↔ backend-mydata 구간 TLS 에 쓸 인증서를 만든다.
#
#   sudo ./scripts/internal-tls-cert.sh          없거나 만료가 가까우면 새로 만든다
#   sudo ./scripts/internal-tls-cert.sh --force   무조건 새로 만든다
#
# ## 무엇을 만드나
#
#     /opt/finntech/tls/server.p12      제공자가 쓰는 키스토어(개인키 + 인증서)
#     /opt/finntech/tls/ca.crt          그 인증서만 PEM 으로 — curl 헬스체크와 본체가 쓴다
#     /opt/finntech/tls/truststore.p12  본체가 쓰는 신뢰저장소(위 인증서 하나만 들어 있다)
#
# ## 왜 자체 서명인가
#
# 이 구간은 **도커 내부망**이고 이름이 `backend-mydata` 다. 공인 CA 는 그런 이름에 인증서를
# 발급하지 않는다. 필요한 것은 "아무나 믿지 않는 것"이지 "공인된 신원"이 아니다 —
# 본체의 신뢰저장소에 **이 인증서 하나만** 넣어, 그 하나 외에는 아무도 못 붙게 한다.
#
# ## 키스토어 비밀번호를 왜 고정값으로 두나
#
# 파일이 `root:600` 이고 개인키는 이 호스트를 떠나지 않는다. 이 파일을 읽을 수 있는 사람은
# 이미 호스트를 장악한 사람이고, 그에게는 비밀번호가 장애물이 아니다(같은 디스크에 두면
# 함께 읽히고, 환경변수에 두면 `docker inspect` 로 보인다). **지키는 것은 파일 권한이지
# 비밀번호가 아니다** — 그 사실을 숨기지 않고 적어 둔다.
#
# ## 유효기간
#
# 825일. 그전에 다시 돌리면 새로 만든다. 만료되면 본체가 제공자를 못 부르고, 그것은
# 로그인·동기화가 통째로 서는 것을 뜻한다 — 그래서 만료 30일 전부터 갱신한다.
set -euo pipefail

DIR="${TLS_DIR:-/opt/finntech/tls}"
PASS="${TLS_PASS:-finntech-internal}"
DAYS=825
RENEW_BEFORE_DAYS=30
# 제공자가 이 이름들로 불린다. 하나라도 빠지면 그 이름으로 붙을 때 검증이 깨진다.
SAN="DNS:backend-mydata,DNS:localhost,IP:127.0.0.1"

need_new() {
    [ "${1:-}" = "--force" ] && return 0
    [ -f "$DIR/server.p12" ] && [ -f "$DIR/truststore.p12" ] && [ -f "$DIR/ca.crt" ] || return 0
    # 만료가 가까우면 새로. `-checkend` 는 초 단위다.
    # keytool 에는 `-checkend` 가 없다. 만료일을 읽어 남은 날을 센다.
    local until_line days_left
    until_line=$(docker run --rm --entrypoint keytool -v "$DIR:/tls:ro" "${TLS_TOOL_IMAGE:-app-backend-mydata}" \
        -printcert -file /tls/ca.crt 2>/dev/null | sed -n 's/.*until: //p' | head -1)
    [ -z "$until_line" ] && return 0
    days_left=$(( ( $(date -d "$until_line" +%s 2>/dev/null || echo 0) - $(date +%s) ) / 86400 ))
    [ "$days_left" -gt "$RENEW_BEFORE_DAYS" ] && return 1
    return 0
}

if ! need_new "${1:-}"; then
    echo "인증서가 아직 유효하다 — 그대로 둔다 ($DIR)"
    exit 0
fi

mkdir -p "$DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# keytool 은 JRE 에 들어 있다. 제공자 이미지를 그대로 쓴다 — 새 이미지를 끌어오지 않는다.
IMAGE="${TLS_TOOL_IMAGE:-app-backend-mydata}"
# **`--entrypoint` 로 덮어쓴다.** 이 이미지는 스프링 앱을 띄우는 것이 기본이라, 그냥 부르면
# 내 명령이 무시되고 앱이 뜨다가 DB 를 못 찾고 죽는다(2026-08-13 실측).
#
# **`--user 0:0` 이 필요하다.** 이미지는 `appuser`(uid 1001)로 도는데 이 디렉터리는 root
# 소유라 그대로는 못 쓴다. 만들어진 파일의 주인은 아래 `install` 이 다시 정한다.
docker run --rm --user 0:0 --entrypoint sh -v "$TMP:/out" "$IMAGE" -c "
    keytool -genkeypair -alias mydata -keyalg RSA -keysize 2048 \
        -dname 'CN=backend-mydata, OU=internal, O=finntech' \
        -ext 'SAN=$SAN' -validity $DAYS \
        -keystore /out/server.p12 -storetype PKCS12 \
        -storepass '$PASS' -keypass '$PASS' >/dev/null &&
    keytool -exportcert -alias mydata -rfc \
        -keystore /out/server.p12 -storepass '$PASS' -file /out/ca.crt >/dev/null &&
    keytool -importcert -noprompt -alias mydata -file /out/ca.crt \
        -keystore /out/truststore.p12 -storetype PKCS12 -storepass '$PASS' >/dev/null
"

install -m 640 -o root -g root "$TMP/server.p12"     "$DIR/server.p12"
install -m 644 -o root -g root "$TMP/ca.crt"          "$DIR/ca.crt"
install -m 644 -o root -g root "$TMP/truststore.p12"  "$DIR/truststore.p12"

echo "인증서를 새로 만들었다 — $DIR"
docker run --rm --entrypoint keytool -v "$DIR:/tls:ro" "$IMAGE" \
    -printcert -file /tls/ca.crt 2>/dev/null | grep -E 'Owner|Valid' | head -2 || true
