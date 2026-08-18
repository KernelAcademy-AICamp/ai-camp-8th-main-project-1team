#!/usr/bin/env python3
"""NH농협카드 상품공시실의 카드 상품설명서 PDF 수집기.

개인 신용카드와 체크카드 목록(IpCi1261R)을 끝까지 넘기며 상품설명서 PDF를
내려받는다. 같은 PDF를 여러 상품이 공유하는 경우가 있어 URL 기준으로 한 번만
저장하고, 그 파일을 쓰는 상품명을 metadata.json에 함께 기록한다.
외부 패키지는 필요하지 않다.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import http.cookiejar
import json
import logging
import os
import random
import re
import socket
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from collector_policy import USER_AGENT, require_robots_allowed


BASE_URL = "https://card.nonghyup.com"
PAGE_URL = BASE_URL + "/servlet/IpCi1260R.act"
LIST_API_URL = BASE_URL + "/servlet/IpCi1261R.act"
HISTORY_API_URL = BASE_URL + "/servlet/IpCi1262R.act"

# 화면 JS(lfGetListWrsDoc)가 쓰는 상품유형명.
#   brandGbn 1 채움  -> 농협신용(비씨제외) / 농협체크(비씨제외)
#   brandGbn 2 비씨  -> 농협비씨신용       / 농협비씨체크
#   전체            -> 신용               / 체크
CARD_TYPES: Tuple[Dict[str, str], ...] = (
    {
        "key": "credit",
        "label": "신용카드",
        "directory": "credit",
        "all": "신용",
        "chaeum": "농협신용(비씨제외)",
        "bc": "농협비씨신용",
    },
    {
        "key": "check",
        "label": "체크카드",
        "directory": "check",
        "all": "체크",
        "chaeum": "농협체크(비씨제외)",
        "bc": "농협비씨체크",
    },
)
CARD_TYPE_KEYS = tuple(card["key"] for card in CARD_TYPES)
BRAND_GBN = {"all": "", "chaeum": "1", "bc": "2"}

# 화면에서는 '상품설명서'지만 서버 내부 명칭은 '상품안내서'다.
DOCUMENT_STYLE_NAME = "상품안내서"
LIST_CALL_ID = "카드 상품설명"

DEFAULT_USER_AGENT = USER_AGENT
RETRYABLE_HTTP_STATUSES = {408, 425, 429, 500, 502, 503, 504}
FORBIDDEN_PATH_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
WHITESPACE = re.compile(r"\s+")
WINDOWS_RESERVED_NAMES = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}

TOTAL_COUNT_PATTERN = re.compile(r"#total_count'\)\.text\('(\d+)'\)")
HISTORY_SEQ_PATTERN = re.compile(
    r"lfGetWrsDocHst\s*\(\s*.*?,\s*['\"]([^'\"]+)['\"]\s*,", re.S
)

LOG = logging.getLogger("nhcard")


class CollectorError(RuntimeError):
    """수집을 계속할 수 없는 오류."""


class PdfValidationError(CollectorError):
    """다운로드 결과가 정상 PDF가 아닐 때 발생한다."""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def unescape_repeatedly(value: str) -> str:
    """NH농협카드 상품명은 HTML 엔티티가 이중으로 인코딩되어 있다.

    '충북 다자녀 행복카드&#40;체크&#41;' 처럼 unescape를 한 번 더 해야
    괄호가 제자리를 찾는다.
    """
    previous = None
    while value != previous:
        previous = value
        value = html.unescape(value)
    return value


def normalize_text(value: Any) -> str:
    text = unescape_repeatedly(str(value or ""))
    text = unicodedata.normalize("NFC", text)
    return WHITESPACE.sub(" ", text).strip()


def safe_component(value: Any, fallback: str, max_length: int = 160) -> str:
    text = normalize_text(value)
    text = FORBIDDEN_PATH_CHARS.sub("_", text)
    text = WHITESPACE.sub(" ", text).strip(" .")
    if not text:
        text = fallback

    stem, suffix = os.path.splitext(text)
    if stem.upper() in WINDOWS_RESERVED_NAMES:
        stem = "_" + stem
        text = stem + suffix

    if len(text) > max_length:
        if suffix and len(suffix) < 20:
            stem = stem[: max(1, max_length - len(suffix))].rstrip(" .")
            text = stem + suffix
        else:
            text = text[:max_length].rstrip(" .")
    return text or fallback


def filename_from_url(url: str) -> str:
    name = urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(url).path))
    return safe_component(name, fallback="document.pdf")


def retry_delay(attempt: int, retry_after: Optional[str] = None) -> float:
    if retry_after:
        try:
            return min(120.0, max(0.0, float(retry_after)))
        except ValueError:
            pass
    return min(30.0, (2 ** (attempt - 1)) + random.uniform(0.0, 0.75))


def atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def inspect_pdf(path: Path) -> Tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    head = b""
    tail = b""
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            if len(head) < 1024:
                head = (head + chunk)[:1024]
            tail = (tail + chunk)[-4096:]
            size += len(chunk)
            digest.update(chunk)

    if size == 0:
        raise PdfValidationError("0바이트 파일입니다")
    if b"%PDF-" not in head:
        raise PdfValidationError("처음 1024바이트에 PDF 헤더가 없습니다")
    if b"%%EOF" not in tail:
        raise PdfValidationError("마지막 4096바이트에 PDF EOF 표시가 없습니다")
    return size, digest.hexdigest()


class ListRowParser(HTMLParser):
    """목록 응답의 tbody 행에서 셀 텍스트·링크·onclick을 뽑는다.

    한 행은 번호 / 구분 / 상품명 / 상품설명서 / 상품내용변경이력 다섯 칸이다.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.rows: List[Dict[str, Any]] = []
        self._cells: Optional[List[Dict[str, Any]]] = None
        self._cell: Optional[Dict[str, Any]] = None

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        attributes = {key: (value or "") for key, value in attrs}
        if tag == "tr":
            self._cells = []
            self._cell = None
        elif tag == "td" and self._cells is not None:
            self._cell = {"text": [], "hrefs": [], "onclicks": []}
            self._cells.append(self._cell)
        elif tag == "a" and self._cell is not None:
            if attributes.get("href"):
                self._cell["hrefs"].append(attributes["href"].strip())
            if attributes.get("onclick"):
                self._cell["onclicks"].append(attributes["onclick"])

    def handle_endtag(self, tag: str) -> None:
        if tag == "td":
            self._cell = None
        elif tag == "tr":
            if self._cells:
                self.rows.append({"cells": self._cells})
            self._cells = None
            self._cell = None

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell["text"].append(data)

    def handle_entityref(self, name: str) -> None:
        if self._cell is not None:
            self._cell["text"].append(f"&{name};")

    def handle_charref(self, name: str) -> None:
        if self._cell is not None:
            self._cell["text"].append(f"&#{name};")


def cell_text(cell: Dict[str, Any]) -> str:
    return normalize_text("".join(cell["text"]))


def parse_list_page(page_html: str) -> Tuple[Optional[int], List[Dict[str, Any]]]:
    match = TOTAL_COUNT_PATTERN.search(page_html)
    total = int(match.group(1)) if match else None

    parser = ListRowParser()
    parser.feed(page_html)

    products: List[Dict[str, Any]] = []
    for row in parser.rows:
        cells = row["cells"]
        if len(cells) < 4:
            continue
        number = cell_text(cells[0])
        if not number.isdigit():
            continue

        pdf_urls = [
            urllib.parse.urljoin(BASE_URL, href)
            for href in cells[3]["hrefs"]
            if ".pdf" in href.lower()
        ]

        history_seq = None
        if len(cells) >= 5:
            for onclick in cells[4]["onclicks"]:
                seq = HISTORY_SEQ_PATTERN.search(onclick)
                if seq:
                    history_seq = seq.group(1)
                    break

        products.append(
            {
                "number": number,
                "category": cell_text(cells[1]),
                "product_name": cell_text(cells[2]),
                "pdf_urls": pdf_urls,
                "history_seq": history_seq,
            }
        )
    return total, products


class NhCardCollector:
    def __init__(self, timeout: float, retries: int) -> None:
        self.timeout = timeout
        self.retries = retries
        cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(cookie_jar)
        )
        self.opener.addheaders = [
            ("User-Agent", DEFAULT_USER_AGENT),
            ("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7"),
        ]

    def _read_bytes(self, request: urllib.request.Request, action: str) -> bytes:
        last_error: Optional[BaseException] = None
        for attempt in range(1, self.retries + 2):
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    return response.read()
            except urllib.error.HTTPError as exc:
                last_error = exc
                if exc.code not in RETRYABLE_HTTP_STATUSES or attempt > self.retries:
                    body = exc.read(500).decode("utf-8", errors="replace")
                    raise CollectorError(f"{action}: HTTP {exc.code}: {body!r}") from exc
                delay = retry_delay(attempt, exc.headers.get("Retry-After"))
            except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
                last_error = exc
                if attempt > self.retries:
                    raise CollectorError(f"{action}: {exc}") from exc
                delay = retry_delay(attempt)

            LOG.warning(
                "%s 실패(%d/%d): %s; %.1f초 뒤 재시도",
                action,
                attempt,
                self.retries + 1,
                last_error,
                delay,
            )
            time.sleep(delay)
        raise CollectorError(f"{action}: 알 수 없는 재시도 오류: {last_error}")

    def warm_session(self) -> None:
        request = urllib.request.Request(
            PAGE_URL,
            method="GET",
            headers={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            },
        )
        self._read_bytes(request, "상품공시실 페이지")

    def _post_form(self, url: str, payload: Dict[str, str], action: str) -> str:
        request = urllib.request.Request(
            url,
            data=urllib.parse.urlencode(payload, encoding="utf-8").encode("utf-8"),
            method="POST",
            headers={
                "Accept": "text/html, */*; q=0.01",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer": PAGE_URL,
                "X-Requested-With": "XMLHttpRequest",
            },
        )
        return self._read_bytes(request, action).decode("utf-8", errors="replace")

    def fetch_list_page(
        self, card_type: Dict[str, str], brand: str, page: int, page_size: int
    ) -> Tuple[Optional[int], List[Dict[str, Any]]]:
        payload = {
            "currentPage": str(page),
            "rowPerPage": str(page_size),
            "callId": LIST_CALL_ID,
            "brandGbn": BRAND_GBN[brand],
            "wrs_stlt_tinm": "",
            "servicePage": "IpCi1260R",
            "wrs_tpnm": card_type[brand],
            "wrs_stlt_bsnnm": "카드",
            "wrs_stlt_doc_stynm": DOCUMENT_STYLE_NAME,
        }
        body = self._post_form(
            LIST_API_URL, payload, f"목록 {card_type['key']}/{brand} page={page}"
        )
        return parse_list_page(body)

    def collect_products(
        self,
        card_type: Dict[str, str],
        brand: str,
        page_size: int,
        request_delay: float,
    ) -> Tuple[Optional[int], List[Dict[str, Any]]]:
        expected_total: Optional[int] = None
        products: List[Dict[str, Any]] = []
        page = 1

        while True:
            total, batch = self.fetch_list_page(card_type, brand, page, page_size)
            if expected_total is None:
                expected_total = total
            elif total is not None and total != expected_total:
                raise CollectorError(
                    f"{card_type['label']}: 수집 도중 전체 건수가 "
                    f"{expected_total}에서 {total}(으)로 변경됐습니다"
                )
            if not batch:
                break

            products.extend(batch)
            LOG.info(
                "%s 목록: %d페이지, 누적 %d/%s건",
                card_type["label"],
                page,
                len(products),
                expected_total if expected_total is not None else "?",
            )
            if expected_total is not None and len(products) >= expected_total:
                break
            if len(batch) < page_size:
                break
            page += 1
            if request_delay > 0:
                time.sleep(request_delay)

        if expected_total is not None and len(products) != expected_total:
            raise CollectorError(
                f"{card_type['label']} 목록 누락: 화면 표기 {expected_total}건, "
                f"실제 수집 {len(products)}건"
            )
        return expected_total, products

    def fetch_change_history(
        self, product_name: str, history_seq: str
    ) -> List[Dict[str, str]]:
        payload = {
            "wrs_stlt_tinm": product_name,
            "wrs_stlt_sqno1": history_seq,
            "wrs_stlt_sqno2": "0",
            "wrs_stlt_doc_stynm": DOCUMENT_STYLE_NAME,
        }
        body = self._post_form(
            HISTORY_API_URL, payload, f"변경이력 {product_name}"
        )
        parser = ListRowParser()
        parser.feed(body)
        history = []
        for row in parser.rows:
            cells = row["cells"]
            if len(cells) != 2:
                continue
            before = cell_text(cells[0])
            after = cell_text(cells[1])
            if before or after:
                history.append({"before": before, "after": after})
        return history

    def download_pdf(self, url: str, destination: Path, overwrite: bool) -> Dict[str, Any]:
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() and not overwrite:
            try:
                size, digest = inspect_pdf(destination)
                return {
                    "status": "skipped_existing",
                    "size": size,
                    "sha256": digest,
                    "attempts": 0,
                }
            except (OSError, PdfValidationError) as exc:
                LOG.warning("기존 파일이 유효하지 않아 다시 받습니다: %s (%s)", destination, exc)

        partial = destination.with_name(destination.name + ".part")
        last_error: Optional[BaseException] = None
        for attempt in range(1, self.retries + 2):
            if partial.exists():
                partial.unlink()
            request = urllib.request.Request(
                url,
                method="GET",
                headers={
                    "Accept": "application/pdf,application/octet-stream;q=0.9,*/*;q=0.8",
                    "Referer": PAGE_URL,
                },
            )
            try:
                digest = hashlib.sha256()
                size = 0
                head = b""
                tail = b""
                with self.opener.open(request, timeout=self.timeout) as response:
                    content_type = response.headers.get("Content-Type", "")
                    raw_length = response.headers.get("Content-Length")
                    expected_length = (
                        int(raw_length) if raw_length and raw_length.isdigit() else None
                    )
                    with partial.open("wb") as handle:
                        while True:
                            chunk = response.read(1024 * 1024)
                            if not chunk:
                                break
                            handle.write(chunk)
                            digest.update(chunk)
                            size += len(chunk)
                            if len(head) < 1024:
                                head = (head + chunk)[:1024]
                            tail = (tail + chunk)[-4096:]
                        handle.flush()
                        os.fsync(handle.fileno())

                if size == 0:
                    raise PdfValidationError("서버가 HTTP 200과 0바이트 본문을 반환했습니다")
                if expected_length is not None and size != expected_length:
                    raise PdfValidationError(
                        f"Content-Length 불일치: 헤더 {expected_length}, 수신 {size}"
                    )
                if b"%PDF-" not in head:
                    raise PdfValidationError("응답 처음 1024바이트에 PDF 헤더가 없습니다")
                if b"%%EOF" not in tail:
                    raise PdfValidationError("응답 마지막 4096바이트에 PDF EOF 표시가 없습니다")

                os.replace(partial, destination)
                return {
                    "status": "downloaded",
                    "size": size,
                    "sha256": digest.hexdigest(),
                    "content_type": content_type,
                    "attempts": attempt,
                    "downloaded_at": now_iso(),
                }
            except urllib.error.HTTPError as exc:
                last_error = exc
                retryable = exc.code in RETRYABLE_HTTP_STATUSES
                delay = retry_delay(attempt, exc.headers.get("Retry-After"))
                if not retryable or attempt > self.retries:
                    break
            except (
                urllib.error.URLError,
                socket.timeout,
                TimeoutError,
                OSError,
                PdfValidationError,
            ) as exc:
                last_error = exc
                delay = retry_delay(attempt)
                if attempt > self.retries:
                    break

            if partial.exists():
                partial.unlink()
            LOG.warning(
                "PDF 다운로드 실패(%d/%d): %s; %.1f초 뒤 재시도",
                attempt,
                self.retries + 1,
                last_error,
                delay,
            )
            time.sleep(delay)

        if partial.exists():
            partial.unlink()
        raise CollectorError(f"PDF 다운로드 최종 실패: {last_error}")


class PathAllocator:
    """상품명이 겹쳐도 저장 경로가 충돌하지 않게 한다."""

    def __init__(self) -> None:
        self._taken = set()

    def claim(self, directory: str, filename: str) -> str:
        stem, suffix = os.path.splitext(filename)
        candidate = filename
        counter = 2
        while Path(directory, candidate).as_posix().lower() in self._taken:
            candidate = f"{stem} ({counter}){suffix}"
            counter += 1
        relative = Path(directory, candidate).as_posix()
        self._taken.add(relative.lower())
        return relative


def build_files(
    card_type: Dict[str, str],
    products: Iterable[Dict[str, Any]],
    allocator: PathAllocator,
) -> Tuple[List[Dict[str, Any]], int]:
    """같은 PDF를 여러 상품이 공유하므로 URL 기준으로 묶는다."""
    files: Dict[str, Dict[str, Any]] = {}
    without_pdf = 0

    for product in products:
        if not product["pdf_urls"]:
            without_pdf += 1
            continue
        for url in product["pdf_urls"]:
            record = files.get(url)
            if record is None:
                stored = safe_component(
                    f"{product['product_name']}__{filename_from_url(url)}",
                    fallback=filename_from_url(url),
                )
                record = {
                    "card_type": card_type["key"],
                    "card_type_label": card_type["label"],
                    "product_name": product["product_name"],
                    "category": product["category"],
                    "history_seq": product["history_seq"],
                    "shared_with": [],
                    "url": url,
                    "relative_path": allocator.claim(card_type["directory"], stored),
                    "status": "pending",
                }
                files[url] = record
            elif product["product_name"] != record["product_name"]:
                if product["product_name"] not in record["shared_with"]:
                    record["shared_with"].append(product["product_name"])
    return list(files.values()), without_pdf


def summarize_status(files: Iterable[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for record in files:
        status = str(record.get("status") or "unknown")
        counts[status] = counts.get(status, 0) + 1
    return counts


def failure_records(files: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [
        {
            "card_type_label": record["card_type_label"],
            "product_name": record["product_name"],
            "url": record["url"],
            "relative_path": record["relative_path"],
            "error": record.get("error", ""),
        }
        for record in files
        if record.get("status") == "failed"
    ]


def save_state(output_root: Path, metadata: Dict[str, Any]) -> None:
    metadata["updated_at"] = now_iso()
    metadata["status_counts"] = summarize_status(metadata["files"])
    failures = failure_records(metadata["files"])
    atomic_write_json(output_root / "metadata.json", metadata)
    atomic_write_json(
        output_root / "failures.json",
        {
            "updated_at": metadata["updated_at"],
            "count": len(failures),
            "failures": failures,
        },
    )


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="NH농협카드 상품공시실의 카드 상품설명서 PDF를 받습니다."
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "out" / "nhcard",
        help="출력 디렉터리(기본값: out/nhcard)",
    )
    parser.add_argument(
        "--card-types",
        default="all",
        help=f"받을 카드 종류를 쉼표로 나열합니다. 선택지: {', '.join(CARD_TYPE_KEYS)} (기본값: all)",
    )
    parser.add_argument(
        "--brand",
        choices=("all", "chaeum", "bc"),
        default="all",
        help="all 전체(기본) / chaeum 농협(비씨제외) / bc 농협비씨",
    )
    parser.add_argument(
        "--with-history",
        action="store_true",
        help="상품내용변경이력(개정 전/후 문구)도 metadata.json에 담기(요청이 크게 늘어남)",
    )
    parser.add_argument(
        "--metadata-only",
        action="store_true",
        help="목록과 JSON만 만들고 PDF는 받지 않기",
    )
    parser.add_argument(
        "--limit-files",
        type=int,
        default=None,
        help="앞의 N개 파일만 내려받기(시험 실행용)",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=100,
        help="목록 API 페이지당 건수(기본값: 100)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.2,
        help="정상 요청 사이 대기 초(기본값: 0.2)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=120.0,
        help="요청 제한시간 초(기본값: 120, 20MB 넘는 PDF가 있음)",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=4,
        help="실패 후 재시도 횟수(기본값: 4)",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="이미 존재하는 정상 PDF도 다시 받기",
    )
    parser.add_argument(
        "--fail-fast",
        action="store_true",
        help="첫 PDF 다운로드 실패 시 즉시 중단",
    )
    parser.add_argument(
        "--log-level",
        choices=("DEBUG", "INFO", "WARNING", "ERROR"),
        default="INFO",
    )
    args = parser.parse_args(argv)

    if not 1 <= args.page_size <= 1000:
        parser.error("--page-size는 1 이상 1000 이하여야 합니다")
    if args.limit_files is not None and args.limit_files <= 0:
        parser.error("--limit-files는 0보다 커야 합니다")
    if args.delay < 0:
        parser.error("--delay는 0 이상이어야 합니다")
    if args.timeout <= 0:
        parser.error("--timeout은 0보다 커야 합니다")
    if args.retries < 0:
        parser.error("--retries는 0 이상이어야 합니다")

    if args.card_types.strip().lower() == "all":
        args.selected_card_types = list(CARD_TYPE_KEYS)
    else:
        selected = [key.strip() for key in args.card_types.split(",") if key.strip()]
        unknown = [key for key in selected if key not in CARD_TYPE_KEYS]
        if unknown:
            parser.error(f"알 수 없는 --card-types 값: {', '.join(unknown)}")
        if not selected:
            parser.error("--card-types에 하나 이상을 지정해야 합니다")
        args.selected_card_types = selected
    return args


def run(args: argparse.Namespace) -> int:
    require_robots_allowed((PAGE_URL, LIST_API_URL, HISTORY_API_URL))
    output_root = args.output.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    collector = NhCardCollector(timeout=args.timeout, retries=args.retries)
    collector.warm_session()

    allocator = PathAllocator()
    listed_counts: Dict[str, Any] = {}
    product_counts: Dict[str, int] = {}
    without_pdf_counts: Dict[str, int] = {}
    files: List[Dict[str, Any]] = []

    for card_type in CARD_TYPES:
        if card_type["key"] not in args.selected_card_types:
            continue
        total, products = collector.collect_products(
            card_type, args.brand, args.page_size, args.delay
        )
        card_files, without_pdf = build_files(card_type, products, allocator)
        listed_counts[card_type["key"]] = total
        product_counts[card_type["key"]] = len(products)
        without_pdf_counts[card_type["key"]] = without_pdf
        files.extend(card_files)
        LOG.info(
            "%s: 목록 %d건, 상품설명서 없는 행 %d건, 고유 PDF %d건",
            card_type["label"],
            len(products),
            without_pdf,
            len(card_files),
        )

    metadata: Dict[str, Any] = {
        "schema_version": 1,
        "source_page": PAGE_URL,
        "list_api": LIST_API_URL,
        "history_api": HISTORY_API_URL,
        "created_at": now_iso(),
        "filter": {
            "card_types": args.selected_card_types,
            "brand": args.brand,
            "document_style": DOCUMENT_STYLE_NAME,
        },
        "listed_counts": listed_counts,
        "collected_products": product_counts,
        "rows_without_pdf": without_pdf_counts,
        "unique_files": len(files),
        "limit_files": args.limit_files,
        "metadata_only": bool(args.metadata_only),
        "files": files,
    }
    save_state(output_root, metadata)

    if args.with_history:
        targets = [record for record in files if record.get("history_seq")]
        for index, record in enumerate(targets, start=1):
            try:
                record["change_history"] = collector.fetch_change_history(
                    record["product_name"], record["history_seq"]
                )
            except CollectorError as exc:
                record["change_history_error"] = str(exc)
                LOG.warning("변경이력 조회 실패: %s (%s)", record["product_name"], exc)
            if index % 50 == 0:
                LOG.info("변경이력 %d/%d", index, len(targets))
                save_state(output_root, metadata)
            if args.delay > 0:
                time.sleep(args.delay)
        save_state(output_root, metadata)

    summary = {
        "output": str(output_root),
        "listed_counts": listed_counts,
        "collected_products": product_counts,
        "rows_without_pdf": without_pdf_counts,
        "unique_files": len(files),
    }

    if args.metadata_only:
        metadata["run_status"] = "metadata_only"
        metadata["completed_at"] = now_iso()
        save_state(output_root, metadata)
        print(json.dumps({"status": "metadata_only", **summary}, ensure_ascii=False))
        return 0

    targets = files if args.limit_files is None else files[: args.limit_files]
    for index, record in enumerate(targets, start=1):
        destination = output_root / Path(record["relative_path"])
        try:
            result = collector.download_pdf(
                record["url"], destination, overwrite=args.overwrite
            )
            record.update(result)
            record.pop("error", None)
            LOG.info(
                "[%d/%d %s] %s (%d바이트)",
                index,
                len(targets),
                record["status"],
                record["product_name"],
                record["size"],
            )
        except (CollectorError, OSError) as exc:
            record.update({"status": "failed", "error": str(exc), "failed_at": now_iso()})
            LOG.error("[failed] %s: %s", record["product_name"], exc)
            save_state(output_root, metadata)
            if args.fail_fast:
                raise

        if index % 20 == 0:
            save_state(output_root, metadata)
        if args.delay > 0 and index < len(targets):
            time.sleep(args.delay)

    failures = failure_records(files)
    metadata["run_status"] = "completed_with_errors" if failures else "completed"
    metadata["completed_at"] = now_iso()
    save_state(output_root, metadata)
    print(
        json.dumps(
            {
                "status": metadata["run_status"],
                **summary,
                "attempted_files": len(targets),
                "status_counts": metadata["status_counts"],
                "failures": len(failures),
            },
            ensure_ascii=False,
        )
    )
    return 1 if failures else 0


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        return run(args)
    except KeyboardInterrupt:
        LOG.error("사용자에 의해 중단됐습니다. 다시 실행하면 정상 PDF는 건너뜁니다.")
        return 130
    except (CollectorError, OSError) as exc:
        LOG.error("수집 실패: %s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
