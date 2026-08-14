#!/usr/bin/env python3
"""접근 정책으로 자동 수집하지 못한 후보의 수동 다운로드 링크를 정리한다."""

from __future__ import annotations

import argparse
import csv
import html
import json
from collections import defaultdict
from pathlib import Path
from typing import Optional


HERE = Path(__file__).resolve().parent
DEFAULT_MANIFEST = HERE / "out" / "youth-pdf" / "metadata.json"
DEFAULT_HTML = HERE / "out" / "manual-download-links.html"
DEFAULT_CSV = HERE / "out" / "manual-download-links.csv"


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="수동 PDF 다운로드 링크 내보내기")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--html", type=Path, default=DEFAULT_HTML)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    return parser.parse_args(argv)


def blocked_rows(manifest: Path) -> list[dict]:
    root = json.loads(manifest.read_text(encoding="utf-8"))
    rows = [row for row in root.get("files", []) if row.get("status") == "policy_blocked"]
    rows.sort(key=lambda row: (str(row.get("issuer")), str(row.get("name"))))
    return rows


def write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["카드사", "카드명", "상품번호", "카드종류", "게시일", "PDF 다운로드 링크"])
        for row in rows:
            writer.writerow([
                row.get("issuer"), row.get("name"), row.get("product_id"),
                row.get("card_type"), row.get("posted_at"), row.get("pdf_url"),
            ])


def write_html(path: Path, rows: list[dict]) -> None:
    grouped = defaultdict(list)
    for row in rows:
        grouped[row.get("issuer") or "기타"].append(row)

    sections = []
    for issuer, cards in sorted(grouped.items()):
        items = []
        for index, row in enumerate(cards, 1):
            name = html.escape(str(row.get("name") or "이름 없음"))
            product_id = html.escape(str(row.get("product_id") or "-"))
            card_type = html.escape(str(row.get("card_type") or "UNKNOWN"))
            url = html.escape(str(row.get("pdf_url") or ""), quote=True)
            items.append(
                f'<li><input type="checkbox" aria-label="완료">'
                f'<span class="name">{index:02d}. {name}</span>'
                f'<span class="meta">상품번호 {product_id} · {card_type}</span>'
                f'<a href="{url}" target="_blank" rel="noopener noreferrer">PDF 열기</a></li>'
            )
        sections.append(f'<section><h2>{html.escape(issuer)} <small>{len(cards)}장</small></h2><ol>{"".join(items)}</ol></section>')

    document = f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>2030 후보 카드 수동 다운로드 링크</title>
<style>
body {{ max-width: 980px; margin: 36px auto; padding: 0 20px; font-family: -apple-system, BlinkMacSystemFont, "Noto Sans KR", sans-serif; color: #172033; }}
h1 {{ margin-bottom: 8px; }} .notice {{ color: #5d687b; line-height: 1.6; }}
section {{ margin-top: 34px; }} h2 {{ border-bottom: 2px solid #172033; padding-bottom: 10px; }} h2 small {{ color: #687386; font-weight: 500; }}
ol {{ list-style: none; padding: 0; }} li {{ display: grid; grid-template-columns: 28px minmax(240px, 1fr) 210px 86px; align-items: center; gap: 12px; padding: 12px 4px; border-bottom: 1px solid #e5e8ee; }}
.name {{ font-weight: 650; }} .meta {{ color: #6a7485; font-size: 14px; }} a {{ display: inline-block; padding: 7px 10px; border-radius: 7px; background: #1769e0; color: white; text-decoration: none; text-align: center; }}
input {{ width: 18px; height: 18px; }} @media (max-width: 720px) {{ li {{ grid-template-columns: 28px 1fr 86px; }} .meta {{ grid-column: 2 / 4; }} }}
</style>
</head>
<body>
<h1>2030 후보 카드 수동 다운로드</h1>
<p class="notice">자동 수집이 허용되지 않은 공식 PDF 링크 {len(rows)}개입니다. 버튼을 눌러 브라우저에서 직접 저장하세요. 체크 상태는 새로고침하면 초기화됩니다.</p>
{"".join(sections)}
</body>
</html>
"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(document, encoding="utf-8")


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    rows = blocked_rows(args.manifest.expanduser().resolve())
    if not rows:
        print("수동 다운로드 대상이 없습니다")
        return 1
    write_html(args.html.expanduser().resolve(), rows)
    write_csv(args.csv.expanduser().resolve(), rows)
    issuers = defaultdict(int)
    for row in rows:
        issuers[row.get("issuer") or "기타"] += 1
    print(json.dumps({"total": len(rows), "by_issuer": dict(issuers)}, ensure_ascii=False))
    print(f"HTML → {args.html.expanduser().resolve()}")
    print(f"CSV → {args.csv.expanduser().resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
