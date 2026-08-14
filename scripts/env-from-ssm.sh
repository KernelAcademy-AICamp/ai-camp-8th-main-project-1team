#!/usr/bin/env bash
# 운영 설정을 **SSM Parameter Store 에서 받아** `.env` 를 만든다.
#
#   sudo ./scripts/env-from-ssm.sh            /opt/finntech/.env 를 새로 쓴다
#   sudo ./scripts/env-from-ssm.sh --check    쓰지 않고 지금 파일과 대조만
#
# ## 왜 옮겼나
#
# `/opt/finntech/.env` 에 DB 비밀번호·공유 시크릿·KMS 암호문·외부 API 키가 **평문으로**
# 있었다(38줄, 백업본까지 10개 더). EBS 를 암호화해 디스크 도난은 막았지만, 서버에 들어올 수
# 있는 사람에게는 그대로 읽혔다.
#
# Parameter Store 는 `SecureString` 으로 KMS 에 감싸 두고, **누가 언제 읽었는지 CloudTrail 에
# 남는다.** 파일에는 그런 것이 없다.
#
# ## 값은 언제 디스크에 있나
#
# compose 는 컨테이너를 만들 때만 `.env` 를 읽는다. 만들어진 컨테이너는 환경변수를 자기 안에
# 들고 있어 재부팅으로 다시 떠도 파일이 필요 없다. 그래서 배포는 **받아서 쓰고 → 컨테이너를
# 만들고 → 지운다.** 파일이 디스크에 있는 시간은 배포 몇 분뿐이다.
#
# ## 빈 값은 안 넘어간다
#
# SSM 은 빈 문자열을 거부한다. `MYDATA_NOW=` 처럼 비워 두던 항목은 파라미터가 아예 없고,
# 그러면 compose 의 `${MYDATA_NOW:-}` 가 같은 결과를 만든다. **없는 것과 빈 것이 같은 뜻인
# 항목만** 그렇게 둘 수 있다 — 아닌 항목이 생기면 기본값을 compose 에 명시해야 한다.
set -euo pipefail

PREFIX="${SSM_PREFIX:-/finntech/prod}"
TARGET="${ENV_FILE:-/opt/finntech/.env}"
REGION="${AWS_REGION:-ap-northeast-2}"
# 서버에 AWS CLI 가 없어 컨테이너로 부른다. 이미 배포에 쓰는 이미지다.
CLI=(docker run --rm -e AWS_REGION="$REGION" amazon/aws-cli:latest)

fetch() {
    # `--with-decryption` 이 있어야 SecureString 이 풀린다. 없으면 암호문이 그대로 나와
    # **앱이 조용히 틀린 값으로 뜬다** — 가장 나쁜 실패라 여기서만 쓴다.
    "${CLI[@]}" ssm get-parameters-by-path \
        --path "$PREFIX" --with-decryption --recursive \
        --query 'Parameters[].[Name,Value]' --output text
}

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"

COUNT=0
while IFS=$'\t' read -r NAME VALUE; do
    [ -z "$NAME" ] && continue
    printf '%s=%s\n' "${NAME##*/}" "$VALUE" >> "$TMP"
    COUNT=$((COUNT + 1))
done < <(fetch | sort)

# **한 개도 못 받으면 멈춘다.** 빈 `.env` 를 쓰면 컨테이너가 기본값으로 떠서, DB 비밀번호가
# 비고 공유 시크릿이 없는 채로 서비스가 도는 최악이 된다.
if [ "$COUNT" -lt 10 ]; then
    echo "파라미터를 $COUNT 개밖에 못 받았다 — 권한이나 경로를 확인하라 ($PREFIX)" >&2
    exit 1
fi

if [ "${1:-}" = "--check" ]; then
    echo "받은 항목: $COUNT"
    if [ -f "$TARGET" ]; then
        # 값까지 비교하되 **값은 찍지 않는다.** 이름만 보여 준다.
        diff <(sed -n 's/^\([A-Z_][A-Z_0-9]*\)=.*/\1/p' "$TARGET" | sort) \
             <(sed -n 's/^\([A-Z_][A-Z_0-9]*\)=.*/\1/p' "$TMP" | sort) \
             && echo "항목 이름 일치" || echo "항목 이름이 다르다(위 diff)"
        if diff -q "$TARGET" "$TMP" >/dev/null 2>&1; then
            echo "값까지 완전히 같다"
        else
            echo "값이 다른 항목이 있다:"
            diff <(sort "$TARGET") <(sort "$TMP") | sed -n 's/^\([<>]\) \([A-Z_][A-Z_0-9]*\)=.*/  \1 \2/p' | sort -u
        fi
    fi
    exit 0
fi

install -m 600 "$TMP" "$TARGET"
echo "$TARGET 를 SSM 에서 새로 썼다 — $COUNT 개 항목"
