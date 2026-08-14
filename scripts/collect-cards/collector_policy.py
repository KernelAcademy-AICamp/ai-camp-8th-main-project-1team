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
