#!/usr/bin/env python3
"""KB국민카드 개인신용·개인체크 상품설명서 PDF 수집기.

금융약관 페이지를 카드분류코드 0(개인신용), 1(개인체크)로 순회한다.
발급중단일이 비어 있는 상품만 남기고, 각 상품설명서 열의 PDF 링크를
검증하여 원자적으로 저장한다. 외부 패키지는 필요하지 않다.
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


BASE_URL = "https://card.kbcard.com"
PAGE_URL = BASE_URL + "/SVC/DVIEW/HSHMCXCRSZZC0002"
PAGE_SIZE = 10
CATEGORIES = {
    "credit": {"card_type": "0", "label": "개인신용"},
    "check": {"card_type": "1", "label": "개인체크"},
}

DEFAULT_USER_AGENT = USER_AGENT
RETRYABLE_HTTP_STATUSES = {408, 425, 429, 500, 502, 503, 504}
FORBIDDEN_PATH_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
WHITESPACE = re.compile(r"\s+")
TOTAL_PATTERN = re.compile(
    r'<div[^>]*class=["\'][^"\']*\btotalNum\b[^"\']*["\'][^>]*>'
    r".*?<strong[^>]*>\s*([\d,]+)\s*</strong>",
    re.IGNORECASE | re.DOTALL,
)
DATE_PATTERN = re.compile(r"^(\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})$")
DETAIL_CODE_PATTERN = re.compile(r"goDetail\(\s*['\"]([^'\"]+)['\"]")
WINDOWS_RESERVED_NAMES = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}

LOG = logging.getLogger("kbcard")


class CollectorError(RuntimeError):
    """수집을 계속할 수 없는 오류."""


class PdfValidationError(CollectorError):
    """다운로드 결과가 정상 PDF가 아닐 때 발생한다."""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def normalize_text(value: Any) -> str:
    text = html.unescape(str(value or ""))
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


def normalize_date(value: Any) -> Tuple[Optional[str], Optional[str]]:
    text = normalize_text(value)
    match = DATE_PATTERN.match(text)
    if not match:
        return (text or None), (text or None)
    year, month, day = (int(part) for part in match.groups())
    return f"{year:04d}{month:02d}{day:02d}", f"{year:04d}-{month:02d}-{day:02d}"


def filename_from_url(url: str, fallback: str) -> str:
    parsed = urllib.parse.urlparse(url)
    name = urllib.parse.unquote(os.path.basename(parsed.path))
    return normalize_text(name) or fallback


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


class FinancialTermsTableParser(HTMLParser):
    """금융약관의 tblH crossLine 상품 테이블만 파싱한다."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.selected_card_type: Optional[str] = None
        self.in_target_table = False
        self.table_depth = 0
        self.in_row = False
        self.in_cell = False
        self.current_cell: Optional[Dict[str, Any]] = None
        self.current_cells: List[Dict[str, Any]] = []
        self.rows: List[List[Dict[str, Any]]] = []

    @staticmethod
    def _classes(attrs: Dict[str, Optional[str]]) -> set:
        return set((attrs.get("class") or "").split())

    def handle_starttag(self, tag: str, attrs_list: List[Tuple[str, Optional[str]]]) -> None:
        attrs = dict(attrs_list)
        if tag == "input" and attrs.get("name") == "카드분류코드":
            self.selected_card_type = str(attrs.get("value") or "").strip()

        if tag == "table":
            classes = self._classes(attrs)
            if self.in_target_table:
                self.table_depth += 1
            elif "tblH" in classes and "crossLine" in classes:
                self.in_target_table = True
                self.table_depth = 1
            return

        if not self.in_target_table:
            return
        if tag == "tr":
            self.in_row = True
            self.current_cells = []
        elif tag == "td" and self.in_row:
            self.in_cell = True
            self.current_cell = {"text": [], "links": []}
        elif tag == "a" and self.in_cell and self.current_cell is not None:
            self.current_cell["links"].append(
                {
                    "href": str(attrs.get("href") or "").strip(),
                    "title": normalize_text(attrs.get("title")),
                }
            )

    def handle_data(self, data: str) -> None:
        if self.in_cell and self.current_cell is not None:
            self.current_cell["text"].append(data)

    def handle_endtag(self, tag: str) -> None:
        if not self.in_target_table:
            return
        if tag == "td" and self.in_cell and self.current_cell is not None:
            self.current_cell["text"] = normalize_text("".join(self.current_cell["text"]))
            self.current_cells.append(self.current_cell)
            self.current_cell = None
            self.in_cell = False
        elif tag == "tr" and self.in_row:
            if self.current_cells:
                self.rows.append(self.current_cells)
            self.current_cells = []
            self.in_row = False
        elif tag == "table":
            self.table_depth -= 1
            if self.table_depth <= 0:
                self.in_target_table = False
                self.table_depth = 0


def extract_total_count(page_html: str) -> int:
    match = TOTAL_PATTERN.search(page_html)
    if not match:
        raise CollectorError("페이지에서 전체 상품 건수를 찾지 못했습니다")
    return int(match.group(1).replace(",", ""))


def parse_page_products(page_html: str, expected_card_type: str) -> List[Dict[str, Any]]:
    parser = FinancialTermsTableParser()
    parser.feed(page_html)
    parser.close()
    if parser.selected_card_type != expected_card_type:
        raise CollectorError(
            f"요청 카드분류코드={expected_card_type}, 응답={parser.selected_card_type!r}"
        )

    products: List[Dict[str, Any]] = []
    for cells in parser.rows:
        if len(cells) < 5:
            continue
        product_name = normalize_text(cells[0]["text"])
        if not product_name:
            continue

        file_links = []
        seen_urls = set()
        for link in cells[1]["links"]:
            href = link["href"]
            if not href or href.lower().startswith("javascript:"):
                continue
            url = urllib.parse.urljoin(PAGE_URL, href)
            if not urllib.parse.urlparse(url).path.lower().endswith(".pdf"):
                continue
            if url in seen_urls:
                continue
            seen_urls.add(url)
            file_links.append(
                {"url": url, "title": link["title"], "href": href}
            )

        card_code = ""
        for link in cells[2]["links"]:
            match = DETAIL_CODE_PATTERN.search(link["href"])
            if match:
                card_code = match.group(1).strip()
                break

        products.append(
            {
                "product_name": product_name,
                "card_code": card_code,
                "launch_date_text": normalize_text(cells[-2]["text"]),
                "stop_date_text": normalize_text(cells[-1]["text"]),
                "file_links": file_links,
            }
        )

    if not products:
        raise CollectorError("금융약관 상품 테이블에서 상품을 찾지 못했습니다")
    return products


class KBCardCollector:
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
            headers={"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"},
        )
        self._read_bytes(request, "금융약관 초기 페이지")

    def fetch_page(self, card_type: str, page_number: int) -> str:
        payload = {
            "카드분류코드": card_type,
            "카드검색그룹코드": "",
            "pageCount": str(page_number),
            "카드명": "",
        }
        request = urllib.request.Request(
            PAGE_URL,
            data=urllib.parse.urlencode(payload).encode("utf-8"),
            method="POST",
            headers={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer": PAGE_URL,
            },
        )
        raw = self._read_bytes(request, f"카드분류코드={card_type} {page_number}페이지")
        return raw.decode("utf-8", errors="replace")

    def collect_category(
        self, card_type: str, label: str, request_delay: float
    ) -> Tuple[int, int, List[Dict[str, Any]]]:
        first_html = self.fetch_page(card_type, 1)
        total = extract_total_count(first_html)
        total_pages = (total + PAGE_SIZE - 1) // PAGE_SIZE
        products: List[Dict[str, Any]] = []
        seen_keys = set()

        for page_number in range(1, total_pages + 1):
            page_html = first_html if page_number == 1 else self.fetch_page(card_type, page_number)
            batch = parse_page_products(page_html, card_type)
            for product in batch:
                identity = (
                    product["card_code"],
                    product["product_name"],
                    product["launch_date_text"],
                    product["stop_date_text"],
                    tuple(link["url"] for link in product["file_links"]),
                )
                if identity in seen_keys:
                    raise CollectorError(
                        f"{label} 페이지 사이에 동일 상품이 중복됐습니다: {product['product_name']}"
                    )
                seen_keys.add(identity)
                products.append(product)

            LOG.info(
                "%s %d/%d페이지: %d건, 누적 %d/%d건",
                label,
                page_number,
                total_pages,
                len(batch),
                len(products),
                total,
            )
            if request_delay > 0 and page_number < total_pages:
                time.sleep(request_delay)

        if len(products) != total:
            raise CollectorError(
                f"{label} 목록 누락: 페이지 전체 {total}건, 실제 수집 {len(products)}건"
            )
        return total, total_pages, products

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
                    content_disposition = response.headers.get("Content-Disposition", "")
                    raw_length = response.headers.get("Content-Length")
                    expected_length = int(raw_length) if raw_length and raw_length.isdigit() else None
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
                    "content_disposition": content_disposition,
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


def build_active_products(
    raw_products: Iterable[Dict[str, Any]], category_key: str
) -> Tuple[List[Dict[str, Any]], int, int]:
    products: List[Dict[str, Any]] = []
    excluded_stopped = 0
    active_without_pdf = 0
    seen_paths = set()

    for raw_product in raw_products:
        stop_date_text = normalize_text(raw_product.get("stop_date_text"))
        if stop_date_text:
            excluded_stopped += 1
            continue

        name = normalize_text(raw_product.get("product_name"))
        card_code = str(raw_product.get("card_code") or "").strip()
        launch_raw, launch_date = normalize_date(raw_product.get("launch_date_text"))
        directory = safe_component(
            f"{launch_raw or '출시일없음'}_{card_code or '코드없음'}_{name}",
            fallback=f"상품_{card_code or len(products) + 1}",
        )

        files: List[Dict[str, Any]] = []
        links = raw_product.get("file_links") or []
        for index, link in enumerate(links, start=1):
            url = str(link.get("url") or "").strip()
            filename = filename_from_url(url, fallback=f"상품설명서_{index}.pdf")
            stored_filename = safe_component(
                f"{index:02d}_{filename}", fallback=f"{index:02d}_상품설명서.pdf"
            )
            relative_path = Path(category_key, directory, stored_filename).as_posix()
            if relative_path in seen_paths:
                raise CollectorError(f"저장 경로가 중복됩니다: {relative_path}")
            seen_paths.add(relative_path)
            files.append(
                {
                    "filename": filename,
                    "title": normalize_text(link.get("title")),
                    "url": url,
                    "relative_path": relative_path,
                    "status": "pending",
                }
            )

        if not files:
            active_without_pdf += 1
        products.append(
            {
                "product_name": name,
                "card_code": card_code,
                "launch_date": launch_date,
                "launch_date_raw": launch_raw,
                "stop_date": None,
                "relative_directory": Path(category_key, directory).as_posix(),
                "files": files,
            }
        )
    return products, excluded_stopped, active_without_pdf


def iter_products(metadata: Dict[str, Any]) -> Iterable[Tuple[str, Dict[str, Any]]]:
    for category_key, category in metadata["categories"].items():
        for product in category["products"]:
            yield category_key, product


def summarize_status(metadata: Dict[str, Any]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for _, product in iter_products(metadata):
        for file_info in product["files"]:
            status = str(file_info.get("status") or "unknown")
            counts[status] = counts.get(status, 0) + 1
    return counts


def failure_records(metadata: Dict[str, Any]) -> List[Dict[str, Any]]:
    failures = []
    for category_key, product in iter_products(metadata):
        for file_info in product["files"]:
            if file_info.get("status") != "failed":
                continue
            failures.append(
                {
                    "category": category_key,
                    "product_name": product["product_name"],
                    "card_code": product["card_code"],
                    "filename": file_info["filename"],
                    "url": file_info["url"],
                    "relative_path": file_info["relative_path"],
                    "error": file_info.get("error", ""),
                }
            )
    return failures


def save_state(output_root: Path, metadata: Dict[str, Any]) -> None:
    metadata["updated_at"] = now_iso()
    metadata["status_counts"] = summarize_status(metadata)
    failures = failure_records(metadata)
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
        description=(
            "KB국민카드 개인신용·개인체크 중 발급중단일이 없는 상품의 "
            "PDF 링크를 수집하고 다운로드합니다."
        )
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "out" / "kbcard",
        help="출력 디렉터리(기본값: out/kbcard)",
    )
    parser.add_argument(
        "--category",
        choices=("all", "credit", "check"),
        default="all",
        help="수집 분류(기본값: all)",
    )
    parser.add_argument(
        "--metadata-only",
        action="store_true",
        help="PDF 링크와 JSON만 만들고 파일은 받지 않기",
    )
    parser.add_argument(
        "--limit-products",
        type=int,
        default=None,
        help="각 분류에서 앞의 N개 활성 상품만 처리(시험 실행용)",
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
        default=60.0,
        help="요청 제한시간 초(기본값: 60)",
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

    if args.limit_products is not None and args.limit_products <= 0:
        parser.error("--limit-products는 0보다 커야 합니다")
    if args.delay < 0:
        parser.error("--delay는 0 이상이어야 합니다")
    if args.timeout <= 0:
        parser.error("--timeout은 0보다 커야 합니다")
    if args.retries < 0:
        parser.error("--retries는 0 이상이어야 합니다")
    return args


def run(args: argparse.Namespace) -> int:
    require_robots_allowed((PAGE_URL, BASE_URL + "/SVC/"))
    output_root = args.output.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    collector = KBCardCollector(timeout=args.timeout, retries=args.retries)
    collector.warm_session()

    category_keys = list(CATEGORIES) if args.category == "all" else [args.category]
    categories: Dict[str, Any] = {}
    for category_key in category_keys:
        config = CATEGORIES[category_key]
        site_total, total_pages, raw_products = collector.collect_category(
            config["card_type"], config["label"], args.delay
        )
        products, excluded_stopped, active_without_pdf = build_active_products(
            raw_products, category_key
        )
        active_before_limit = len(products)
        if args.limit_products is not None:
            products = products[: args.limit_products]
        categories[category_key] = {
            "label": config["label"],
            "card_type": config["card_type"],
            "site_total_products": site_total,
            "total_pages": total_pages,
            "excluded_with_stop_date": excluded_stopped,
            "active_products_before_limit": active_before_limit,
            "active_products_without_pdf": active_without_pdf,
            "selected_products": len(products),
            "total_files": sum(len(product["files"]) for product in products),
            "products": products,
        }
        LOG.info(
            "%s 완료: 전체 %d, 발급중단일 있음 %d 제외, 활성 %d, 선택 %d",
            config["label"],
            site_total,
            excluded_stopped,
            active_before_limit,
            len(products),
        )

    metadata: Dict[str, Any] = {
        "schema_version": 1,
        "source_page": PAGE_URL,
        "created_at": now_iso(),
        "filter": {"include_only_when_stop_date_is_empty": True},
        "category_option": args.category,
        "limit_products_per_category": args.limit_products,
        "metadata_only": bool(args.metadata_only),
        "categories": categories,
    }
    save_state(output_root, metadata)

    if args.metadata_only:
        metadata["run_status"] = "metadata_only"
        metadata["completed_at"] = now_iso()
        save_state(output_root, metadata)
        print(
            json.dumps(
                {
                    "status": "metadata_only",
                    "output": str(output_root),
                    "categories": {
                        key: {
                            "site_total_products": value["site_total_products"],
                            "excluded_with_stop_date": value["excluded_with_stop_date"],
                            "active_products": value["active_products_before_limit"],
                            "selected_products": value["selected_products"],
                            "total_files": value["total_files"],
                        }
                        for key, value in categories.items()
                    },
                },
                ensure_ascii=False,
            )
        )
        return 0

    total_files = sum(category["total_files"] for category in categories.values())
    processed = 0
    for category_key, product in iter_products(metadata):
        for file_info in product["files"]:
            destination = output_root / Path(file_info["relative_path"])
            try:
                result = collector.download_pdf(
                    file_info["url"], destination, overwrite=args.overwrite
                )
                file_info.update(result)
                file_info.pop("error", None)
                LOG.info(
                    "[%s] %s / %s / %s (%d바이트)",
                    file_info["status"],
                    CATEGORIES[category_key]["label"],
                    product["product_name"],
                    file_info["filename"],
                    file_info["size"],
                )
            except (CollectorError, OSError) as exc:
                file_info.update(
                    {
                        "status": "failed",
                        "error": str(exc),
                        "failed_at": now_iso(),
                    }
                )
                LOG.error(
                    "[failed] %s / %s / %s: %s",
                    CATEGORIES[category_key]["label"],
                    product["product_name"],
                    file_info["filename"],
                    exc,
                )
                save_state(output_root, metadata)
                if args.fail_fast:
                    raise

            processed += 1
            save_state(output_root, metadata)
            if args.delay > 0 and processed < total_files:
                time.sleep(args.delay)

    failures = failure_records(metadata)
    metadata["run_status"] = "completed_with_errors" if failures else "completed"
    metadata["completed_at"] = now_iso()
    save_state(output_root, metadata)
    print(
        json.dumps(
            {
                "status": metadata["run_status"],
                "output": str(output_root),
                "status_counts": metadata["status_counts"],
                "failures": len(failures),
                "categories": {
                    key: {
                        "site_total_products": value["site_total_products"],
                        "excluded_with_stop_date": value["excluded_with_stop_date"],
                        "active_products": value["active_products_before_limit"],
                        "selected_products": value["selected_products"],
                        "total_files": value["total_files"],
                    }
                    for key, value in categories.items()
                },
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
