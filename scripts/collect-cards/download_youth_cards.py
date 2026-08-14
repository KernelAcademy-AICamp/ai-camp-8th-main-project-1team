#!/usr/bin/env python3
"""2030 우선 후보 매니페스트에 든 PDF만 내려받는다.

카드사별 전수 수집기를 다시 실행하지 않고 ``select_youth_cards.py``가 고른
상품만 받는다. 각 원본 호스트의 robots.txt를 먼저 확인하며, 판정할 수 없거나
불허된 호스트는 우회하지 않고 메타데이터에 ``policy_blocked``로 남긴다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from collector_policy import USER_AGENT, RobotsPolicyError, require_robots_allowed


HERE = Path(__file__).resolve().parent
DEFAULT_CANDIDATES = HERE / "out" / "youth-card-candidates.json"
DEFAULT_OUT = HERE / "out" / "youth-pdf"
RETRYABLE = {408, 425, 429, 500, 502, 503, 504}
ISSUER_SLUG = {
    "삼성카드": "samsung",
    "현대카드": "hyundai",
    "롯데카드": "lotte",
    "하나카드": "hana",
    "KB국민카드": "kb",
    "우리카드": "woori",
    "NH농협카드": "nh",
}


class PdfValidationError(OSError):
    pass


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="2030 우선 후보 PDF만 다운로드")
    parser.add_argument("--candidates", type=Path, default=DEFAULT_CANDIDATES)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument(
        "--issuer", action="append", default=[],
        help="특정 카드사만 처리(여러 번 지정 가능, 예: --issuer KB국민카드)",
    )
    parser.add_argument("--force", action="store_true", help="정상 기존 PDF도 다시 받기")
    parser.add_argument("--delay", type=float, default=1.5, help="요청 사이 대기 초")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--retries", type=int, default=3)
    args = parser.parse_args(argv)
    if args.delay < 0 or args.timeout <= 0 or args.retries < 0:
        parser.error("--delay는 0 이상, --timeout은 양수, --retries는 0 이상이어야 합니다")
    return args


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
    except Exception:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def inspect_pdf(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    head = b""
    tail = b""
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            size += len(chunk)
            digest.update(chunk)
            if len(head) < 1024:
                head = (head + chunk)[:1024]
            tail = (tail + chunk)[-4096:]
    if size == 0:
        raise PdfValidationError("0바이트 파일")
    if b"%PDF-" not in head:
        raise PdfValidationError("PDF 헤더 없음")
    if b"%%EOF" not in tail:
        raise PdfValidationError("PDF EOF 없음")
    return size, digest.hexdigest()


def safe_id(value: Any) -> str:
    clean = "".join(character if character.isalnum() or character in "._-" else "_"
                    for character in str(value or "").strip()).strip("._")
    if not clean:
        clean = hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:16]
    return clean[:80]


def local_relative_path(card: dict[str, Any]) -> str:
    issuer = card.get("issuer") or "unknown"
    slug = ISSUER_SLUG.get(issuer, safe_id(issuer))
    return f"{slug}/{safe_id(card.get('product_id'))}.pdf"


def load_selected(path: Path, issuers: list[str]) -> list[dict[str, Any]]:
    root = json.loads(path.read_text(encoding="utf-8"))
    selected = root.get("selected")
    if not isinstance(selected, list):
        raise ValueError("후보 JSON의 selected가 배열이 아닙니다")
    wanted = set(issuers)
    cards = [card for card in selected if not wanted or card.get("issuer") in wanted]
    missing = sorted(wanted - {card.get("issuer") for card in cards})
    if missing:
        raise ValueError(f"후보에 없는 카드사: {', '.join(missing)}")
    for card in cards:
        if not card.get("pdf_url") or not card.get("issuer") or not card.get("product_id"):
            raise ValueError("issuer/product_id/pdf_url이 없는 후보가 있습니다")
    return cards


def origin(url: str) -> str:
    parts = urllib.parse.urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}"


def reuse_existing(destination: Path) -> Optional[dict[str, Any]]:
    """이미 디스크에 있는 정상 PDF는 다시 받지 않는다.

    robots가 막는 것은 새 요청이지 이미 가진 파일이 아니다. 이 갈래가 없으면
    수동으로 반입한 PDF가 policy_blocked로 덮여 추출 대상에서 통째로 사라진다.
    """
    if not destination.exists():
        return None
    try:
        size, digest = inspect_pdf(destination)
    except (OSError, PdfValidationError):
        return None
    return {"status": "existing", "size": size, "sha256": digest, "attempts": 0}


def download_pdf(
    card: dict[str, Any], destination: Path, force: bool, timeout: float, retries: int,
    opener: Any = urllib.request.urlopen,
) -> dict[str, Any]:
    if not force:
        reused = reuse_existing(destination)
        if reused is not None:
            return reused

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    last_error: Optional[BaseException] = None
    for attempt in range(1, retries + 2):
        partial.unlink(missing_ok=True)
        request = urllib.request.Request(
            card["pdf_url"],
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "application/pdf,application/octet-stream;q=0.9,*/*;q=0.8",
                "Referer": card.get("source_page") or origin(card["pdf_url"]) + "/",
            },
        )
        try:
            with opener(request, timeout=timeout) as response, partial.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
                stream.flush()
                os.fsync(stream.fileno())
            size, digest = inspect_pdf(partial)
            os.replace(partial, destination)
            return {
                "status": "downloaded", "size": size, "sha256": digest,
                "attempts": attempt, "downloaded_at": now_iso(),
            }
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code not in RETRYABLE or attempt > retries:
                break
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as error:
            last_error = error
            if attempt > retries:
                break
        time.sleep(min(8.0, 2 ** attempt))
    partial.unlink(missing_ok=True)
    label = f"HTTP {last_error.code}" if isinstance(last_error, urllib.error.HTTPError) else type(last_error).__name__
    raise OSError(f"PDF 다운로드 실패({label}, {retries + 1}회)") from last_error


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    candidates_path = args.candidates.expanduser().resolve()
    out = args.out.expanduser().resolve()
    try:
        selected = load_selected(candidates_path, args.issuer)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(str(error))
        return 1

    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for card in selected:
        grouped[origin(card["pdf_url"])].append(card)

    blocked: dict[str, str] = {}
    for host, cards in grouped.items():
        try:
            require_robots_allowed(card["pdf_url"] for card in cards)
        except RobotsPolicyError as error:
            blocked[host] = str(error)

    records = []
    failures = 0
    processed = 0
    total_allowed = sum(len(cards) for host, cards in grouped.items() if host not in blocked)
    metadata_path = out / "metadata.json"
    root = {
        "schema_version": 1,
        "purpose": "2030 우선 LLM 추출 입력",
        "source_candidates": str(candidates_path),
        "created_at": now_iso(),
        "files": records,
    }
    if metadata_path.exists():
        try:
            prior = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            prior = {}
        if prior.get("manual_import"):
            # 수동 반입 이력은 이번 수집과 무관한 과거 사실이라 그대로 물려준다.
            root["manual_import"] = prior["manual_import"]

    for card in selected:
        host = origin(card["pdf_url"])
        relative = local_relative_path(card)
        record = {
            key: card.get(key) for key in (
                "issuer", "name", "product_id", "card_type", "posted_at",
                "source_page", "pdf_url", "active_verified",
            )
        }
        record["relative_path"] = relative
        if host in blocked:
            reused = reuse_existing(out / relative)
            if reused is None:
                record.update({"status": "policy_blocked", "error": blocked[host]})
            else:
                record.update(reused)
                print(f"  기존 사용 · {card['issuer']} · {card['name']}")
            records.append(record)
            continue
        try:
            result = download_pdf(card, out / relative, args.force, args.timeout, args.retries)
            record.update(result)
            print(f"  {result['status']} · {card['issuer']} · {card['name']}")
        except OSError as error:
            failures += 1
            record.update({"status": "failed", "error": str(error), "failed_at": now_iso()})
            print(f"  실패 · {card['issuer']} · {card['name']}")
        records.append(record)
        processed += 1
        atomic_json(metadata_path, root)
        if args.delay and processed < total_allowed:
            time.sleep(args.delay)

    counts = Counter(record["status"] for record in records)
    root["completed_at"] = now_iso()
    root["summary"] = dict(sorted(counts.items()))
    atomic_json(metadata_path, root)
    print(json.dumps({"total": len(records), **root["summary"]}, ensure_ascii=False))
    print(f"메타데이터 → {metadata_path}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
