#!/usr/bin/env python3
"""카드사 수집기가 공유하는 접근 정책 검사."""

from __future__ import annotations

import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from typing import Iterable


USER_AGENT = (
    "MOA-portfolio-collector/1.0 (student portfolio project; "
    "card product disclosure; respects robots.txt)"
)


class RobotsPolicyError(OSError):
    """robots.txt가 자동 수집을 허용하지 않거나 판정할 수 없을 때 발생한다."""


def _decode(raw: bytes) -> str:
    for encoding in ("utf-8", "cp949", "euc-kr"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise RobotsPolicyError("robots.txt 문자 인코딩을 해석할 수 없습니다")


def require_robots_allowed(urls: Iterable[str], user_agent: str = USER_AGENT) -> None:
    """모든 URL의 robots 정책이 허용일 때만 반환한다.

    404/410은 정책 파일이 없는 것으로 처리한다. 그 밖의 네트워크·HTTP 오류는
    허용으로 추측하지 않고 중단한다.
    """

    checked = {}
    for url in urls:
        parts = urllib.parse.urlsplit(url)
        origin = f"{parts.scheme}://{parts.netloc}"
        if not parts.scheme or not parts.netloc:
            raise RobotsPolicyError(f"절대 URL이 아닙니다: {url}")
        if origin not in checked:
            robots_url = origin + "/robots.txt"
            request = urllib.request.Request(robots_url, headers={"User-Agent": user_agent})
            try:
                with urllib.request.urlopen(request, timeout=15) as response:
                    raw = response.read()
            except urllib.error.HTTPError as exc:
                if exc.code in (404, 410):
                    checked[origin] = None
                    continue
                raise RobotsPolicyError(
                    f"robots.txt를 확인할 수 없습니다: {robots_url} (HTTP {exc.code})"
                ) from exc
            except Exception as exc:
                raise RobotsPolicyError(
                    f"robots.txt를 확인할 수 없습니다: {robots_url} "
                    f"({type(exc).__name__})"
                ) from exc

            parser = urllib.robotparser.RobotFileParser()
            parser.parse(_decode(raw).splitlines())
            checked[origin] = parser

        parser = checked[origin]
        if parser is None:
            continue
        for agent in ("*", user_agent):
            if not parser.can_fetch(agent, url):
                raise RobotsPolicyError(
                    f"robots.txt가 자동 수집을 불허합니다: {url} (User-agent: {agent})"
                )


# ── 수집 대상 분류 (A/B/C 정책, 2026-08-10 확정) ─────────────────────────
#
# 카드사 공시를 통째로 받으면 시간도 용량도 감당이 안 된다. 그렇다고 "발급 중인 것만"
# 받으면 **이미 그 카드를 쓰는 사람**에게 해 줄 말이 사라진다 — 비교의 과거 축이 없어진다.
#
#     A  발급 중                        추천도 하고 비교도 한다
#     B  발급 중단 + 기준선 이후 중단     비교만 한다 (보유자가 있다)
#     C  발급 중단 + 기준선 이전 중단     받지 않는다 (쓰는 사람이 거의 없다)
#     X  개인이 신청할 수 없는 상품       받지 않는다 (법인·직역·특수결제)
#
# 기준선은 보통 수집일 - 3년이다. **"쓰는 사람이 있느냐"를 공시로는 알 수 없어** 갱신
# 시점을 대리 지표로 쓴다. 완벽하지 않지만 공시에서 얻을 수 있는 최선이다.
#
# 이 판정을 카드사마다 따로 적으면 갈라진다. 여기 한 벌만 둔다.

# 공시 표가 "중단일 없음"을 적는 방식. 카드사마다 표기가 조금씩 다르고 깨진 것도 있다.
PLACEHOLDER_STOP_YEARS = ("9999", "9912", "2999", "9998")

_HERE = __import__("pathlib").Path(__file__).resolve().parent


def _exclusion_rules():
    """개인 신청 불가 규칙은 후보 선정 모듈이 정본이다(두 벌로 두면 갈라진다)."""
    import sys

    if str(_HERE) not in sys.path:
        sys.path.insert(0, str(_HERE))
    import select_youth_cards

    return select_youth_cards.EXCLUSION_RULES


def real_stop_date(value):
    """실제 중단일이면 그대로, 아니면 ``None``.

    빈 값 표기가 카드사마다 갈리고(``''``·``'-'``), **먼 미래 날짜는 "중단일 없음"의
    자리표시자다**(`9999.12.31`). 이 판단을 쓰는 곳이 둘이라(수집 분류와 추출의 status)
    여기 한 벌만 둔다 — 한쪽만 고치면 갈라진다.
    """
    raw = str(value or "").strip()
    if not raw or raw == "-" or raw.startswith(PLACEHOLDER_STOP_YEARS):
        return None
    return raw


def abc_group(product_name, stop_date, baseline):
    """(군, 사유)를 돌려준다. 군은 ``A``·``B``·``C``·``X`` 중 하나다.

    ``stop_date``는 ``datetime.date`` 이거나 ``YYYY-MM-DD`` 문자열, 또는 비어 있으면
    발급 중으로 본다. 빈 값 표기가 카드사마다 갈려서(``''``·``'-'``·``None``)
    **값이 있고 ``-``가 아닐 때만** 중단으로 읽는다.

    중단인데 날짜를 못 읽으면 ``B``로 둔다. **모르는 것을 버리지 않는다** — C로 밀면
    보유자가 있는 카드가 조용히 사라지고, 사라진 것은 아무도 눈치채지 못한다.
    """
    import datetime

    for reason, pattern in _exclusion_rules():
        if pattern.search(product_name or ""):
            return "X", reason

    raw = str(stop_date or "").strip()
    if not raw or raw == "-":
        return "A", "발급 중"
    # **먼 미래 날짜는 "중단일 없음" 이라는 자리표시자다.** 공시 표에 빈칸을 못 두어
    # `9999.12.31`(또는 깨진 `9912.31.`)을 넣는다. 이걸 날짜로 읽으면 발급 중인 카드가
    # 전부 "9999년에 중단됨"이 되어 신규 발급 추천에서 사라진다
    # (2026-08-14 실측 — KB 388장 중 67장이 이 값이었다).
    if raw.startswith(PLACEHOLDER_STOP_YEARS):
        return "A", "발급 중(중단일 자리표시자)"

    if isinstance(stop_date, datetime.date):
        parsed = stop_date
    else:
        try:
            parsed = datetime.date.fromisoformat(raw[:10])
        except ValueError:
            return "B", "발급 중단(중단일을 읽지 못함)"

    if parsed >= baseline:
        return "B", f"발급 중단 {parsed} (기준선 이후)"
    return "C", f"발급 중단 {parsed} (기준선 이전)"
