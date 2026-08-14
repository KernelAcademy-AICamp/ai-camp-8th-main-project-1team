#!/usr/bin/env python3
"""롯데카드 상품공시실의 발급 중인 카드 상품 약관 PDF 수집기.

검색 API의 ISU_E_YN이 "Y"인 발급종료 상품은 제외한다. 남은 상품의 현재
상품설명서(약관) PDF를 검증하여 상품별 디렉터리에 원자적으로 저장한다.
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
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from collector_policy import USER_AGENT, require_robots_allowed


BASE_URL = "https://www.lottecard.co.kr"
PAGE_URL = BASE_URL + "/app/LPCMNPD_V100.lc"
SEARCH_API_URL = BASE_URL + "/app/LPSCHAA_V100.lc"
FILE_BASE_URL = "https://image.lottecard.co.kr/UploadFiles/cardProvisionPath/"

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

LOG = logging.getLogger("lottecard")


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


def display_date(value: Any) -> Optional[str]:
    raw = str(value or "").strip()
    if len(raw) == 8 and raw.isdigit():
        return f"{raw[:4]}-{raw[4:6]}-{raw[6:8]}"
    return raw or None


def build_file_url(filename: Any) -> str:
    name = normalize_text(filename)
    if not name:
        raise CollectorError("OCY_FILE_NM이 비어 있습니다")
    return FILE_BASE_URL + urllib.parse.quote(name, safe="()[]_-.,~")


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


def unwrap_search_collection(response: Dict[str, Any]) -> Dict[str, Any]:
    status = response.get("Status") or {}
    code = status.get("code")
    if code not in (None, 0, "0"):
        raise CollectorError(
            "롯데카드 API 오류: " + json.dumps(status, ensure_ascii=False)
        )

    content = response.get("Content")
    if isinstance(content, str):
        try:
            inner = json.loads(content.strip())
        except json.JSONDecodeError as exc:
            raise CollectorError(
                f"검색 API Content가 올바른 JSON이 아닙니다: {content[:500]!r}"
            ) from exc
    elif isinstance(content, dict):
        inner = content
    else:
        raise CollectorError("검색 API 응답의 Content가 문자열 또는 객체가 아닙니다")

    result = inner.get("result")
    collections = result.get("collection") if isinstance(result, dict) else None
    if not isinstance(collections, list):
        raise CollectorError("검색 API 응답에 result.collection 배열이 없습니다")

    for collection in collections:
        if isinstance(collection, dict) and collection.get("id") == "disclosure":
            return collection
    if len(collections) == 1 and isinstance(collections[0], dict):
        return collections[0]
    raise CollectorError("검색 API 응답에서 disclosure 컬렉션을 찾지 못했습니다")


class LotteCardCollector:
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

    def fetch_batch(self, start: int, page_size: int) -> Tuple[int, List[Dict[str, Any]]]:
        payload = {
            "collection": "disclosure",
            "listcount": str(page_size),
            "startcount": str(start),
            "query": "",
        }
        request = urllib.request.Request(
            SEARCH_API_URL,
            data=urllib.parse.urlencode(payload).encode("utf-8"),
            method="POST",
            headers={
                "Accept": "application/json, text/javascript, */*; q=0.01",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer": PAGE_URL,
                "X-Requested-With": "XMLHttpRequest",
            },
        )
        raw = self._read_bytes(request, f"목록 startcount={start}")
        try:
            outer = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            preview = raw[:500].decode("utf-8", errors="replace")
            raise CollectorError(f"검색 API가 잘못된 JSON을 반환했습니다: {preview!r}") from exc
        if not isinstance(outer, dict):
            raise CollectorError("검색 API JSON 최상위 값이 객체가 아닙니다")

        collection = unwrap_search_collection(outer)
        try:
            total = int(collection["totalcount"])
        except (KeyError, TypeError, ValueError) as exc:
            raise CollectorError("검색 응답에 유효한 totalcount가 없습니다") from exc
        docs = collection.get("docs") or []
        if not isinstance(docs, list) or not all(isinstance(doc, dict) for doc in docs):
            raise CollectorError("검색 응답의 docs가 객체 배열이 아닙니다")
        return total, docs

    def collect_all(self, page_size: int, request_delay: float) -> Tuple[int, List[Dict[str, Any]]]:
        self.warm_session()
        expected_total: Optional[int] = None
        docs: List[Dict[str, Any]] = []
        seen_docids = set()
        start = 0

        while True:
            total, batch = self.fetch_batch(start, page_size)
            if expected_total is None:
                expected_total = total
            elif total != expected_total:
                raise CollectorError(
                    f"수집 도중 전체 건수가 {expected_total}에서 {total}(으)로 변경됐습니다"
                )
            if not batch:
                break

            for doc in batch:
                docid = str(doc.get("DOCID") or "").strip()
                if not docid:
                    raise CollectorError("DOCID가 비어 있는 상품이 있습니다")
                if docid in seen_docids:
                    raise CollectorError(f"페이지 사이 DOCID가 중복됐습니다: {docid}")
                seen_docids.add(docid)
                docs.append(doc)

            LOG.info("목록: 이번 %d건, 누적 %d/%d건", len(batch), len(docs), expected_total)
            if len(docs) >= expected_total:
                break
            start += len(batch)
            if request_delay > 0:
                time.sleep(request_delay)

        if expected_total is None:
            raise CollectorError("검색 API에서 전체 건수를 받지 못했습니다")
        if len(docs) != expected_total:
            raise CollectorError(
                f"목록 누락: API 전체 {expected_total}건, 실제 수집 {len(docs)}건"
            )
        return expected_total, docs

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


def build_products(docs: Iterable[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], int, int]:
    products: List[Dict[str, Any]] = []
    excluded_ended = 0
    missing_files = 0
    seen_paths = set()

    for doc in docs:
        if str(doc.get("ISU_E_YN") or "").strip().upper() == "Y":
            excluded_ended += 1
            continue

        docid = str(doc.get("DOCID") or "").strip()
        product_name = normalize_text(doc.get("VT_CD_KND_NM"))
        published_raw = str(doc.get("BULT_SDT") or "").strip()
        raw_filename = str(doc.get("OCY_FILE_NM") or "")
        filename = normalize_text(raw_filename)
        directory = safe_component(
            f"{published_raw or '게시일없음'}_{docid}_{product_name}",
            fallback=f"상품_{docid}",
        )

        files: List[Dict[str, Any]] = []
        if filename:
            stored_filename = safe_component(filename, fallback=f"약관_{docid}.pdf")
            relative_path = Path(directory, stored_filename).as_posix()
            if relative_path in seen_paths:
                raise CollectorError(f"저장 경로가 중복됩니다: {relative_path}")
            seen_paths.add(relative_path)
            files.append(
                {
                    "filename": filename,
                    "raw_filename": raw_filename,
                    "url": build_file_url(filename),
                    "relative_path": relative_path,
                    "status": "pending",
                }
            )
        else:
            missing_files += 1

        products.append(
            {
                "product_name": product_name,
                "docid": docid,
                "published_date": display_date(published_raw),
                "published_date_raw": published_raw,
                "issuance_ended": False,
                "issuance_ended_raw": str(doc.get("ISU_E_YN") or "").strip(),
                "relative_directory": directory,
                "files": files,
            }
        )
    return products, excluded_ended, missing_files


def summarize_status(products: Iterable[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for product in products:
        for file_info in product["files"]:
            status = str(file_info.get("status") or "unknown")
            counts[status] = counts.get(status, 0) + 1
    return counts


def failure_records(products: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    failures = []
    for product in products:
        for file_info in product["files"]:
            if file_info.get("status") != "failed":
                continue
            failures.append(
                {
                    "product_name": product["product_name"],
                    "docid": product["docid"],
                    "filename": file_info["filename"],
                    "url": file_info["url"],
                    "relative_path": file_info["relative_path"],
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
        description="롯데카드 발급 중인 상품의 현재 상품설명서(약관) PDF를 받습니다."
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "out" / "lottecard",
        help="출력 디렉터리(기본값: out/lottecard)",
    )
    parser.add_argument(
        "--metadata-only",
        action="store_true",
        help="목록과 JSON만 만들고 PDF는 받지 않기",
    )
    parser.add_argument(
        "--limit-products",
        type=int,
        default=None,
        help="필터 통과 상품 중 앞의 N개만 처리(시험 실행용)",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=100,
        help="검색 API 페이지당 건수(기본값: 100)",
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
    require_robots_allowed((PAGE_URL, SEARCH_API_URL, FILE_BASE_URL))
    output_root = args.output.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    collector = LotteCardCollector(timeout=args.timeout, retries=args.retries)
    api_total, docs = collector.collect_all(args.page_size, args.delay)
    products, excluded_ended, missing_files = build_products(docs)
    selected_before_limit = len(products)
    if args.limit_products is not None:
        products = products[: args.limit_products]

    total_files = sum(len(product["files"]) for product in products)
    metadata: Dict[str, Any] = {
        "schema_version": 1,
        "source_page": PAGE_URL,
        "search_api": SEARCH_API_URL,
        "created_at": now_iso(),
        "filter": {"exclude_when_ISU_E_YN": "Y"},
        "api_total_products": api_total,
        "excluded_issuance_ended": excluded_ended,
        "active_products_before_limit": selected_before_limit,
        "active_products_without_file": missing_files,
        "selected_products": len(products),
        "limit_products": args.limit_products,
        "total_files": total_files,
        "metadata_only": bool(args.metadata_only),
        "products": products,
    }
    save_state(output_root, metadata)
    LOG.info(
        "목록 완료: 전체 %d, 발급종료 제외 %d, 발급 중 %d, PDF %d",
        api_total,
        excluded_ended,
        selected_before_limit,
        total_files,
    )

    if args.metadata_only:
        metadata["run_status"] = "metadata_only"
        metadata["completed_at"] = now_iso()
        save_state(output_root, metadata)
        print(
            json.dumps(
                {
                    "status": "metadata_only",
                    "output": str(output_root),
                    "api_total_products": api_total,
                    "excluded_issuance_ended": excluded_ended,
                    "active_products": selected_before_limit,
                    "selected_products": len(products),
                    "total_files": total_files,
                },
                ensure_ascii=False,
            )
        )
        return 0

    processed = 0
    for product in products:
        for file_info in product["files"]:
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

    failures = failure_records(products)
    metadata["run_status"] = "completed_with_errors" if failures else "completed"
    metadata["completed_at"] = now_iso()
    save_state(output_root, metadata)
    print(
        json.dumps(
            {
                "status": metadata["run_status"],
                "output": str(output_root),
                "api_total_products": api_total,
                "excluded_issuance_ended": excluded_ended,
                "active_products": selected_before_limit,
                "selected_products": len(products),
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
