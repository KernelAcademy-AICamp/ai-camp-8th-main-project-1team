#!/usr/bin/env python3
"""카드 상품 페이지 수집 — seeds.txt의 URL을 받아 원문 HTML을 out/raw/에 보관한다.

수집과 추출을 나눈 이유: 사이트 구조가 바뀌어도 원문이 남아 있으면 다시 긁지 않고
extract.py만 고치면 된다. 서버에도 부담이 덜하다.

지키는 것
  - robots.txt를 매 호스트마다 읽고, 불허 경로는 받지 않는다(스킵 사유를 남긴다).
  - 요청 간격 1.5초. 동시 요청 없음.
  - 이미 받은 URL은 다시 받지 않는다(--force로만 강제).
  - 출처 URL·수집 시각·응답 상태를 meta.json에 함께 남긴다.

의존성 없음(표준 라이브러리만). 실행: python3 scripts/collect-cards/fetch.py
"""
import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from datetime import datetime, timezone
from pathlib import Path

BASE = Path(__file__).resolve().parent
SEEDS = BASE / "seeds.txt"
RAW = BASE / "out" / "raw"
META = BASE / "out" / "meta.json"

# 정직하게 밝힌다 — 숨기고 받을 이유가 없다.
# HTTP 헤더는 latin-1만 실을 수 있어 ASCII로만 쓴다(한글을 넣으면 UnicodeEncodeError).
UA = ("MOA-portfolio-collector/1.0 (student portfolio project; "
      "card product disclosure; respects robots.txt)")
DELAY_SEC = 1.5


def read_seeds() -> list[str]:
    if not SEEDS.exists():
        sys.exit(f"seeds.txt가 없습니다: {SEEDS}")
    urls = []
    for line in SEEDS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            urls.append(line)
    return urls


class RobotsCache:
    """호스트별 robots.txt를 한 번만 읽어 재사용한다.

    ★ 실패는 '막는 쪽'으로 처리한다.
      robotparser.read()를 그냥 쓰면 안 된다 — 내부에서 UTF-8로만 디코딩해서
      주석이 EUC-KR인 robots.txt(국내 사이트에 흔하다)를 만나면 예외가 난다.
      그걸 '파일 없음'으로 넘기면 실제로는 금지된 경로를 허용해 버린다.
      그래서 직접 받아서 인코딩을 낮춰가며 해석하고, 그래도 안 되면 수집을 건너뛴다.
    """

    # (파서, 판정 사유). 파서가 None이면 제약 없음, FAIL이면 확인 불가라 차단.
    FAIL = "unreadable"

    def __init__(self) -> None:
        self._cache: dict[str, object] = {}

    @staticmethod
    def _decode(raw: bytes) -> str:
        for enc in ("utf-8", "cp949", "euc-kr"):
            try:
                return raw.decode(enc)
            except UnicodeDecodeError:
                continue
        # 마지막 수단 — 깨진 글자는 버리되 규칙 줄(ASCII)은 살린다.
        return raw.decode("utf-8", errors="replace")

    def _load(self, origin: str):
        url = origin + "/robots.txt"
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=15) as res:
                raw = res.read()
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                print(f"  robots.txt 확인: {url} (없음 — 제약 없음)")
                return None
            print(f"  robots.txt 확인: {url} (HTTP {e.code} — 확인 불가)")
            return self.FAIL
        except Exception as e:  # noqa: BLE001
            print(f"  robots.txt 확인: {url} ({type(e).__name__} — 확인 불가)")
            return self.FAIL

        rp = urllib.robotparser.RobotFileParser()
        rp.parse(self._decode(raw).splitlines())
        print(f"  robots.txt 확인: {url} (있음)")
        return rp

    def allowed(self, url: str) -> tuple[bool, str]:
        parts = urllib.parse.urlsplit(url)._replace(path="", query="", fragment="")
        origin = urllib.parse.urlunsplit(parts)
        if origin not in self._cache:
            self._cache[origin] = self._load(origin)
        rp = self._cache[origin]

        if rp is self.FAIL:
            # 확인할 수 없으면 받지 않는다. 안전장치는 닫히는 쪽으로 실패해야 한다.
            return False, "robots.txt를 읽을 수 없어 건너뜀"
        if rp is None:
            return True, "robots.txt 없음"
        # '*'과 우리 UA 둘 다 확인해 더 엄격한 쪽을 따른다.
        for agent in ("*", UA):
            if not rp.can_fetch(agent, url):
                return False, f"robots.txt가 '{agent}'에 불허"
        return True, "robots.txt 허용"


def slug(url: str) -> str:
    """URL → 파일명. 사람이 알아볼 수 있게 경로 끝을 남기고 해시를 붙인다."""
    parts = urllib.parse.urlsplit(url)
    tail = re.sub(r"[^A-Za-z0-9._-]", "_", Path(parts.path).name) or "index"
    return f"{parts.netloc}_{tail}_{hashlib.sha1(url.encode()).hexdigest()[:8]}.html"


def fetch_one(url: str) -> tuple[int, bytes]:
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "ko-KR,ko;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=20) as res:
        return res.status, res.read()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="이미 받은 것도 다시 받는다")
    ap.add_argument("--limit", type=int, default=0, help="이번 실행에서 받을 최대 건수")
    args = ap.parse_args()

    RAW.mkdir(parents=True, exist_ok=True)
    meta = json.loads(META.read_text(encoding="utf-8")) if META.exists() else {}
    robots = RobotsCache()

    urls = read_seeds()
    print(f"대상 {len(urls)}건\n")
    got = skipped = blocked = failed = 0

    for url in urls:
        if args.limit and got >= args.limit:
            print(f"\n--limit {args.limit} 도달, 중단합니다.")
            break

        name = slug(url)
        path = RAW / name
        if path.exists() and not args.force:
            skipped += 1
            continue

        ok, why = robots.allowed(url)
        if not ok:
            print(f"  건너뜀 · {why}\n    {url}")
            meta[url] = {"skipped": why, "checked_at": datetime.now(timezone.utc).isoformat()}
            blocked += 1
            continue

        try:
            status, body = fetch_one(url)
        except urllib.error.HTTPError as e:
            print(f"  실패 · HTTP {e.code}\n    {url}")
            meta[url] = {"error": f"HTTP {e.code}",
                         "checked_at": datetime.now(timezone.utc).isoformat()}
            failed += 1
            time.sleep(DELAY_SEC)
            continue
        except Exception as e:  # noqa: BLE001 — 어떤 실패든 다음 URL로 넘어간다
            print(f"  실패 · {type(e).__name__}\n    {url}")
            meta[url] = {"error": type(e).__name__,
                         "checked_at": datetime.now(timezone.utc).isoformat()}
            failed += 1
            time.sleep(DELAY_SEC)
            continue

        path.write_bytes(body)
        meta[url] = {
            "file": name,
            "http_status": status,
            "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
            "fetched_at": datetime.now(timezone.utc).isoformat(),
            "robots": why,
        }
        got += 1
        print(f"  받음 · {len(body):,}B · {name}")
        time.sleep(DELAY_SEC)

    META.parent.mkdir(parents=True, exist_ok=True)
    META.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n받음 {got} · 건너뜀(기존) {skipped} · robots 차단 {blocked} · 실패 {failed}")
    print(f"원문 → {RAW}\n메타 → {META}")


if __name__ == "__main__":
    main()
