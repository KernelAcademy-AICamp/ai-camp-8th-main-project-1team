#!/usr/bin/env python3
"""HTML 원문에서 카드 상품 PDF를 수집한다.

- out/raw/에 저장된 HTML 원문에서 PDF 링크를 찾는다.
- 추가 사이트 목록도 직접 확인해 PDF 링크를 수집한다.
- robots.txt를 확인하고, 막히는 경우는 건너뛴다.
- 다운로드한 PDF는 out/pdf/에 저장하고 out/pdf_meta.json에 메타데이터를 남긴다.
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
from typing import Any, Optional, Tuple

BASE = Path(__file__).resolve().parent
RAW = BASE / "out" / "raw"
PDF_OUT = BASE / "out" / "pdf"
META = BASE / "out" / "pdf_meta.json"
FETCH_META = BASE / "out" / "meta.json"
UA = ("MOA-portfolio-collector/1.0 (student portfolio project; "
      "card product disclosure; respects robots.txt)")
DELAY_SEC = 1.5

SOURCE_URLS = [
    ("BC카드", "https://www.bccard.com/app/card/ContentsLinkActn.do?pgm_id=ind0836"),
    ("삼성카드", "https://www.samsungcard.com/company/IR/announce/product-conditions/UHPPCI0261M0.jsp"),
    ("우리카드", "https://pc.wooricard.com/dcpc/yh1/cct/cct11/prdntc/H1CCT211S09.do"),
    ("현대카드", "https://www.hyundaicard.com/cpu/ug/CPUUG2001_08.hc"),
]

HOST_ISSUER = {
    "www.bccard.com": "BC카드",
    "www.samsungcard.com": "삼성카드",
    "pc.wooricard.com": "우리카드",
    "www.hyundaicard.com": "현대카드",
}


class RobotsCache:
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
        if not url.startswith(("http://", "https://")):
            return False, "절대 URL이 아니어 건너뜀"
        parts = urllib.parse.urlsplit(url)
        origin = f"{parts.scheme}://{parts.netloc}"
        if origin not in self._cache:
            self._cache[origin] = self._load(origin)
        rp = self._cache[origin]
        if rp is self.FAIL:
            return False, "robots.txt를 읽을 수 없어 건너뜀"
        if rp is None:
            return True, "robots.txt 없음"
        for agent in ("*", UA):
            if not rp.can_fetch(agent, url):
                return False, f"robots.txt가 '{agent}'에 불허"
        return True, "robots.txt 허용"


def slug_for_url(url: str, fallback: str = "file") -> str:
    parts = urllib.parse.urlsplit(url)
    name = Path(parts.path).name or fallback
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name) or fallback
    return f"{parts.netloc}_{name}_{hashlib.sha1(url.encode()).hexdigest()[:8]}"


def fetch_html(url: str, *, force: bool = False) -> Tuple[Optional[Path], Optional[str]]:
    host = urllib.parse.urlsplit(url).netloc
    path = RAW / f"{host}_{slug_for_url(url, 'index')}.html"
    if path.exists() and not force:
        return path, None
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            body = res.read()
    except Exception as e:  # noqa: BLE001
        return None, str(e)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body)
    return path, None


def iter_candidate_html_paths() -> list[Path]:
    paths = []
    if RAW.exists():
        paths.extend(sorted(RAW.glob("*.html")))
    return paths


def resolve_page_url(html_path: Path, meta: dict) -> Optional[str]:
    for source_url, info in meta.items():
        if info.get("file") == html_path.name:
            return source_url
    for name, source_url in SOURCE_URLS:
        if html_path.name.startswith(urllib.parse.urlsplit(source_url).netloc):
            return source_url
    if "_" in html_path.name:
        host = html_path.name.split("_", 1)[0]
        if "." in host:
            return f"https://{host}"
    return None


def extract_pdf_links(html_text: str, page_url: str) -> list[str]:
    links = []
    for href in re.findall(r"href=[\"']([^\"']+)[\"']", html_text, flags=re.I):
        if href.startswith(("mailto:", "javascript:")):
            continue
        if href.startswith("#"):
            continue
        if ".pdf" not in href.lower():
            if "pdf" not in href.lower():
                continue
        parsed = urllib.parse.urljoin(page_url, href)
        links.append(parsed)
    return sorted(set(links))


def download_pdf(url: str, *, force: bool = False) -> Tuple[Optional[Path], Optional[dict], Optional[str]]:
    ok, reason = RobotsCache().allowed(url)
    if not ok:
        return None, None, reason
    path = PDF_OUT / f"{slug_for_url(url, 'download')}.pdf"
    if path.exists() and not force:
        return path, None, None
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            body = res.read()
    except Exception as e:  # noqa: BLE001
        return None, None, str(e)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body)
    meta = {
        "pdf_url": url,
        "file": path.name,
        "bytes": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
        "downloaded_at": datetime.now(timezone.utc).isoformat(),
    }
    return path, meta, None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="기존 파일도 다시 수집한다")
    ap.add_argument("--limit", type=int, default=0, help="이번 실행에서 처리할 최대 PDF 수")
    args = ap.parse_args()

    PDF_OUT.mkdir(parents=True, exist_ok=True)
    RAW.mkdir(parents=True, exist_ok=True)
    meta = json.loads(META.read_text(encoding="utf-8")) if META.exists() else {}
    fetch_meta = json.loads(FETCH_META.read_text(encoding="utf-8")) if FETCH_META.exists() else {}
    robots = RobotsCache()

    html_paths = iter_candidate_html_paths()
    seen_sources = set()

    for name, source_url in SOURCE_URLS:
        html_path, err = fetch_html(source_url, force=args.force)
        if err:
            print(f"HTML 수집 실패 · {name} · {err}")
            time.sleep(DELAY_SEC)
            continue
        if html_path is not None:
            seen_sources.add(source_url)
            time.sleep(DELAY_SEC)

    for html_path in html_paths:
        html_text = html_path.read_text(encoding="utf-8", errors="replace")
        page_url = resolve_page_url(html_path, fetch_meta)
        if page_url is None:
            continue
        pdf_links = extract_pdf_links(html_text, page_url)
        if not pdf_links:
            print(f"PDF 링크 없음 · {html_path.name}")
            continue
        for pdf_url in pdf_links:
            if args.limit and len(meta) >= args.limit:
                print(f"\n--limit {args.limit} 도달, 중단합니다.")
                return
            ok, why = robots.allowed(pdf_url)
            if not ok:
                print(f"  건너뜀 · {why}\n    {pdf_url}")
                meta[pdf_url] = {
                    "status": "skipped",
                    "reason": why,
                    "checked_at": datetime.now(timezone.utc).isoformat(),
                }
                continue
            path, entry, err = download_pdf(pdf_url, force=args.force)
            if err:
                print(f"  실패 · {err}\n    {pdf_url}")
                meta[pdf_url] = {
                    "status": "failed",
                    "reason": err,
                    "checked_at": datetime.now(timezone.utc).isoformat(),
                }
            elif path is None:
                print(f"  건너뜀 · 이미 있음 또는 차단\n    {pdf_url}")
                continue
            else:
                entry = entry or {}
                entry.update({
                    "status": "downloaded",
                    "source_url": page_url,
                    "source_html": str(html_path),
                    "issuer": HOST_ISSUER.get(urllib.parse.urlsplit(page_url).netloc, "알수없음"),
                    "robots": why,
                })
                meta[pdf_url] = entry
                size = entry.get("bytes", 0)
                print(f"  받음 · {size:,}B · {entry.get('file', pdf_url)}")
            time.sleep(DELAY_SEC)

    META.parent.mkdir(parents=True, exist_ok=True)
    META.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n수집 완료 · 메타 → {META}")


if __name__ == "__main__":
    main()
