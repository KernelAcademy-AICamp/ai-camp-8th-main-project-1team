#!/usr/bin/env python3
"""사용자가 직접 받은 후보 PDF를 수집 매니페스트에 안전하게 반입한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from download_youth_cards import atomic_json, inspect_pdf


HERE = Path(__file__).resolve().parent
DEFAULT_MANIFEST = HERE / "out" / "youth-pdf" / "metadata.json"
DEFAULT_OUT = HERE / "out" / "youth-pdf"
# 카드사 이름은 박아두지 않는다. 어느 카드사가 수동 경로를 타는지는 robots 정책이
# 정하고, 그 결과가 매니페스트의 policy_blocked로 남는다.
NUMBERED = re.compile(r"^(.+?)_(\d{2})_(.+)\.pdf$", re.I)


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="수동 다운로드 카드 PDF 반입")
    parser.add_argument("--src", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    return parser.parse_args(argv)


def normalized_name(value: Any) -> str:
    return re.sub(r"[^0-9A-Za-z가-힣]+", "", str(value or "")).lower()


def match_by_name(stem: str, candidates: dict[tuple[str, int], dict[str, Any]]) -> Optional[tuple[str, int]]:
    """번호를 안 붙인 파일을 카드명으로 맞춘다.

    카드사가 내려주는 이름은 카드명 뒤에 문서 종류가 붙는다 —
    `모니모페이카드 이용안내장.pdf`. 카드명이 앞에 오는 후보가 **하나뿐일 때만**
    맞다고 본다. 둘 이상 걸리면 사람이 번호를 붙여 구분해야 한다.
    """
    normalized = normalized_name(stem)
    if not normalized:
        return None
    hits = [
        key for key, row in candidates.items()
        if normalized.startswith(normalized_name(row.get("name")))
    ]
    return hits[0] if len(hits) == 1 else None


def numbered_sources(
    src: Path, candidates: dict[tuple[str, int], dict[str, Any]]
) -> dict[tuple[str, int], Path]:
    found: dict[tuple[str, int], Path] = {}
    for path in sorted(src.glob("*.pdf")):
        match = NUMBERED.match(path.name)
        key = (match.group(1), int(match.group(2))) if match else None
        if key not in candidates:
            # 번호가 없거나 후보와 안 맞으면 카드명으로 한 번 더 본다.
            key = match_by_name(path.stem, candidates)
        if key is None:
            continue
        if key in found:
            raise ValueError(f"같은 카드에 수동 PDF가 둘입니다: {key} ({found[key].name}, {path.name})")
        found[key] = path
    return found


def candidate_index(rows: list[dict[str, Any]]) -> dict[tuple[str, int], dict[str, Any]]:
    """수동으로 받아야 하는 행에만 카드사별 가나다순 번호를 매긴다.

    번호 규칙은 ``export_manual_download_links.py``가 안내문에 찍는 번호와 같아야
    한다. 그쪽도 policy_blocked 행만 카드사별로 묶어 1번부터 센다.
    """
    blocked = [row for row in rows if row.get("status") == "policy_blocked"]
    by_issuer = {}
    for issuer in sorted({str(row.get("issuer")) for row in blocked}):
        cards = sorted(
            (row for row in blocked if str(row.get("issuer")) == issuer),
            key=lambda row: str(row.get("name")),
        )
        by_issuer.update({(issuer, index): row for index, row in enumerate(cards, 1)})
    return by_issuer


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            hasher.update(chunk)
    return hasher.hexdigest()


def atomic_copy(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".part", dir=destination.parent,
    )
    os.close(handle)
    temporary = Path(temporary_name)
    try:
        shutil.copyfile(source, temporary)
        inspect_pdf(temporary)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    src = args.src.expanduser().resolve()
    manifest_path = args.manifest.expanduser().resolve()
    out = args.out.expanduser().resolve()
    if not src.is_dir():
        print(f"수동 PDF 폴더가 없습니다: {src}")
        return 1

    root = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = root.get("files") or []
    candidates = candidate_index(rows)
    sources = numbered_sources(src, candidates)
    # 받은 만큼만 넣는다. 아직 안 받은 카드는 policy_blocked로 남아 다음 회차 대상이 된다.
    expected = sorted(set(sources) & set(candidates))
    if not expected:
        print(f"반입할 PDF가 없습니다. 후보 {len(candidates)}장, 폴더에서 맞춘 파일 0장")
        return 1
    pending = sorted(set(candidates) - set(sources))

    imported = invalid = 0
    for key in sorted(expected):
        row = candidates[key]
        source = sources[key]
        source_label = NUMBERED.match(source.name)
        supplied_name = source_label.group(3) if source_label else source.stem
        if not normalized_name(supplied_name).startswith(normalized_name(row.get("name"))):
            print(f"이름 불일치: {source.name} ↔ {row.get('name')}")
            return 1
        try:
            size, sha256 = inspect_pdf(source)
            atomic_copy(source, out / row["relative_path"])
            row.update({
                "status": "manual_imported",
                "size": size,
                "sha256": sha256,
                "manual_source_name": source.name,
                "imported_at": now_iso(),
            })
            row.pop("error", None)
            imported += 1
            print(f"  반입 · {row['issuer']} · {row['name']}")
        except OSError as error:
            row.update({
                "status": "manual_invalid",
                "manual_source_name": source.name,
                "source_sha256": digest(source),
                "error": str(error),
                "checked_at": now_iso(),
            })
            invalid += 1
            print(f"  판독 불가 · {row['issuer']} · {row['name']}")

    root["manual_import"] = {
        "source_directory": str(src),
        "completed_at": now_iso(),
        "imported": imported,
        "invalid": invalid,
        "still_pending": len(pending),
    }
    if pending:
        print(f"아직 안 받은 {len(pending)}장은 policy_blocked로 남깁니다:")
        for issuer, number in pending:
            print(f"  · {issuer}_{number:02d}_{candidates[(issuer, number)]['name']}.pdf")
    atomic_json(manifest_path, root)
    print(json.dumps(root["manual_import"], ensure_ascii=False))
    return 0 if imported + invalid == len(expected) else 1


if __name__ == "__main__":
    raise SystemExit(main())
