#!/usr/bin/env python3
"""삼성카드 신용카드 상품약관 공시의 PDF를 수집한다.

외부 패키지가 필요하지 않다. 목록 API를 페이지 단위로 조회하고, 제목에
"중단"이 포함된 상품을 제외한 뒤 모든 PDF를 검증하여 원자적으로 저장한다.
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


BASE_URL = "https://www.samsungcard.com"
LIST_PAGE_URL = (
    BASE_URL
    + "/company/IR/announce/product-conditions/UHPPCI0261M0.jsp"
)
DETAIL_PAGE_URL = (
    BASE_URL
    + "/company/IR/announce/product-conditions/UHPPCI0262M0.jsp"
)
LIST_API_URL = BASE_URL + "/service/SHPPCC0247S01"
DOWNLOAD_URL = BASE_URL + "/filedownload.do"

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

LOG = logging.getLogger("samsungcard")


class CollectorError(RuntimeError):
    """수집을 계속할 수 없는 오류."""


class PdfValidationError(CollectorError):
    """다운로드 결과가 정상 PDF가 아닐 때 발생한다."""


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def normalize_text(value: Any) -> str:
    """HTML 엔티티를 풀고 유니코드와 공백을 정규화한다."""
    text = html.unescape(str(value or ""))
    text = unicodedata.normalize("NFC", text)
    return WHITESPACE.sub(" ", text).strip()


def safe_component(value: Any, fallback: str, max_length: int = 140) -> str:
    """한 개의 안전한 파일시스템 경로 구성요소를 만든다."""
    text = normalize_text(value)
    text = FORBIDDEN_PATH_CHARS.sub("_", text)
    text = WHITESPACE.sub(" ", text).strip(" .")
    if not text:
        text = fallback

    stem, suffix = os.path.splitext(text)
    if stem.upper() in WINDOWS_RESERVED_NAMES:
        stem = "_" + stem

    if len(text) > max_length:
        if suffix and len(suffix) < 20:
            stem = stem[: max(1, max_length - len(suffix))].rstrip(" .")
            text = stem + suffix
        else:
            text = text[:max_length].rstrip(" .")

    return text or fallback


def display_date(value: Any) -> str:
    raw = str(value or "").strip()
    if len(raw) == 8 and raw.isdigit():
        return f"{raw[:4]}-{raw[4:6]}-{raw[6:8]}"
    return raw


def canonical_group_number(encoded_value: Any) -> str:
    """API의 이미 인코딩된 그룹 번호를 정확히 한 번만 인코딩한다."""
    raw = str(encoded_value or "").strip()
    if not raw:
        raise CollectorError("첨부파일의 apnFileGrpNoE가 비어 있습니다")
    return urllib.parse.quote(urllib.parse.unquote(raw), safe="")


def build_download_url(encoded_group: Any, file_sn: Any) -> str:
    group = canonical_group_number(encoded_group)
    sn = str(file_sn or "").strip()
    if not sn:
        raise CollectorError("첨부파일의 apnFileSn이 비어 있습니다")
    return f"{DOWNLOAD_URL}?grpNo={group}&sn={urllib.parse.quote(sn, safe='')}"


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
    """크기, PDF 헤더/EOF 및 SHA-256을 확인한다."""
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


class SamsungCardCollector:
    def __init__(
        self,
        timeout: float,
        retries: int,
        user_agent: str = DEFAULT_USER_AGENT,
    ) -> None:
        self.timeout = timeout
        self.retries = retries
        cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(cookie_jar)
        )
        self.opener.addheaders = [
            ("User-Agent", user_agent),
            ("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7"),
        ]

    def _read_json(self, request: urllib.request.Request, action: str) -> Dict[str, Any]:
        last_error: Optional[BaseException] = None

        for attempt in range(1, self.retries + 2):
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    raw = response.read()
                parsed = json.loads(raw.decode("utf-8"))
                if not isinstance(parsed, dict):
                    raise CollectorError(f"{action}: JSON 최상위 값이 객체가 아닙니다")
                return parsed
            except urllib.error.HTTPError as exc:
                last_error = exc
                if exc.code not in RETRYABLE_HTTP_STATUSES or attempt > self.retries:
                    body = exc.read(500).decode("utf-8", errors="replace")
                    raise CollectorError(
                        f"{action}: HTTP {exc.code}: {body!r}"
                    ) from exc
                delay = retry_delay(attempt, exc.headers.get("Retry-After"))
            except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
                last_error = exc
                if attempt > self.retries:
                    raise CollectorError(f"{action}: {exc}") from exc
                delay = retry_delay(attempt)
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise CollectorError(f"{action}: 잘못된 JSON 응답: {exc}") from exc

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

    def fetch_page(self, page_number: int, page_size: int) -> Tuple[int, List[Dict[str, Any]]]:
        payload = {
            "cndt": {
                "no1PgeSize": str(page_size),
                "pgeNo": page_number,
                "itgBlbdSn": "",
                "itgBlbdChnlDvC": "01",
                "itgBlbdTpDvC": "19",
                "bltnbmTitNm": "",
                "bltnbmCn": "",
                "seaKeywCn": "",
                "seaKeywNm": "",
                "aryCriCn": "sysFstRgTs",
                "aryDvCn": "DESC",
            }
        }
        request = urllib.request.Request(
            LIST_API_URL,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={
                "Accept": "application/json, text/plain, */*",
                "Content-Type": "application/json",
                "Origin": BASE_URL,
                "Referer": LIST_PAGE_URL,
            },
        )
        response = self._read_json(request, f"목록 {page_number}페이지")

        message = response.get("message") or {}
        if message.get("msgC") != "NSFRW0001":
            raise CollectorError(
                "목록 API 오류: "
                + str(message.get("msgKrnCn") or message.get("msgC") or response)
            )

        try:
            total = int(response["totInqrCt"])
        except (KeyError, TypeError, ValueError) as exc:
            raise CollectorError("목록 응답에 유효한 totInqrCt가 없습니다") from exc

        batch = response.get("blbdInqrRsList")
        if not isinstance(batch, list):
            raise CollectorError("목록 응답의 blbdInqrRsList가 배열이 아닙니다")
        if not all(isinstance(item, dict) for item in batch):
            raise CollectorError("목록 응답에 객체가 아닌 상품이 있습니다")

        return total, batch

    def collect_all(self, page_size: int, request_delay: float) -> Tuple[int, List[Dict[str, Any]]]:
        expected_total: Optional[int] = None
        products: List[Dict[str, Any]] = []
        seen_ids = set()
        page_number = 1

        while True:
            total, batch = self.fetch_page(page_number, page_size)
            if expected_total is None:
                expected_total = total
            elif total != expected_total:
                raise CollectorError(
                    f"수집 도중 전체 건수가 {expected_total}에서 {total}(으)로 변경됐습니다"
                )

            if not batch:
                break

            for product in batch:
                product_id = str(product.get("itgBlbdSn") or "").strip()
                if not product_id:
                    raise CollectorError("itgBlbdSn이 비어 있는 상품이 있습니다")
                if product_id in seen_ids:
                    raise CollectorError(
                        f"페이지 사이에 상품 식별번호가 중복됐습니다: {product_id}"
                    )
                seen_ids.add(product_id)
                products.append(product)

            LOG.info(
                "목록 %d페이지: %d건, 누적 %d/%d건",
                page_number,
                len(batch),
                len(products),
                expected_total,
            )

            if len(products) >= expected_total:
                break
            if len(batch) < page_size:
                break

            page_number += 1
            if request_delay > 0:
                time.sleep(request_delay)

        if expected_total is None:
            raise CollectorError("목록 API에서 전체 건수를 받지 못했습니다")
        if len(products) != expected_total:
            raise CollectorError(
                f"목록 누락: API 전체 {expected_total}건, 실제 수집 {len(products)}건"
            )

        return expected_total, products

    def download_pdf(
        self,
        url: str,
        destination: Path,
        overwrite: bool,
    ) -> Dict[str, Any]:
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
                    "Referer": DETAIL_PAGE_URL,
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


def build_products(raw_products: Iterable[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], int]:
    selected: List[Dict[str, Any]] = []
    excluded = 0
    relative_paths = set()

    for raw_product in raw_products:
        product_name = normalize_text(raw_product.get("bltnbmTitNm"))
        if "중단" in product_name:
            excluded += 1
            continue

        product_id = str(raw_product.get("itgBlbdSn") or "").strip()
        start_date_raw = str(raw_product.get("bltnStrtdt") or "").strip()
        directory_name = safe_component(
            f"{start_date_raw or '날짜없음'}_{product_id}_{product_name}",
            fallback=f"상품_{product_id}",
        )

        upload_files = raw_product.get("uploadFileList") or []
        if not isinstance(upload_files, list):
            raise CollectorError(f"{product_name}: uploadFileList가 배열이 아닙니다")

        files: List[Dict[str, Any]] = []
        for raw_file in upload_files:
            if not isinstance(raw_file, dict):
                raise CollectorError(f"{product_name}: 첨부파일 항목이 객체가 아닙니다")

            raw_filename = str(raw_file.get("apnFileNm") or "")
            filename = normalize_text(raw_filename)
            file_sn = str(raw_file.get("apnFileSn") or "").strip()
            encoded_group = str(raw_file.get("apnFileGrpNoE") or "").strip()
            safe_filename = safe_component(
                filename,
                fallback=f"첨부파일_{file_sn or '번호없음'}.pdf",
            )
            stored_filename = safe_component(
                f"{file_sn}_{safe_filename}",
                fallback=f"첨부파일_{file_sn or '번호없음'}.pdf",
            )
            relative_path = Path(directory_name, stored_filename).as_posix()
            if relative_path in relative_paths:
                raise CollectorError(f"저장 경로가 중복됩니다: {relative_path}")
            relative_paths.add(relative_path)

            files.append(
                {
                    "filename": filename,
                    "raw_filename": raw_filename,
                    "file_sn": file_sn,
                    "file_group_number": str(raw_file.get("apnFileGrpNo") or "").strip(),
                    "encoded_file_group": encoded_group,
                    "url": build_download_url(encoded_group, file_sn),
                    "relative_path": relative_path,
                    "status": "pending",
                }
            )

        selected.append(
            {
                "product_name": product_name,
                "raw_product_name": str(raw_product.get("bltnbmTitNm") or ""),
                "date": display_date(start_date_raw),
                "date_raw": start_date_raw,
                "write_date_raw": str(raw_product.get("bltnbmWrteDt") or "").strip(),
                "itg_blbd_sn": product_id,
                "relative_directory": directory_name,
                "files": files,
            }
        )

    return selected, excluded


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
                    "itg_blbd_sn": product["itg_blbd_sn"],
                    "filename": file_info["filename"],
                    "file_sn": file_info["file_sn"],
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
        description=(
            "삼성카드 신용카드 상품약관 공시에서 제목에 '중단'이 없는 상품의 "
            "PDF를 모두 받습니다."
        )
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "out" / "samsungcard",
        help="출력 디렉터리(기본값: out/samsungcard)",
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
        default=0.35,
        help="정상 요청 사이 대기 초(기본값: 0.35)",
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
        "--metadata-only",
        action="store_true",
        help="목록과 metadata.json만 만들고 PDF는 받지 않기",
    )
    parser.add_argument(
        "--limit-products",
        type=int,
        default=None,
        help="앞에서부터 지정한 상품 수만 처리(시험 실행용)",
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
    if args.delay < 0:
        parser.error("--delay는 0 이상이어야 합니다")
    if args.timeout <= 0:
        parser.error("--timeout은 0보다 커야 합니다")
    if args.retries < 0:
        parser.error("--retries는 0 이상이어야 합니다")
    if args.limit_products is not None and args.limit_products <= 0:
        parser.error("--limit-products는 0보다 커야 합니다")

    return args


def run(args: argparse.Namespace) -> int:
    require_robots_allowed((LIST_PAGE_URL, LIST_API_URL, DOWNLOAD_URL))
    output_root = args.output.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)

    collector = SamsungCardCollector(timeout=args.timeout, retries=args.retries)
    api_total, raw_products = collector.collect_all(
        page_size=args.page_size,
        request_delay=args.delay,
    )
    products, excluded_count = build_products(raw_products)
    selected_before_limit = len(products)

    if args.limit_products is not None:
        products = products[: args.limit_products]

    total_files = sum(len(product["files"]) for product in products)
    metadata: Dict[str, Any] = {
        "schema_version": 1,
        "source_page": LIST_PAGE_URL,
        "list_api": LIST_API_URL,
        "created_at": now_iso(),
        "filter": {"exclude_title_containing": "중단"},
        "api_total_products": api_total,
        "excluded_products": excluded_count,
        "selected_products_before_limit": selected_before_limit,
        "selected_products": len(products),
        "total_files": total_files,
        "metadata_only": bool(args.metadata_only),
        "limit_products": args.limit_products,
        "products": products,
    }
    save_state(output_root, metadata)

    LOG.info(
        "목록 완료: 전체 %d, '중단' 제외 %d, 대상 %d, PDF %d",
        api_total,
        excluded_count,
        len(products),
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
                    "excluded_products": excluded_count,
                    "selected_products": len(products),
                    "total_files": total_files,
                },
                ensure_ascii=False,
            )
        )
        return 0

    processed = 0
    for product in products:
        LOG.info("상품: %s (%s)", product["product_name"], product["itg_blbd_sn"])
        for file_info in product["files"]:
            destination = output_root / Path(file_info["relative_path"])
            try:
                result = collector.download_pdf(
                    file_info["url"],
                    destination,
                    overwrite=args.overwrite,
                )
                file_info.update(result)
                file_info.pop("error", None)
                LOG.info(
                    "  [%s] %s (%d바이트)",
                    file_info["status"],
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
                LOG.error("  [failed] %s: %s", file_info["filename"], exc)
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

    summary = {
        "status": metadata["run_status"],
        "output": str(output_root),
        "api_total_products": api_total,
        "excluded_products": excluded_count,
        "selected_products": len(products),
        "total_files": total_files,
        "status_counts": metadata["status_counts"],
        "failures": len(failures),
    }
    print(json.dumps(summary, ensure_ascii=False))
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
