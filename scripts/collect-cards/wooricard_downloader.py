#!/usr/bin/env python3
"""우리카드 상품공시실 (TAB) 약관의 카드별 약관 PDF 수집기.

목록 API의 issuAt이 "N"인 발급중지 상품은 제외하고, 남은 발급가능 카드의
상품설명서·국제브랜드 기본/선택 서비스 안내장·보이스아이 이력 전체와
신용/체크/기업 표준약관 전문을 상품별 디렉터리에 원자적으로 저장한다.
외부 패키지는 필요하지 않다.

파일 실물은 RAON KUpload 다운로드 솔루션 뒤에 있어 filePath를 그대로 열면
/error.html로 튕긴다. 대신 모바일 상품공시실이 쓰는 PDF 뷰어 엔드포인트
(pfileRead.jsp)가 같은 filePath로 원본 PDF를 그대로 내려준다.
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


PC_BASE_URL = "https://pc.wooricard.com"
PAGE_URL = PC_BASE_URL + "/dcpc/yh1/cct/cct11/prdntc/H1CCT211S09.do"
MAIN_API_URL = PC_BASE_URL + "/dcpc/yh1/cct/cct11/prdntc/getMainDataList.pwkjson"
DETAIL_API_URL = PC_BASE_URL + "/dcpc/yh1/cct/cct11/prdntc/getDetailDataList.pwkjson"
FILE_VIEWER_URL = "https://m.wooricard.com/dcmw/pdfviewer/jsp/pfileRead.jsp"

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

# 화면 JS(H1CCT211S09.js)의 openDetailPop 매핑.
#   guideTypeCd 01 상품설명서       -> searchCrdSe 01
#   guideTypeCd 02 국제브랜드 기본  -> searchCrdSe 02
#   guideTypeCd 03 국제브랜드 선택  -> searchCrdSe 02
#   guideTypeCd 04 보이스아이       -> searchCrdSe 03
DOC_TYPES: Tuple[Dict[str, str], ...] = (
    {
        "key": "goods_desc",
        "label": "상품설명서",
        "search_crd_se": "01",
        "guide_type_cd": "01",
        "flag": "goodsDescAt",
    },
    {
        "key": "intl_basic",
        "label": "국제브랜드 기본서비스 안내장",
        "search_crd_se": "02",
        "guide_type_cd": "02",
        "flag": "intlBscGdccAt",
    },
    {
        "key": "intl_option",
        "label": "국제브랜드 선택서비스 안내장",
        "search_crd_se": "02",
        "guide_type_cd": "03",
        "flag": "intlOptGdccAt",
    },
    {
        "key": "voiceye",
        "label": "보이스아이",
        "search_crd_se": "03",
        "guide_type_cd": "04",
        "flag": "voiceAt",
    },
)
DOC_TYPE_KEYS = tuple(doc["key"] for doc in DOC_TYPES)

# 표준약관 전문(cct11PrdntcTermsVo)의 s1Code 매핑.
STANDARD_TERMS_NAMES = {
    "01": "신용카드 개인회원 표준약관",
    "02": "기업상품 표준약관",
    "03": "체크카드 개인회원 표준약관",
}
STANDARD_TERMS_DIRECTORY = "_표준약관"

PART_CODES = {"personal": 0, "corporate": 1}

LOG = logging.getLogger("wooricard")


class CollectorError(RuntimeError):
    """수집을 계속할 수 없는 오류."""


class PdfValidationError(CollectorError):
    """다운로드 결과가 정상 PDF가 아닐 때 발생한다."""


# 우리카드가 일부 안내장을 Fasoo DRM으로 암호화된 상태 그대로 올려둔다.
# 사이트에서 받아도 동일한 바이트가 내려오므로 재시도 대상이 아니다.
DRM_MARKERS = (b"DRMONE", b"Fasoo DRM")


def looks_drm_protected(head: bytes) -> bool:
    return any(marker in head[:256] for marker in DRM_MARKERS)


def digest_file(path: Path) -> Tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


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


def compact_date(value: Any) -> str:
    """'2025.10.01' 또는 '20251001'을 '20251001'로 만든다."""
    digits = re.sub(r"\D", "", str(value or ""))
    return digits[:8]


def display_date(value: Any) -> Optional[str]:
    raw = compact_date(value)
    if len(raw) == 8:
        return f"{raw[:4]}-{raw[4:6]}-{raw[6:8]}"
    return normalize_text(value) or None


def build_file_url(file_path: Any, file_name: Any) -> str:
    """모바일 상품공시실(M1CCT211S09_1.js)이 만드는 PDF 뷰어 URL."""
    path = normalize_text(file_path)
    if not path:
        raise CollectorError("filePath가 비어 있습니다")
    query = urllib.parse.urlencode(
        {
            "pdfPath": path,
            "pdfKubun": "05",
            "pdfName": normalize_text(file_name),
        }
    )
    return f"{FILE_VIEWER_URL}?{query}"


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


def unwrap_api_response(raw: bytes, action: str) -> Dict[str, Any]:
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        preview = raw[:500].decode("utf-8", errors="replace")
        raise CollectorError(f"{action}: 올바른 JSON이 아닙니다: {preview!r}") from exc
    if not isinstance(payload, dict):
        raise CollectorError(f"{action}: JSON 최상위 값이 객체가 아닙니다")

    header = payload.get("elHeader")
    if isinstance(header, dict) and header.get("resSuc") is not True:
        raise CollectorError(
            f"{action}: 우리카드 API 오류 "
            f"{header.get('resCode')} / {header.get('resMsg')}"
        )
    return payload


class WooriCardCollector:
    def __init__(self, timeout: float, retries: int) -> None:
        self.timeout = timeout
        self.retries = retries
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookie_jar)
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
        """상품공시실 페이지를 열고 프레임워크가 요구하는 쿠키를 심는다.

        ajaxUtils.js의 callAjaxStart가 lang/bodyYn 쿠키를 설정하지 않으면
        서버가 ERROR.SYS.002로 응답한다.
        """
        request = urllib.request.Request(
            PAGE_URL,
            method="GET",
            headers={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            },
        )
        self._read_bytes(request, "상품공시실 페이지")

        for name, value in (("lang", "ko"), ("bodyYn", "Y")):
            self.cookie_jar.set_cookie(
                http.cookiejar.Cookie(
                    version=0,
                    name=name,
                    value=value,
                    port=None,
                    port_specified=False,
                    domain="pc.wooricard.com",
                    domain_specified=True,
                    domain_initial_dot=False,
                    path="/",
                    path_specified=True,
                    secure=True,
                    expires=None,
                    discard=True,
                    comment=None,
                    comment_url=None,
                    rest={},
                )
            )

    def post_api(self, url: str, payload: Dict[str, Any], action: str) -> Dict[str, Any]:
        """Proworks 프레임워크 규약대로 JSON 문자열을 본문에 실어 보낸다."""
        request = urllib.request.Request(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={
                "Accept": "application/json, text/javascript, */*; q=0.01",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer": PAGE_URL,
                "X-Requested-With": "XMLHttpRequest",
                "Proworks-Body": "Y",
                "Proworks-Lang": "ko",
            },
        )
        return unwrap_api_response(self._read_bytes(request, action), action)

    def fetch_main_page(
        self, part: int, page_index: int, page_size: int
    ) -> Tuple[int, List[Dict[str, Any]], List[Dict[str, Any]]]:
        payload = {
            "cct11PrdntcAgrmReqVo": {
                "part": part,
                "searchPrdNm": "",
                "searchPrdCd": "",
                "pageIndex": str(page_index),
                "pageSize": str(page_size),
                "preView": "",
            }
        }
        data = self.post_api(MAIN_API_URL, payload, f"목록 part={part} page={page_index}")
        main = data.get("mainDataList") or {}
        try:
            total = int(main.get("totCnt"))
        except (TypeError, ValueError) as exc:
            raise CollectorError("목록 응답에 유효한 totCnt가 없습니다") from exc

        rows = main.get("cct11PrdntcAgrmMainVo") or []
        terms = main.get("cct11PrdntcTermsVo") or []
        if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
            raise CollectorError("목록 응답의 cct11PrdntcAgrmMainVo가 객체 배열이 아닙니다")
        if not isinstance(terms, list):
            raise CollectorError("목록 응답의 cct11PrdntcTermsVo가 배열이 아닙니다")
        return total, rows, terms

    def fetch_detail_page(
        self,
        part: int,
        product_code: str,
        doc_type: Dict[str, str],
        page_index: int,
        page_size: int,
    ) -> Tuple[int, List[Dict[str, Any]]]:
        payload = {
            "cct11PrdntcAgrmReqVo": {
                "part": part,
                "searchPrdCd": product_code,
                "searchCrdSe": doc_type["search_crd_se"],
                "guideTypeCd": doc_type["guide_type_cd"],
                "pageIndex": str(page_index),
                "pageSize": str(page_size),
            }
        }
        data = self.post_api(
            DETAIL_API_URL,
            payload,
            f"상세 {product_code}/{doc_type['key']} page={page_index}",
        )
        detail = data.get("detailDataList") or {}
        try:
            total = int(detail.get("totCnt") or 0)
        except (TypeError, ValueError) as exc:
            raise CollectorError("상세 응답에 유효한 totCnt가 없습니다") from exc

        rows = detail.get("cct11PrdntcAgrmDetailVo") or []
        if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
            raise CollectorError("상세 응답의 cct11PrdntcAgrmDetailVo가 객체 배열이 아닙니다")
        return total, rows

    def collect_products(
        self, part: int, page_size: int, request_delay: float
    ) -> Tuple[int, List[Dict[str, Any]], List[Dict[str, Any]]]:
        expected_total: Optional[int] = None
        standard_terms: List[Dict[str, Any]] = []
        rows: List[Dict[str, Any]] = []
        seen_codes = set()
        page_index = 1

        while True:
            total, batch, terms = self.fetch_main_page(part, page_index, page_size)
            if expected_total is None:
                expected_total = total
                standard_terms = terms
            elif total != expected_total:
                raise CollectorError(
                    f"수집 도중 전체 건수가 {expected_total}에서 {total}(으)로 변경됐습니다"
                )
            if not batch:
                break

            for row in batch:
                # 'ID / RF카드', '선불카드'처럼 상품코드가 없는 행이 몇 건 있다.
                # 이 행들은 안내장이 하나도 없으므로 순번(rn)으로만 구분한다.
                code = normalize_text(row.get("code"))
                identity = code or f"rn:{normalize_text(row.get('rn'))}"
                if identity in seen_codes:
                    raise CollectorError(f"페이지 사이 상품 식별자가 중복됐습니다: {identity}")
                seen_codes.add(identity)
                rows.append(row)

            LOG.info(
                "목록 part=%d: 이번 %d건, 누적 %d/%d건",
                part,
                len(batch),
                len(rows),
                expected_total,
            )
            if len(rows) >= expected_total:
                break
            page_index += 1
            if request_delay > 0:
                time.sleep(request_delay)

        if expected_total is None:
            raise CollectorError("목록 API에서 전체 건수를 받지 못했습니다")
        if len(rows) != expected_total:
            raise CollectorError(
                f"목록 누락: API 전체 {expected_total}건, 실제 수집 {len(rows)}건"
            )
        return expected_total, rows, standard_terms

    def collect_history(
        self,
        part: int,
        product_code: str,
        doc_type: Dict[str, str],
        page_size: int,
        request_delay: float,
    ) -> List[Dict[str, Any]]:
        expected_total: Optional[int] = None
        rows: List[Dict[str, Any]] = []
        page_index = 1

        while True:
            total, batch = self.fetch_detail_page(
                part, product_code, doc_type, page_index, page_size
            )
            if expected_total is None:
                expected_total = total
            elif total != expected_total:
                raise CollectorError(
                    f"{product_code}/{doc_type['key']}: 조회 도중 전체 건수가 "
                    f"{expected_total}에서 {total}(으)로 변경됐습니다"
                )
            if not batch:
                break

            rows.extend(batch)
            if len(rows) >= expected_total:
                break
            page_index += 1
            if request_delay > 0:
                time.sleep(request_delay)

        if expected_total is not None and len(rows) != expected_total:
            raise CollectorError(
                f"{product_code}/{doc_type['key']} 이력 누락: "
                f"API 전체 {expected_total}건, 실제 수집 {len(rows)}건"
            )
        return rows

    def download_pdf(self, url: str, destination: Path, overwrite: bool) -> Dict[str, Any]:
        destination.parent.mkdir(parents=True, exist_ok=True)
        drm_destination = destination.with_name(destination.name + ".drm")
        if not overwrite:
            if destination.exists():
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
            elif drm_destination.exists():
                size, digest = digest_file(drm_destination)
                return {
                    "status": "skipped_existing_drm",
                    "size": size,
                    "sha256": digest,
                    "saved_filename": drm_destination.name,
                    "attempts": 0,
                }

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
                if looks_drm_protected(head):
                    os.replace(partial, drm_destination)
                    return {
                        "status": "drm_protected",
                        "size": size,
                        "sha256": digest.hexdigest(),
                        "content_type": content_type,
                        "saved_filename": drm_destination.name,
                        "attempts": attempt,
                        "downloaded_at": now_iso(),
                        "note": "우리카드가 Fasoo DRM으로 암호화된 채 올려둔 파일입니다",
                    }
                if b"%PDF-" not in head:
                    raise PdfValidationError(
                        "응답 처음 1024바이트에 PDF 헤더가 없습니다"
                        " (뷰어가 오류 페이지를 돌려준 경우)"
                    )
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


class PathAllocator:
    """상품/파일 이름이 겹쳐도 저장 경로가 충돌하지 않게 한다."""

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


def build_standard_terms(
    terms: Iterable[Dict[str, Any]], allocator: PathAllocator
) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for item in terms:
        file_path = normalize_text(item.get("s1File"))
        file_name = normalize_text(item.get("s1Nm"))
        code = normalize_text(item.get("s1Code"))
        if not file_path or not file_name:
            continue
        records.append(
            {
                "terms_code": code,
                "terms_name": STANDARD_TERMS_NAMES.get(code, f"표준약관({code})"),
                "filename": file_name,
                "file_path": file_path,
                "declared_size": item.get("s1Size"),
                "url": build_file_url(file_path, file_name),
                "relative_path": allocator.claim(
                    STANDARD_TERMS_DIRECTORY,
                    safe_component(file_name, fallback=f"표준약관_{code or '00'}.pdf"),
                ),
                "status": "pending",
            }
        )
    return records


def build_products(
    rows: Iterable[Dict[str, Any]], include_suspended: bool
) -> Tuple[List[Dict[str, Any]], int]:
    products: List[Dict[str, Any]] = []
    excluded_suspended = 0

    for row in rows:
        issu_at = normalize_text(row.get("issuAt")).upper()
        suspended = issu_at == "N"
        if suspended and not include_suspended:
            excluded_suspended += 1
            continue

        code = normalize_text(row.get("code"))
        name = normalize_text(row.get("codeName"))
        available = (
            [
                doc["key"]
                for doc in DOC_TYPES
                if normalize_text(row.get(doc["flag"])).upper() == "Y"
            ]
            if code
            else []
        )
        products.append(
            {
                "product_code": code,
                "product_name": name,
                "issue_status": "발급중지" if suspended else "발급가능",
                "issuAt": issu_at,
                "suspended_date": display_date(row.get("issuDt")),
                "available_doc_types": available,
                "relative_directory": safe_component(
                    f"{code}_{name}" if code else name,
                    fallback=f"상품_{normalize_text(row.get('rn')) or '000'}",
                ),
                "documents": [],
            }
        )
    return products, excluded_suspended


def build_documents(
    product: Dict[str, Any],
    doc_type: Dict[str, str],
    history: List[Dict[str, Any]],
    latest_only: bool,
    allocator: PathAllocator,
) -> List[Dict[str, Any]]:
    ordered = sorted(
        history,
        key=lambda item: compact_date(item.get("beginDt")),
        reverse=True,
    )
    if latest_only:
        ordered = ordered[:1]

    directory = Path(
        product["relative_directory"], safe_component(doc_type["label"], fallback=doc_type["key"])
    ).as_posix()

    documents: List[Dict[str, Any]] = []
    for item in ordered:
        file_path = normalize_text(item.get("filePath"))
        file_name = normalize_text(item.get("fileNm"))
        begin = compact_date(item.get("beginDt"))
        if not file_path or not file_name:
            documents.append(
                {
                    "doc_type": doc_type["key"],
                    "doc_type_label": doc_type["label"],
                    "begin_date": display_date(item.get("beginDt")),
                    "filename": file_name,
                    "file_path": file_path,
                    "declared_size": item.get("fileSize"),
                    "status": "no_file",
                }
            )
            continue

        stored = safe_component(
            f"{begin}_{file_name}" if begin else file_name,
            fallback=f"{doc_type['key']}.pdf",
        )
        documents.append(
            {
                "doc_type": doc_type["key"],
                "doc_type_label": doc_type["label"],
                "begin_date": display_date(item.get("beginDt")),
                "filename": file_name,
                "file_path": file_path,
                "declared_size": item.get("fileSize"),
                "url": build_file_url(file_path, file_name),
                "relative_path": allocator.claim(directory, stored),
                "status": "pending",
            }
        )
    return documents


def iter_files(metadata: Dict[str, Any]) -> Iterable[Tuple[str, Dict[str, Any]]]:
    for record in metadata["standard_terms"]:
        yield record["terms_name"], record
    for product in metadata["products"]:
        for document in product["documents"]:
            yield product["product_name"], document


def summarize_status(metadata: Dict[str, Any]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for _, record in iter_files(metadata):
        status = str(record.get("status") or "unknown")
        counts[status] = counts.get(status, 0) + 1
    return counts


def failure_records(metadata: Dict[str, Any]) -> List[Dict[str, Any]]:
    failures = []
    for owner, record in iter_files(metadata):
        if record.get("status") != "failed":
            continue
        failures.append(
            {
                "owner": owner,
                "doc_type_label": record.get("doc_type_label") or record.get("terms_name"),
                "filename": record.get("filename"),
                "url": record.get("url"),
                "relative_path": record.get("relative_path"),
                "error": record.get("error", ""),
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
        description="우리카드 상품공시실에서 발급가능 카드의 약관 PDF를 받습니다."
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "out" / "wooricard",
        help="출력 디렉터리(기본값: out/wooricard)",
    )
    parser.add_argument(
        "--part",
        choices=("personal", "corporate", "all"),
        default="personal",
        help="개인/기업 구분(기본값: personal)",
    )
    parser.add_argument(
        "--doc-types",
        default="all",
        help=(
            "받을 문서 종류를 쉼표로 나열합니다. "
            f"선택지: {', '.join(DOC_TYPE_KEYS)} (기본값: all)"
        ),
    )
    parser.add_argument(
        "--include-suspended",
        action="store_true",
        help="발급중지(issuAt=N) 상품도 포함",
    )
    parser.add_argument(
        "--latest-only",
        action="store_true",
        help="문서 종류별 최신 1건만 받기(기본값은 이력 전체)",
    )
    parser.add_argument(
        "--skip-standard-terms",
        action="store_true",
        help="신용/체크/기업 표준약관 전문은 건너뛰기",
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
        help="목록/상세 API 페이지당 건수(기본값: 100)",
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

    if args.doc_types.strip().lower() == "all":
        args.selected_doc_types = list(DOC_TYPE_KEYS)
    else:
        selected = [key.strip() for key in args.doc_types.split(",") if key.strip()]
        unknown = [key for key in selected if key not in DOC_TYPE_KEYS]
        if unknown:
            parser.error(f"알 수 없는 --doc-types 값: {', '.join(unknown)}")
        if not selected:
            parser.error("--doc-types에 하나 이상을 지정해야 합니다")
        args.selected_doc_types = selected

    args.selected_parts = (
        [("personal", 0), ("corporate", 1)]
        if args.part == "all"
        else [(args.part, PART_CODES[args.part])]
    )
    return args


def run(args: argparse.Namespace) -> int:
    policy_urls = [PAGE_URL, MAIN_API_URL, DETAIL_API_URL]
    if not args.metadata_only:
        policy_urls.append(FILE_VIEWER_URL)
    require_robots_allowed(policy_urls)
    output_root = args.output.expanduser().resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    collector = WooriCardCollector(timeout=args.timeout, retries=args.retries)
    collector.warm_session()

    allocator = PathAllocator()
    doc_types = [doc for doc in DOC_TYPES if doc["key"] in args.selected_doc_types]

    api_totals: Dict[str, int] = {}
    excluded_total = 0
    products: List[Dict[str, Any]] = []
    standard_terms: List[Dict[str, Any]] = []

    for part_name, part_code in args.selected_parts:
        api_total, rows, terms = collector.collect_products(
            part_code, args.page_size, args.delay
        )
        api_totals[part_name] = api_total
        selected, excluded = build_products(rows, args.include_suspended)
        excluded_total += excluded
        for product in selected:
            product["part"] = part_name
        products.extend(selected)
        if not args.skip_standard_terms:
            standard_terms.extend(build_standard_terms(terms, allocator))
        LOG.info(
            "%s: 전체 %d건, 발급중지 제외 %d건, 대상 %d건",
            part_name,
            api_total,
            excluded,
            len(selected),
        )

    selected_before_limit = len(products)
    if args.limit_products is not None:
        products = products[: args.limit_products]

    metadata: Dict[str, Any] = {
        "schema_version": 1,
        "source_page": PAGE_URL,
        "main_api": MAIN_API_URL,
        "detail_api": DETAIL_API_URL,
        "file_viewer": FILE_VIEWER_URL,
        "created_at": now_iso(),
        "filter": {
            "part": args.part,
            "exclude_when_issuAt": None if args.include_suspended else "N",
            "doc_types": args.selected_doc_types,
            "latest_only": bool(args.latest_only),
            "standard_terms": not args.skip_standard_terms,
        },
        "api_total_products": api_totals,
        "excluded_suspended": excluded_total,
        "selected_products_before_limit": selected_before_limit,
        "limit_products": args.limit_products,
        "selected_products": len(products),
        "metadata_only": bool(args.metadata_only),
        "standard_terms": standard_terms,
        "products": products,
    }
    save_state(output_root, metadata)

    for index, product in enumerate(products, start=1):
        part_code = PART_CODES[product["part"]]
        for doc_type in doc_types:
            if doc_type["key"] not in product["available_doc_types"]:
                continue
            history = collector.collect_history(
                part_code,
                product["product_code"],
                doc_type,
                args.page_size,
                args.delay,
            )
            product["documents"].extend(
                build_documents(product, doc_type, history, args.latest_only, allocator)
            )
        LOG.info(
            "[%d/%d] %s (%s): 문서 %d건",
            index,
            len(products),
            product["product_name"],
            product["product_code"],
            len(product["documents"]),
        )
        if index % 20 == 0:
            save_state(output_root, metadata)

    total_files = sum(1 for _, record in iter_files(metadata) if record.get("url"))
    metadata["total_files"] = total_files
    save_state(output_root, metadata)
    LOG.info(
        "목록 완료: 대상 상품 %d, 표준약관 %d, 내려받을 PDF %d",
        len(products),
        len(standard_terms),
        total_files,
    )

    summary = {
        "output": str(output_root),
        "api_total_products": api_totals,
        "excluded_suspended": excluded_total,
        "selected_products": len(products),
        "standard_terms": len(standard_terms),
        "total_files": total_files,
    }

    if args.metadata_only:
        metadata["run_status"] = "metadata_only"
        metadata["completed_at"] = now_iso()
        save_state(output_root, metadata)
        print(json.dumps({"status": "metadata_only", **summary}, ensure_ascii=False))
        return 0

    processed = 0
    for owner, record in iter_files(metadata):
        if not record.get("url"):
            continue
        destination = output_root / Path(record["relative_path"])
        try:
            result = collector.download_pdf(
                record["url"], destination, overwrite=args.overwrite
            )
            record.update(result)
            record.pop("error", None)
            drm = record["status"] in ("drm_protected", "skipped_existing_drm")
            (LOG.warning if drm else LOG.info)(
                "[%s] %s / %s (%d바이트)",
                record["status"],
                owner,
                record["filename"],
                record["size"],
            )
        except (CollectorError, OSError) as exc:
            record.update(
                {"status": "failed", "error": str(exc), "failed_at": now_iso()}
            )
            LOG.error("[failed] %s / %s: %s", owner, record["filename"], exc)
            save_state(output_root, metadata)
            if args.fail_fast:
                raise

        processed += 1
        if processed % 10 == 0:
            save_state(output_root, metadata)
        if args.delay > 0 and processed < total_files:
            time.sleep(args.delay)

    failures = failure_records(metadata)
    metadata["run_status"] = "completed_with_errors" if failures else "completed"
    metadata["completed_at"] = now_iso()
    save_state(output_root, metadata)
    drm_count = sum(
        count
        for status, count in metadata["status_counts"].items()
        if status in ("drm_protected", "skipped_existing_drm")
    )
    if drm_count:
        LOG.warning(
            "%d건은 우리카드가 Fasoo DRM으로 암호화한 채 올려둔 파일이라 "
            ".drm 확장자로 저장했습니다(PDF로 열리지 않습니다).",
            drm_count,
        )
    print(
        json.dumps(
            {
                "status": metadata["run_status"],
                **summary,
                "status_counts": metadata["status_counts"],
                "drm_protected": drm_count,
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
