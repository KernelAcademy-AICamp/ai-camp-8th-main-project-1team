#!/usr/bin/env python3
"""카드사 공시 목록에서 2030 우선 추출 후보를 고른다.

이 점수는 최종 추천 순위가 아니다. 법인·제한발급·특수결제 상품을 먼저
제외하고, 2030 소비와 맞닿은 혜택 키워드 및 낮은 비용·실적 조건을 이용해
비싼 LLM 이중 추출을 먼저 수행할 카드만 정한다.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import html
import json
import re
import subprocess
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Optional


BASE = Path(__file__).resolve().parent
DEFAULT_SRC = BASE / "out"
DEFAULT_OUT = DEFAULT_SRC / "youth-card-candidates.json"
DEFAULT_REPORT = DEFAULT_SRC / "youth-card-candidates.md"

ISSUERS = {
    "samsungcard": "삼성카드",
    "hyundaicard": "현대카드",
    "lottecard": "롯데카드",
    "hanacard": "하나카드",
    "kbcard": "KB국민카드",
    "wooricard": "우리카드",
    "nhcard": "NH농협카드",
}

# 일반 사용자에게 추천할 수 없는 상품만 단호하게 제외한다. 프리미엄 상품처럼
# 가격이 높은 상품은 제외하지 않고 감점해 30대 고소득 사용자 선택지를 남긴다.
EXCLUSION_RULES = (
    ("법인·기업 전용", re.compile(
        r"법인|CORPORATE|기업|비즈|\bBIZ\b|BUSINESS|CEO|업무용|사업자|셀러|구매카드|구매전용",
        re.I,
    )),
    ("제한 발급", re.compile(
        r"학생증|교직원|임직원|직원|사원증|조합원|복지|공무원|군인|나라사랑|국군|"
        r"교육사랑|고향사랑|지자체|시청|연금증|다자녀|유공자|온네임|학교|대학교|"
        r"협회|중앙회|장학금|꿈사다리|근로자행복|농업희망|청년동행|청년응원|"
        r"청년희망|청년맞춤|경기청년|지원금|바우처|국제학생증|"
        r"(?:W_|NEW_)?UNI체크|W_SCHOOL|청년.{0,12}(?:지원|정착|도전)",
        re.I,
    )),
    ("특수 결제 전용", re.compile(
        r"주유전용|우편요금|지방세|택시.{0,5}유가보조|화물.{0,5}유가보조|면세유|"
        r"LPG.{0,5}전용|하이패스.{0,5}전용|전용.{0,5}하이패스",
        re.I,
    )),
    ("선불·기프트", re.compile(r"선불|기프트카드", re.I)),
    # 카드가 아니라 카드에 딸린 문서다. 본체 상품이 따로 공시되므로 그대로 두면
    # 같은 카드를 두 번 추출하게 된다. (현대 570장 중 15장)
    ("부속 안내서", re.compile(r"안내서|선택서비스|기본서비스", re.I)),
)

KEYWORD_GROUPS = (
    ("온라인·간편결제", 3, re.compile(
        r"온라인|간편결제|네이버페이|카카오페이|삼성페이|PAYCO|페이코|쿠팡|무신사|쇼핑",
        re.I,
    )),
    ("교통", 3, re.compile(
        r"대중교통|K-?패스|기후동행|시내버스|고속버스|버스요금|지하철|택시요금",
        re.I,
    )),
    ("생활소비", 2, re.compile(r"배달|음식점|외식|카페|커피|편의점", re.I)),
    ("통신·구독", 2, re.compile(
        r"이동통신|통신요금|OTT|구독|넷플릭스|유튜브|디즈니\+?|멜론|스트리밍",
        re.I,
    )),
    ("여행·해외", 1, re.compile(r"해외|여행|항공|라운지|트래블|마일리지", re.I)),
    ("문화·여가", 1, re.compile(r"영화|문화|공연|놀이|레저", re.I)),
    ("청년 친화 상품", 2, re.compile(r"\bYOUNG\b|MZ|청년", re.I)),
)

ANNUAL_FEE_MAN = re.compile(r"연회비.{0,80}?(\d+(?:\.\d+)?)\s*만\s*원", re.I | re.S)
ANNUAL_FEE_WON = re.compile(r"연회비.{0,80}?([\d,]{3,})\s*원", re.I | re.S)
PERFORMANCE_MAN = re.compile(
    r"전월.{0,50}?(?:실적|이용금액).{0,50}?(\d+(?:\.\d+)?)\s*만\s*원",
    re.I | re.S,
)


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="2030 우선 카드 추출 후보 선정")
    parser.add_argument("--src", type=Path, default=DEFAULT_SRC, help="카드사별 metadata 상위 폴더")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="후보 JSON")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT, help="검토용 Markdown")
    parser.add_argument("--per-issuer", type=int, default=25, help="카드사별 최대 선정 수")
    parser.add_argument("--check-min", type=int, default=5, help="카드사별 체크카드 최소 목표 수")
    parser.add_argument("--group-cap", type=int, default=6, help="카드사별 동일 혜택군 최대 수")
    parser.add_argument("--total", type=int, default=175, help="전체 최대 선정 수")
    parser.add_argument("--no-pdf-text", action="store_true", help="상품명과 파일명만으로 점수 계산")
    parser.add_argument(
        "--include-unverified-status", action="store_true",
        help="발급 상태를 확인할 수 없는 공시 문서도 검토 후보에 포함",
    )
    args = parser.parse_args(argv)
    if args.per_issuer <= 0 or args.total <= 0:
        parser.error("--per-issuer와 --total은 0보다 커야 합니다")
    if args.check_min < 0 or args.group_cap <= 0:
        parser.error("--check-min은 0 이상, --group-cap은 0보다 커야 합니다")
    return args


def stable_id(value: Any) -> str:
    raw = str(value or "").strip()
    if raw and len(raw) <= 30:
        return raw
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:20]


def inferred_card_type(name: str, explicit: Any = None) -> str:
    value = str(explicit or "").upper()
    if "체크" in name.upper() or " CHECK" in f" {name.upper()}":
        return "CHECK"
    if value in {"CHECK", "1", "체크", "체크카드"}:
        return "CHECK"
    if value in {"CREDIT", "0", "신용", "신용카드"}:
        return "CREDIT"
    return "UNKNOWN"


def date_value(value: Any) -> str | None:
    raw = str(value or "").strip()
    if not raw:
        return None
    digits = re.sub(r"\D", "", raw)
    if len(digits) == 8:
        return f"{digits[:4]}-{digits[4:6]}-{digits[6:]}"
    return raw


def document_rank(document: dict[str, Any]) -> tuple[int, str]:
    name = " ".join(str(document.get(key) or "") for key in ("filename", "title", "doc_type_label"))
    score = 0
    if document.get("doc_type") == "goods_desc":
        score += 100
    if re.search(r"상품설명|상품안내|가이드북|이용안내|약관", name, re.I):
        score += 20
    if re.search(r"국제브랜드|보이스아이|표준약관", name, re.I):
        score -= 100
    date = str(document.get("begin_date") or document.get("revision_date") or "")
    return score, date


def choose_document(documents: Iterable[dict[str, Any]]) -> dict[str, Any] | None:
    usable = [doc for doc in documents if doc.get("url") and doc.get("status") != "no_file"]
    return max(usable, key=document_rank) if usable else None


def candidate(
    issuer: str,
    name: Any,
    product_id: Any,
    card_type: Any,
    posted_at: Any,
    source_page: Any,
    document: dict[str, Any] | None,
    metadata_root: Path,
    active_verified: bool,
) -> dict[str, Any]:
    clean_name = re.sub(r"\s+", " ", html.unescape(str(name or ""))).strip()
    relative = document.get("relative_path") if document else None
    local = metadata_root / relative if relative else None
    return {
        "issuer": issuer,
        "name": clean_name,
        "product_id": stable_id(product_id or clean_name),
        "card_type": inferred_card_type(clean_name, card_type),
        "posted_at": date_value(posted_at),
        "source_page": source_page,
        "pdf_url": document.get("url") if document else None,
        "pdf_path": str(local) if local and local.exists() else None,
        "relative_path": relative,
        "document_name": document.get("filename") if document else None,
        "active_verified": active_verified,
    }


def products_from_metadata(path: Path) -> list[dict[str, Any]]:
    root = json.loads(path.read_text(encoding="utf-8"))
    directory = path.parent.name
    issuer = ISSUERS.get(directory)
    if not issuer:
        return []
    source_page = root.get("source_page")
    out = []

    if directory == "kbcard":
        for key, category in (root.get("categories") or {}).items():
            for product in category.get("products") or []:
                out.append(candidate(
                    issuer, product.get("product_name"), product.get("card_code"), key,
                    product.get("launch_date"), source_page,
                    choose_document(product.get("files") or []), path.parent, True,
                ))
        return out

    if directory == "nhcard":
        for record in root.get("files") or []:
            out.append(candidate(
                issuer, record.get("product_name"), record.get("history_seq") or record.get("url"),
                record.get("card_type"), None, source_page, record, path.parent,
                False,
            ))
            for shared_name in record.get("shared_with") or []:
                shared = candidate(
                    issuer, shared_name, f"{record.get('history_seq')}:{shared_name}",
                    record.get("card_type"), None, source_page, record, path.parent,
                    False,
                )
                shared["shared_pdf"] = True
                out.append(shared)
        return out

    for product in root.get("products") or []:
        documents = product.get("documents") or product.get("files") or []
        identity = (
            product.get("sqno") or product.get("docid") or product.get("DOCID")
            or product.get("itg_blbd_sn") or product.get("product_code")
        )
        posted = (
            product.get("launch_date") or product.get("published_date")
            or product.get("start_date") or product.get("date")
        )
        status_known, active = issuance_state(directory, product)
        item = candidate(
            issuer, product.get("product_name"), identity, product.get("card_type"),
            posted, source_page, choose_document(documents), path.parent,
            status_known,
        )
        item["active"] = active
        out.append(item)
    return out


def issuance_state(directory: str, product: dict[str, Any]) -> tuple[bool, bool]:
    """(발급 상태를 아는가, 발급 가능한가).

    카드사 이름이 아니라 공시 데이터에서 읽는다. 현대는 `issued`, 삼성은
    `discontinued`를 상품마다 내려준다. 그 필드가 없는 카드사는 수집 단계에서
    중단분을 걸렀는지로 갈린다 — 롯데·우리는 걸렀고, 농협은 카드사가 상태를
    아예 공시하지 않아 판단을 보류한다.
    """
    issued = product.get("issued")
    if issued is not None:
        return True, bool(issued)
    discontinued = product.get("discontinued")
    if discontinued is not None:
        return True, not discontinued
    return directory in {"lottecard", "wooricard"}, True


def hana_candidates(script: Path) -> list[dict[str, Any]]:
    if not script.exists():
        return []
    tree = ast.parse(script.read_text(encoding="utf-8"))
    targets = None
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "PDF_TARGETS" for target in node.targets
        ):
            targets = ast.literal_eval(node.value)
            break
    if not isinstance(targets, list):
        return []
    out = []
    for name, url in targets:
        stem = Path(url.split("?", 1)[0]).stem
        product_number = stem.split("_", 1)[0]
        document = {"url": url, "filename": Path(url).name, "status": "remote"}
        out.append(candidate(
            "하나카드", name, product_number, None,
            stem.split("_", 1)[1] if "_" in stem else None,
            "https://www.hanacard.co.kr/", document, Path(), True,
        ))
    return out


def load_candidates(src: Path) -> list[dict[str, Any]]:
    candidates = []
    for path in sorted(src.glob("*/metadata.json")):
        candidates.extend(products_from_metadata(path))
    candidates.extend(hana_candidates(BASE / "hanacard_downloader.py"))
    unique = {}
    for item in candidates:
        key = (item["issuer"], item["product_id"])
        if key not in unique or (not unique[key].get("pdf_url") and item.get("pdf_url")):
            unique[key] = item
    return list(unique.values())


def pdf_text(path: str | None) -> str:
    if not path:
        return ""
    try:
        result = subprocess.run(
            ["pdftotext", "-layout", path, "-"], capture_output=True, text=True,
            timeout=60, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout if result.returncode == 0 else ""


def first_money(pattern: re.Pattern[str], text: str, multiplier: int = 1) -> int | None:
    match = pattern.search(text)
    if not match:
        return None
    return int(float(match.group(1).replace(",", "")) * multiplier)


def exclusion_reason(name: str) -> str | None:
    for label, pattern in EXCLUSION_RULES:
        if pattern.search(name):
            return label
    return None


def score_candidate(
    item: dict[str, Any], use_pdf_text: bool = True, include_unverified_status: bool = False,
) -> dict[str, Any]:
    text = pdf_text(item.get("pdf_path")) if use_pdf_text else ""
    searchable = " ".join(filter(None, (item["name"], item.get("document_name"), text)))
    # 카드명에 붙은 후불교통/비교통은 결제 기능이지 교통 할인 혜택이 아니다.
    searchable = re.sub(r"후불교통|비교통", " ", searchable, flags=re.I)
    reason = exclusion_reason(item["name"])
    if not item.get("active", True):
        # 발급이 끝난 것이 확인된 상품. 신청할 수 없으니 추천 후보가 아니다.
        # 데이터 자체는 수집본에 남는다 — 이미 보유한 사용자의 혜택 계산에 쓴다.
        reason = "발급중단"
    elif not item.get("active_verified", True) and not include_unverified_status:
        reason = "발급 상태 미확인"
    score = 3  # 일반 발급 가능 상품을 기본값으로 둔다.
    reasons = [{"label": "일반 발급 후보", "points": 3}]
    matched_groups = []
    for label, points, pattern in KEYWORD_GROUPS:
        if pattern.search(searchable):
            score += points
            reasons.append({"label": label, "points": points})
            matched_groups.append(label)

    annual_fee = first_money(ANNUAL_FEE_MAN, text, 10000)
    if annual_fee is None:
        annual_fee = first_money(ANNUAL_FEE_WON, text)
    performance = first_money(PERFORMANCE_MAN, text, 10000)
    if annual_fee is not None and annual_fee <= 30000:
        score += 2
        reasons.append({"label": "연회비 3만원 이하", "points": 2})
    elif annual_fee is not None and annual_fee > 100000:
        score -= 3
        reasons.append({"label": "연회비 10만원 초과", "points": -3})
    if performance is not None and performance <= 500000:
        score += 2
        reasons.append({"label": "최소 실적 50만원 이하", "points": 2})
    elif performance is not None and performance > 1000000:
        score -= 2
        reasons.append({"label": "최소 실적 100만원 초과", "points": -2})
    if re.search(
        r"INFINITE|VVIP|PRESTIGE|PREMIUM|PLATINUM|ROYAL\s*BLUE|THE\s+(?:BLACK|PURPLE|RED)|플래티늄",
        item["name"], re.I,
    ):
        score -= 2
        reasons.append({"label": "프리미엄 상품", "points": -2})

    item.update({
        "eligible": reason is None,
        "exclusion_reason": reason,
        "youth_priority_score": score,
        "score_reasons": reasons,
        "matched_groups": matched_groups,
        "annual_fee_hint_krw": annual_fee,
        "performance_hint_krw": performance,
        "evidence_level": "PDF_TEXT" if text else "NAME_ONLY",
        "text_chars": len(re.sub(r"\s", "", text)),
    })
    return item


def sort_key(item: dict[str, Any]) -> tuple[Any, ...]:
    return (
        -item["youth_priority_score"],
        item["evidence_level"] != "PDF_TEXT",
        -(int(re.sub(r"\D", "", item.get("posted_at") or "0") or 0)),
        item["name"],
    )


def shortlist(
    candidates: list[dict[str, Any]], per_issuer: int, check_min: int, total: int,
    group_cap: int = 6,
) -> list[dict[str, Any]]:
    by_issuer: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in candidates:
        if item["eligible"]:
            by_issuer[item["issuer"]].append(item)

    issuer_lists = {}
    for issuer, items in by_issuer.items():
        ordered = sorted(items, key=sort_key)
        picked = []
        picked_ids = set()
        group_counts: Counter[str] = Counter()

        def add(item: dict[str, Any]) -> bool:
            groups = item.get("matched_groups") or []
            if any(group_counts[group] >= group_cap for group in groups):
                return False
            picked.append(item)
            picked_ids.add(id(item))
            group_counts.update(groups)
            return True

        for item in ordered:
            if sum(row["card_type"] == "CHECK" for row in picked) >= check_min:
                break
            if item["card_type"] == "CHECK":
                add(item)
        for item in ordered:
            if len(picked) >= per_issuer:
                break
            if id(item) not in picked_ids:
                add(item)
        picked.sort(key=sort_key)
        issuer_lists[issuer] = picked

    # 카드사 하나가 전체를 점유하지 않도록 한 장씩 돌아가며 채운다.
    selected = []
    for index in range(per_issuer):
        for issuer in sorted(issuer_lists):
            if index < len(issuer_lists[issuer]):
                selected.append(issuer_lists[issuer][index])
                if len(selected) >= total:
                    return selected
    return selected


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def write_report(path: Path, payload: dict[str, Any]) -> None:
    lines = [
        "# 2030 카드 추출 후보", "",
        "> 이 점수는 LLM 추출 순서를 정하기 위한 값이며 사용자 추천 순위가 아니다.", "",
        f"- 원본 후보: {payload['summary']['loaded']}장",
        f"- 일반 추천 제외: {payload['summary']['excluded']}장",
        f"- 선정: {payload['summary']['selected']}장",
        f"- PDF 본문 근거: {payload['summary']['pdf_text_evidence']}장",
        "", "## 카드사별 현황", "",
        "| 카드사 | 원본 | 제외 | 선정 | 체크 |", "|---|---:|---:|---:|---:|",
    ]
    for issuer, row in payload["by_issuer"].items():
        lines.append(
            f"| {issuer} | {row['loaded']} | {row['excluded']} | {row['selected']} | {row['checks']} |"
        )
    lines.extend(["", "## 선정 카드", ""])
    for item in payload["selected"]:
        reasons = ", ".join(reason["label"] for reason in item["score_reasons"] if reason["points"] > 0)
        lines.append(
            f"- **{item['issuer']} · {item['name']}** — {item['youth_priority_score']}점"
            f" · {item['card_type']} · {item['evidence_level']} · {reasons}"
        )
    lines.extend(["", "## 제외 사유", ""])
    for label, count in payload["exclusion_reasons"].items():
        lines.append(f"- {label}: {count}장")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_payload(candidates: list[dict[str, Any]], selected: list[dict[str, Any]]) -> dict[str, Any]:
    selected_keys = {(item["issuer"], item["product_id"]) for item in selected}
    issuer_rows = {}
    for issuer in sorted({item["issuer"] for item in candidates}):
        rows = [item for item in candidates if item["issuer"] == issuer]
        chosen = [item for item in rows if (item["issuer"], item["product_id"]) in selected_keys]
        issuer_rows[issuer] = {
            "loaded": len(rows),
            "excluded": sum(not item["eligible"] for item in rows),
            "selected": len(chosen),
            "checks": sum(item["card_type"] == "CHECK" for item in chosen),
        }
    exclusion_counts = Counter(
        item["exclusion_reason"] for item in candidates if item.get("exclusion_reason")
    )
    return {
        "schema_version": 1,
        "purpose": "LLM_EXTRACTION_PRIORITY_NOT_RECOMMENDATION_RANK",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": {
            "loaded": len(candidates),
            "excluded": sum(not item["eligible"] for item in candidates),
            "eligible": sum(item["eligible"] for item in candidates),
            "selected": len(selected),
            "pdf_text_evidence": sum(item["evidence_level"] == "PDF_TEXT" for item in selected),
        },
        "by_issuer": issuer_rows,
        "exclusion_reasons": dict(sorted(exclusion_counts.items())),
        "selected": selected,
        "excluded": [item for item in candidates if not item["eligible"]],
    }


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    candidates = load_candidates(args.src.expanduser().resolve())
    if not candidates:
        raise SystemExit(f"카드사 metadata를 찾지 못했습니다: {args.src}")
    scored = [
        score_candidate(item, not args.no_pdf_text, args.include_unverified_status)
        for item in candidates
    ]
    selected = shortlist(scored, args.per_issuer, args.check_min, args.total, args.group_cap)
    payload = build_payload(scored, selected)
    write_json(args.out.expanduser().resolve(), payload)
    write_report(args.report.expanduser().resolve(), payload)
    print(json.dumps(payload["summary"], ensure_ascii=False))
    print(f"후보 JSON → {args.out.expanduser().resolve()}")
    print(f"검토 보고서 → {args.report.expanduser().resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
