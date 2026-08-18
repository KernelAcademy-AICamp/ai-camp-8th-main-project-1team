#!/usr/bin/env python3
"""카드사 수집기 산출물 → LLM 추출기 매니페스트.

  out/<카드사>/metadata.json  ──→  out/<카드사>/extract-manifest.json  ──→  extract_llm.py --manifest

## 왜 변환이 필요한가

수집기는 **카드사 공시 구조 그대로** 적는다 — 상품 하나 밑에 파일이 여럿이고, 칸 이름도
카드사마다 다르다(`stop_date`·`suspended_date`·`issuance_ended`). 추출기는 **PDF 한 장 =
카드 한 장**인 평평한 목록을 받는다. 둘을 직접 잇지 않고 여기서 옮긴다.

## 상품 하나에 PDF가 여러 장일 때 — 무엇을 고르나

현대카드 한 상품에 파일이 평균 9장이다(가이드북·약관개정·해외이용안내·상품설명서…).
**상품설명서가 먼저고, 없으면 단독 가이드북으로 물러선다.**

처음에는 상품설명서만 읽기로 했다. 그런데 그건 문서를 열어 보지 않고 세운 가정이었고,
실제로 대조해 보니 **가이드북에도 필요한 것이 다 있었다** — SC제일은행 the Red Stripe
가이드북 8쪽에 적립률 구간(`50만원 이상 1%, 100만원 이상 1.5%…`)·실적 제외 목록·연회비·
여신금융협회 심의필 날짜가 모두 있다(2026-08-14 원문 대조). 상품설명서만 고집하면
현대 B군 74장 중 53장을 통째로 버리게 된다.

**여러 카드를 묶은 가이드북은 아직 쓰지 않는다.** 네 카드가 한 지면에 3단으로 들어가
있어(`M_X CHECK 하이브리드 통합`, 14쪽) 어느 카드의 숫자인지 뒤바뀔 위험이 단독본보다 크다.

같은 종류가 **여러 장이면 파일명 앞 날짜가 가장 큰 것**을 고른다(`2410_…` = 2024-10).
날짜가 없으면 이름이 짧은 쪽 — 개정본은 접두사가 붙어 길어지므로 짧은 쪽이 원본이다.

읽을 문서가 아예 없는 상품은 **건너뛰고 사유를 남긴다.** 조용히 빠뜨리면 "다 넣었다"로
읽힌다.
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from collector_policy import abc_group, real_stop_date  # noqa: E402

# 파일명이 상품설명서임을 알리는 말. 카드사마다 표기가 조금씩 다르다.
DESCRIPTION_WORDS = ("상품설명서", "상품 설명서", "prdctOpmn")
# 상품설명서가 없을 때 물러설 곳.
#
# **가이드북도 같은 것을 담고 있다**(2026-08-14 원문 대조 — SC제일은행 the Red Stripe
# 가이드북 8쪽에 적립률 구간·실적 제외 목록·연회비·여신금융협회 심의필 날짜가 모두 있었다).
# 처음에는 상품설명서만 읽기로 했는데, 그건 문서를 열어 보지 않고 세운 가정이었다.
GUIDE_WORDS = ("가이드북", "이용안내", "상품안내")
# 여러 카드를 한 권에 묶은 가이드북. **지금은 쓰지 않는다** — 네 카드가 한 지면에 3단으로
# 들어가 있어(현대 `M_X CHECK 하이브리드 통합`, 14쪽) 어느 카드의 숫자인지 뒤바뀔 위험이
# 단독본보다 크다. 단독본 결과를 보고 판단할 자리다.
BUNDLED = re.compile(r"통합|외 ?\d+ ?종|[_ ]M[_ ]X[_ ]")
# 파일명 앞머리의 개정 시점 — `2410_…`(2024-10) 또는 `20241015_…`.
LEADING_DATE = re.compile(r"^(?:.*?[_\s])?(\d{6}|\d{8})[_\s]")


def file_name(row: Dict[str, Any]) -> str:
    return str(row.get("raw_filename") or row.get("filename") or row.get("relative_path") or "")


def is_description(row: Dict[str, Any]) -> bool:
    name = file_name(row)
    return any(word in name for word in DESCRIPTION_WORDS)


def revision_key(row: Dict[str, Any]) -> Tuple[int, int]:
    """(앞머리 날짜, 이름 짧은 순). 큰 값이 최신이다."""
    name = file_name(row)
    match = LEADING_DATE.search(Path(name).name)
    stamp = 0
    if match:
        digits = match.group(1)
        stamp = int(digits) if len(digits) == 8 else int(digits) * 100
    return stamp, -len(name)


def is_single_guide(row: Dict[str, Any]) -> bool:
    name = file_name(row)
    return any(word in name for word in GUIDE_WORDS) and not BUNDLED.search(name)


def pick_document(files: List[Dict[str, Any]]) -> Tuple[Optional[Dict[str, Any]], str]:
    """읽을 문서 한 장과 그 종류. 상품설명서가 먼저고, 없으면 단독 가이드북이다.

    어느 종류를 읽었는지 매니페스트에 남긴다 — 추출 결과가 나빴을 때 **문서 탓인지
    추출 탓인지**를 가르려면 그 칸이 있어야 한다.
    """
    usable = [
        row for row in files
        if row.get("status") in {"downloaded", "existing", "manual_imported"}
    ]
    for kind, test in (("상품설명서", is_description), ("가이드북", is_single_guide)):
        found = [row for row in usable if test(row)]
        if found:
            return max(found, key=revision_key), kind
    return None, ""


def stop_value(product: Dict[str, Any]) -> Any:
    """카드사마다 다른 중단 신호를 하나로 모은다. 없으면 빈 값(=발급 중)."""
    for key in ("stop_date", "suspended_date", "issuance_ended_raw", "stop_date_raw"):
        value = product.get(key)
        if value is True:                      # 롯데 `issuance_ended` 는 불리언이다
            return "발급종료"
        if real_stop_date(value):
            return value
    return ""


def build(root: Dict[str, Any], issuer: str, baseline, only_groups) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    rows: List[Dict[str, Any]] = []
    skipped: List[Dict[str, Any]] = []
    products = root.get("products")
    if products is None:                      # KB 처럼 분류별로 나뉜 산출물
        products = [p for c in (root.get("categories") or {}).values() for p in c["products"]]

    for product in products:
        name = product.get("product_name") or product.get("name")
        stop = stop_value(product)
        group, reason = abc_group(name, stop, baseline) if baseline else ("A", "분류 안 함")
        if baseline and group not in only_groups:
            skipped.append({"name": name, "reason": f"{group} — {reason}"})
            continue

        chosen, kind = pick_document(product.get("files") or [])
        if chosen is None:
            skipped.append({"name": name, "reason": "읽을 문서 없음(통합 가이드북·약관뿐이다)"})
            continue

        rows.append({
            "issuer": issuer,
            "name": name,
            "product_id": str(product.get("sqno") or product.get("card_code")
                              or product.get("docid") or product.get("itg_blbd_sn") or name),
            "posted_at": product.get("launch_date") or product.get("published_date") or product.get("date"),
            # 발급 상태의 정본. 추출기가 이걸 보고 status 를 적는다(`extract_llm.status_of`).
            "stop_date": stop or None,
            "document_kind": kind,
            "pdf_url": chosen.get("url"),
            "relative_path": chosen.get("relative_path"),
            "status": chosen.get("status"),
        })
    return rows, skipped


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--src", type=Path, required=True, help="수집기 출력 디렉터리")
    parser.add_argument("--issuer", required=True, help="발급사 이름(예: 현대카드)")
    parser.add_argument("--out", type=Path, help="매니페스트 경로(기본: <src>/extract-manifest.json)")
    parser.add_argument("--abc-baseline", metavar="YYYY-MM-DD", help="A/B/C 분류 기준선")
    parser.add_argument("--groups", default="AB", help="담을 군(기본 AB)")
    args = parser.parse_args(argv)

    baseline = None
    if args.abc_baseline:
        import datetime
        baseline = datetime.date.fromisoformat(args.abc_baseline)

    root = json.loads((args.src / "metadata.json").read_text(encoding="utf-8"))
    rows, skipped = build(root, args.issuer, baseline, set(args.groups))
    out = args.out or (args.src / "extract-manifest.json")
    out.write_text(json.dumps({
        "schema_version": 1,
        "purpose": "extract_llm.py 입력 — 상품설명서 한 장씩",
        "source": str(args.src),
        "abc_baseline": args.abc_baseline,
        "files": rows,
        # 조용히 줄이지 않는다. 무엇을 왜 뺐는지 산출물에 남긴다.
        "skipped": skipped,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"매니페스트 {len(rows)}장 → {out}")
    stopped = sum(1 for row in rows if row["stop_date"])
    print(f"  발급 중 {len(rows) - stopped} · 발급 중단 {stopped}")
    kinds = collections.Counter(row["document_kind"] for row in rows)
    print("  읽을 문서: " + " · ".join(f"{k} {v}장" for k, v in kinds.most_common()))
    print(f"  건너뜀 {len(skipped)}장")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
