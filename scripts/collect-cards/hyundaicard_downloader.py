#!/usr/bin/env python3
"""현대카드 카드이용안내 PDF 전체 수집기.

현대카드 상품 페이지에서 상품명, 출시일, sqno를 읽고 각 상품의 안내서
목록 API를 호출한다. 모든 PDF는 구조를 검증한 뒤 원자적으로 저장하며,
재실행하면 기존 정상 PDF를 건너뛴다. 외부 패키지는 필요하지 않다.
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


BASE_URL = "https://www.hyundaicard.com"
PRODUCT_PAGE_URL = BASE_URL + "/cpu/ug/CPUUG2001_04.hc"
FILE_LIST_API_URL = BASE_URL + "/cpu/ug/apiCPUUG2001_0404.hc"
FILE_BASE_URL = BASE_URL + "/upload/card/"

DEFAULT_USER_AGENT = USER_AGENT
RETRYABLE_HTTP_STATUSES = {408, 425, 429, 500, 502, 503, 504}
FORBIDDEN_PATH_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
WHITESPACE = re.compile(r"\s+")
LAUNCH_DATE_PATTERN = re.compile(r"(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})")
WINDOWS_RESERVED_NAMES = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}

LOG = logging.getLogger("hyundaicard")


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


def safe_component(value: Any, fallback: str, max_length: int = 150) -> str:
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


def parse_launch_date(value: Any) -> Tuple[Optional[str], Optional[str]]:
    text = normalize_text(value)
    match = LAUNCH_DATE_PATTERN.search(text)
    if not match:
        return None, None
    year, month, day = (int(part) for part in match.groups())
    raw = f"{year:04d}{month:02d}{day:02d}"
    return raw, f"{year:04d}-{month:02d}-{day:02d}"


def parse_revision_date(value: Any) -> Tuple[Optional[str], Optional[str]]:
    raw = str(value or "").strip()
    if not raw or raw == "99999999":
        return None, None
    if len(raw) == 8 and raw.isdigit():
        return raw, f"{raw[:4]}-{raw[4:6]}-{raw[6:8]}"
    return raw, raw


def build_file_url(filename: Any) -> str:
    name = normalize_text(filename)
    if not name:
        raise CollectorError("apndFileNm이 비어 있습니다")
    encoded = urllib.parse.quote(name, safe="()[]_-.,~")
    return FILE_BASE_URL + encoded


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


class HyundaiProductParser(HTMLParser):
    """출시일이 없는 상품도 누락하지 않는 상품 목록 파서.

    한 상품 아래 `li.divr_txt`가 둘까지 온다 — `상품출시일:…`과 `발급중단일:…`.
    라벨을 보고 갈라 담아야 한다. 라벨을 안 보면 뒤에 오는 발급중단일이
    출시일 자리를 덮어써서, 중단된 상품의 출시일이 중단일로 바뀐다.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.capture: Optional[str] = None
        self.buffer: List[str] = []
        self.pending_name: Optional[str] = None
        self.pending_launch_text: Optional[str] = None
        self.pending_stop_text: Optional[str] = None
        self.products: List[Dict[str, Any]] = []
        self.seen_sqnos = set()

    @staticmethod
    def _classes(attrs: Dict[str, Optional[str]]) -> set:
        return set((attrs.get("class") or "").split())

    def handle_starttag(self, tag: str, attrs_list: List[Tuple[str, Optional[str]]]) -> None:
        attrs = dict(attrs_list)
        classes = self._classes(attrs)

        if tag == "p" and "h4_m_lt" in classes:
            self.capture = "name"
            self.buffer = []
            self.pending_name = None
            self.pending_launch_text = None
            self.pending_stop_text = None
            return

        if tag == "li" and "divr_txt" in classes and self.pending_name is not None:
            self.capture = "divr"
            self.buffer = []
            return

        if tag != "a" or "sqno" not in attrs:
            return

        sqno = str(attrs.get("sqno") or "").strip()
        if not sqno.isdigit() or sqno in self.seen_sqnos:
            return
        if not self.pending_name:
            raise CollectorError(f"sqno={sqno} 앞에서 상품명을 찾지 못했습니다")

        launch_raw, launch_date = parse_launch_date(self.pending_launch_text)
        stop_raw, stop_date = parse_launch_date(self.pending_stop_text)
        self.products.append(
            {
                "product_name": self.pending_name,
                "launch_date": launch_date,
                "launch_date_raw": launch_raw,
                "stop_date": stop_date,
                "stop_date_raw": stop_raw,
                "issued": stop_raw is None,
                "sqno": sqno,
            }
        )
        self.seen_sqnos.add(sqno)
        self.pending_name = None
        self.pending_launch_text = None
        self.pending_stop_text = None

    def handle_data(self, data: str) -> None:
        if self.capture:
            self.buffer.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "p" and self.capture == "name":
            self.pending_name = normalize_text("".join(self.buffer))
            self.capture = None
            self.buffer = []
        elif tag == "li" and self.capture == "divr":
            text = normalize_text("".join(self.buffer))
            # 라벨로 갈라 담는다. 라벨이 없으면 출시일 자리로 본다(기존 동작).
            if "발급중단일" in text:
                self.pending_stop_text = text
            else:
                self.pending_launch_text = text
            self.capture = None
            self.buffer = []


def parse_products(page_html: str) -> List[Dict[str, Any]]:
    parser = HyundaiProductParser()
    parser.feed(page_html)
    parser.close()
    if not parser.products:
        raise CollectorError("상품을 찾지 못했습니다. 현대카드 HTML 구조를 확인하십시오")
    return parser.products


def unwrap_file_rows(response: Dict[str, Any]) -> List[Dict[str, Any]]:
    """직접 HTTP 응답(bdy.result)과 브라우저 래퍼 응답(result)을 모두 처리한다."""
    header = response.get("hdr") or {}
    result_code = str(header.get("rsltCd") or "").strip()
    if result_code and result_code != "0000":
        raise CollectorError(
            "현대카드 API 오류 "
            + result_code
            + ": "
            + str(header.get("rsltMsg") or "메시지 없음")
        )

    body = response.get("bdy")
    container = body if isinstance(body, dict) else response
    result = container.get("result")
    if not isinstance(result, dict):
        result = response.get("result")
    if not isinstance(result, dict):
        raise CollectorError("파일 API 응답에 bdy.result 또는 result가 없습니다")

    rows = result.get("cpuug2001DAO") or []
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise CollectorError("파일 API의 cpuug2001DAO가 객체 배열이 아닙니다")
    return rows


class HyundaiCardCollector:
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

    def fetch_products(self) -> List[Dict[str, Any]]:
        request = urllib.request.Request(
            PRODUCT_PAGE_URL,
            method="GET",
            headers={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            },
        )
        raw = self._read_bytes(request, "상품 페이지")
        return parse_products(raw.decode("utf-8", errors="replace"))

    def fetch_file_rows(self, sqno: str) -> List[Dict[str, Any]]:
        payload = {
            "pgNo": "",
            "sqno": sqno,
            "agmRrfmDt": "",
            "cardDcrpSqno": "00",
            "srchForKwrdCn": "",
        }
        request = urllib.request.Request(
            FILE_LIST_API_URL,
            data=urllib.parse.urlencode(payload).encode("utf-8"),
            method="POST",
            headers={
                "Accept": "application/json, text/javascript, */*; q=0.01",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer": PRODUCT_PAGE_URL,
                "X-Requested-With": "XMLHttpRequest",
            },
        )
        raw = self._read_bytes(request, f"sqno={sqno} 파일 목록")
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            preview = raw[:500].decode("utf-8", errors="replace")
            raise CollectorError(f"sqno={sqno}: 잘못된 JSON 응답: {preview!r}") from exc
        if not isinstance(parsed, dict):
            raise CollectorError(f"sqno={sqno}: JSON 최상위 값이 객체가 아닙니다")
        return unwrap_file_rows(parsed)

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
                    "Referer": PRODUCT_PAGE_URL,
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


def make_product_record(product: Dict[str, Any], rows: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    product_name = normalize_text(product["product_name"])
    sqno = str(product["sqno"])
    launch_raw = product.get("launch_date_raw")
    directory = safe_component(
        f"{launch_raw or '출시일없음'}_{sqno}_{product_name}",
        fallback=f"상품_{sqno}",
    )
    files: List[Dict[str, Any]] = []
    seen_paths = set()

    for index, row in enumerate(rows, start=1):
        raw_filename = str(row.get("apndFileNm") or "")
        filename = normalize_text(raw_filename)
        if not filename:
            LOG.warning("%s(%s): 파일명이 빈 첨부를 건너뜁니다", product_name, sqno)
            continue

        revision_raw, revision_date = parse_revision_date(row.get("agmRrfmDt"))
        safe_filename = safe_component(filename, fallback=f"안내서_{index}.pdf")
        stored_filename = safe_component(
            f"{index:02d}_{revision_raw or '개정일없음'}_{safe_filename}",
            fallback=f"{index:02d}_안내서.pdf",
        )
        relative_path = Path(directory, stored_filename).as_posix()
        if relative_path in seen_paths:
            raise CollectorError(f"{product_name}: 저장 경로가 중복됩니다: {relative_path}")
        seen_paths.add(relative_path)

        files.append(
            {
                "filename": filename,
                "raw_filename": raw_filename,
                "revision_date": revision_date,
                "revision_date_raw": revision_raw,
                "url": build_file_url(filename),
                "relative_path": relative_path,
                "status": "pending",
            }
        )

    return {
        "product_name": product_name,
        "launch_date": product.get("launch_date"),
        "launch_date_raw": launch_raw,
        "stop_date": product.get("stop_date"),
        "stop_date_raw": product.get("stop_date_raw"),
        "issued": product.get("issued"),
        "sqno": sqno,
        "relative_directory": directory,
        "file_lookup_status": "success",
        "files": files,
    }


def summarize_status(products: Iterable[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for product in products:
        for file_info in product.get("files") or []:
            status = str(file_info.get("status") or "unknown")
            counts[status] = counts.get(status, 0) + 1
    return counts


def failure_records(products: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    failures: List[Dict[str, Any]] = []
    for product in products:
        if product.get("file_lookup_status") == "failed":
            failures.append(
                {
                    "stage": "file_list",
                    "product_name": product.get("product_name"),
                    "sqno": product.get("sqno"),
                    "error": product.get("file_lookup_error", ""),
                }
            )
        for file_info in product.get("files") or []:
            if file_info.get("status") == "failed":
                failures.append(
                    {
                        "stage": "download",
                        "product_name": product.get("product_name"),
                        "sqno": product.get("sqno"),
                        "filename": file_info.get("filename"),
                        "url": file_info.get("url"),
                        "relative_path": file_info.get("relative_path"),
                        "error": file_info.get("error", ""),
                    }
                )
    return failures


def save_state(output_root: Path, metadata: Dict[str, Any]) -> None:
    metadata["updated_at"] = now_iso()
    metadata["status_counts"] = summarize_status(metadata["products"])
    failures = failure_records(metadata["products"])
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
        description="현대카드의 모든 카드이용안내 PDF를 상품별로 저장합니다."
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "out" / "hyundaicard",
        help="출력 디렉터리(기본값: out/hyundaicard)",
    )
    parser.add_argument(
        "--metadata-only",
        action="store_true",
        help="상품별 파일 목록과 JSON만 만들고 PDF는 받지 않기",
    )
    parser.add_argument(
        "--limit-products",
        type=int,
        default=None,
        help="앞에서부터 지정한 상품 수만 처리(시험 실행용)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.25,
        help="정상 요청 사이 대기 초(기본값: 0.25)",
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
        help="첫 오류에서 즉시 중단",
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
    require_robots_allowed((PRODUCT_PAGE_URL, FILE_LIST_API_URL, FILE_BASE_URL))
    output_root = args.output.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    collector = HyundaiCardCollector(timeout=args.timeout, retries=args.retries)

    products_from_page = collector.fetch_products()
    page_total = len(products_from_page)
    if args.limit_products is not None:
        products_from_page = products_from_page[: args.limit_products]

    metadata: Dict[str, Any] = {
        "schema_version": 1,
        "source_page": PRODUCT_PAGE_URL,
        "file_list_api": FILE_LIST_API_URL,
        "created_at": now_iso(),
        "page_total_products": page_total,
        "selected_products": len(products_from_page),
        "limit_products": args.limit_products,
        "metadata_only": bool(args.metadata_only),
        "run_status": "collecting_file_lists",
        "products": [],
    }
    save_state(output_root, metadata)

    for index, product in enumerate(products_from_page, start=1):
        LOG.info(
            "파일 목록 %d/%d: %s (sqno=%s)",
            index,
            len(products_from_page),
            product["product_name"],
            product["sqno"],
        )
        try:
            rows = collector.fetch_file_rows(product["sqno"])
            record = make_product_record(product, rows)
            LOG.info("  파일 %d건", len(record["files"]))
        except (CollectorError, OSError) as exc:
            LOG.error("  파일 목록 실패: %s", exc)
            record = {
                **product,
                "relative_directory": safe_component(
                    f"{product.get('launch_date_raw') or '출시일없음'}_"
                    f"{product['sqno']}_{product['product_name']}",
                    fallback=f"상품_{product['sqno']}",
                ),
                "file_lookup_status": "failed",
                "file_lookup_error": str(exc),
                "files": [],
            }
            if args.fail_fast:
                metadata["products"].append(record)
                save_state(output_root, metadata)
                raise

        metadata["products"].append(record)
        save_state(output_root, metadata)
        if args.delay > 0 and index < len(products_from_page):
            time.sleep(args.delay)

    metadata["total_files"] = sum(
        len(product.get("files") or []) for product in metadata["products"]
    )

    if args.metadata_only:
        failures = failure_records(metadata["products"])
        metadata["run_status"] = (
            "metadata_completed_with_errors" if failures else "metadata_completed"
        )
        metadata["completed_at"] = now_iso()
        save_state(output_root, metadata)
        print(
            json.dumps(
                {
                    "status": metadata["run_status"],
                    "output": str(output_root),
                    "page_total_products": page_total,
                    "selected_products": len(products_from_page),
                    "total_files": metadata["total_files"],
                    "failures": len(failures),
                },
                ensure_ascii=False,
            )
        )
        return 1 if failures else 0

    metadata["run_status"] = "downloading"
    save_state(output_root, metadata)
    processed = 0
    total_files = metadata["total_files"]

    for product in metadata["products"]:
        for file_info in product.get("files") or []:
            destination = output_root / Path(file_info["relative_path"])
            try:
                result = collector.download_pdf(
                    file_info["url"], destination, overwrite=args.overwrite
                )
                file_info.update(result)
                file_info.pop("error", None)
                LOG.info(
                    "[%s] %s / %s (%d바이트)",
                    file_info["status"],
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
                    "[failed] %s / %s: %s",
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

    failures = failure_records(metadata["products"])
    metadata["run_status"] = "completed_with_errors" if failures else "completed"
    metadata["completed_at"] = now_iso()
    save_state(output_root, metadata)
    print(
        json.dumps(
            {
                "status": metadata["run_status"],
                "output": str(output_root),
                "page_total_products": page_total,
                "selected_products": len(products_from_page),
                "total_files": total_files,
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
