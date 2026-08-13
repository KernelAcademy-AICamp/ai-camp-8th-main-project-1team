#!/usr/bin/env bash
# 제공자 컨테이너의 **나가는 길을 KMS 엔드포인트 하나로** 좁힌다.
#
#   sudo ./scripts/kms-egress-guard.sh          규칙 설치(멱등)
#   sudo ./scripts/kms-egress-guard.sh --show   지금 걸린 규칙 보기
#
# ## 왜 필요한가
#
# `backend-mydata` 는 실 개인정보가 있는 서버라 `internal: true` 도커 망에 두어 **밖으로 나가지
# 못하게** 해 뒀다. 그런데 신원 컬럼을 KMS envelope 로 암호화하려면 기동할 때 KMS 를 한 번
# 불러야 한다 — 그래서 2026-08-13 배포가 헬스체크에서 죽고 롤백됐다.
#
# 인터넷을 열어 주면 격리가 사라진다. 그래서 **VPC 인터페이스 엔드포인트**를 두어 KMS 트래픽이
# 인터넷을 안 타게 하고(프라이빗 DNS 로 `kms.…amazonaws.com` 이 VPC 내부 IP 로 풀린다),
# 그 컨테이너의 나가는 길은 **그 IP 의 443 하나만** 남긴다. 나머지는 전부 버린다.
#
# ## 왜 DOCKER-USER 인가
#
# 도커는 자기 규칙을 `DOCKER` 체인에 넣고 재시작할 때마다 다시 쓴다. 거기에 손을 대면 다음
# 재시작에 사라진다. `DOCKER-USER` 는 도커가 **건드리지 않고 먼저 태우는** 체인이라, 여기 넣은
# 것은 컨테이너가 오르내려도 남는다.
#
# ## DNS 는 왜 안 열어 주나
#
# 컨테이너는 도커 내장 DNS(127.0.0.11)에 묻고, 그 질의는 **호스트가 대신** 밖에 물어본다.
# 컨테이너 자신이 나갈 필요가 없으므로 열 것이 없다.
set -euo pipefail

# 제공자가 붙는 egress 전용 망. compose 의 `kms-egress` 와 **같아야 한다**.
SUBNET="${KMS_EGRESS_SUBNET:-172.20.53.0/24}"
CHAIN=DOCKER-USER

kms_endpoint_ip() {
    # 프라이빗 DNS 가 켜져 있으면 VPC 내부 IP 가 나온다. 공인 IP 가 나오면 엔드포인트가
    # 없거나 프라이빗 DNS 가 꺼진 것이라, 그대로 잠그면 KMS 를 못 부른다 — 그때는 멈춘다.
    getent hosts kms."${AWS_REGION:-ap-northeast-2}".amazonaws.com | awk '{print $1}' | head -1
}

show() {
    iptables -L "$CHAIN" -n --line-numbers | sed -n '1,40p'
}

if [ "${1:-}" = "--show" ]; then show; exit 0; fi

IP="$(kms_endpoint_ip)"
if [ -z "$IP" ]; then
    echo "KMS 이름을 풀지 못했다 — 엔드포인트를 먼저 만들어라" >&2
    exit 1
fi
case "$IP" in
    172.*|10.*|192.168.*) : ;;
    *) echo "KMS 가 공인 IP($IP)로 풀린다 — VPC 엔드포인트의 프라이빗 DNS 를 켜라" >&2; exit 1 ;;
esac

# **먼저 지운다.** 두 번 돌려도 규칙이 쌓이지 않아야 한다 — 쌓이면 순서가 뒤엉키고,
# 무엇이 실제로 적용 중인지 아무도 모르게 된다.
while iptables -L "$CHAIN" -n --line-numbers | grep -q "finntech-kms-egress"; do
    LINE=$(iptables -L "$CHAIN" -n --line-numbers | grep -m1 "finntech-kms-egress" | awk '{print $1}')
    iptables -D "$CHAIN" "$LINE"
done

# 순서가 규칙이다 — 허용을 먼저 넣고 거부를 마지막에 넣되, 삽입은 역순으로 한다.
# ① 이미 맺어진 흐름은 통과 (응답 패킷)
iptables -I "$CHAIN" 1 -s "$SUBNET" -m state --state ESTABLISHED,RELATED -j RETURN \
    -m comment --comment "finntech-kms-egress: 응답은 통과"
# ② KMS 엔드포인트 443 만 허용
iptables -I "$CHAIN" 2 -s "$SUBNET" -d "$IP"/32 -p tcp --dport 443 -j RETURN \
    -m comment --comment "finntech-kms-egress: KMS 엔드포인트만 허용"
# ③ 나머지는 전부 버린다
iptables -I "$CHAIN" 3 -s "$SUBNET" -j DROP \
    -m comment --comment "finntech-kms-egress: 그 밖은 전부 차단"

echo "설치됨 — $SUBNET 는 $IP:443 외에는 못 나간다"
show
