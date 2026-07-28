#!/usr/bin/env python3
"""보관된 원문 HTML → out/cards.json (검수 대기 상태의 중간 산출물)

★ 카테고리 매핑은 자동으로 하지 않는다.
   `음식점·카페·편의점·온라인쇼핑 5%`를 우리 카테고리(배달·외식 / 카페·간식 / 편의점 / 쇼핑)로
   쪼개는 건 판단이 필요한 일이라 사람이 한다. 여기서는 원문을 그대로 남기고
   `mapped_categories: []`를 비워 둔 채 후보만 제안한다. 비어 있으면 아직 미검수라는 뜻이다.

의존성 없음. 실행: python3 scripts/collect-cards/extract.py
"""
import html
import json
import re
from pathlib import Path

BASE = Path(__file__).resolve().parent
RAW = BASE / "out" / "raw"
META = BASE / "out" / "meta.json"
OUT = BASE / "out" / "cards.json"

# 신한 아이콘 클래스 → 우리 카테고리 후보. 확정이 아니라 '검수자에게 주는 힌트'다.
ICON_HINT = {
    "cafe-bakery": ["카페·간식"],
    "store": ["편의점", "쇼핑"],
    "subscription": ["구독·OTT"],
    "transport": ["택시", "교통"],
    "food": ["배달·외식"],
}
# 혜택 문구에 이 낱말이 보이면 후보로 올린다.
TEXT_HINT = {
    "배달·외식": ["음식점", "배달", "외식", "food"],
    "카페·간식": ["카페", "베이커리", "커피"],
    "편의점": ["편의점"],
    "쇼핑": ["쇼핑", "온라인쇼핑", "백화점", "마트"],
    "구독·OTT": ["OTT", "구독", "멤버십", "스트리밍"],
    "택시": ["택시"],
    "교통": ["대중교통", "지하철", "버스"],
    "통신": ["통신", "SKT", "KT", "LGU"],
}


def text_of(fragment: str) -> str:
    """태그를 지우고 공백을 정리한다."""
    t = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", fragment, flags=re.S | re.I)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", html.unescape(t)).strip()


def parse_shinhan(doc: str) -> dict:
    """신한카드 상품 페이지 파서."""
    title = re.search(r"<title>(.*?)</title>", doc, re.S)
    name = text_of(title.group(1)).split("|")[0].strip() if title else None

    benefits = []
    for block in re.findall(r"<li[^>]*benefit-list__item[^>]*>(.*?)</li>", doc, re.S):
        raw = text_of(block)
        if not raw:
            continue
        icons = re.findall(r"icon--benefit\s+([a-z0-9-]+)", block)
        rates = [int(x) for x in re.findall(r"(\d+)\s*%", raw)]

        cand = []
        for ic in icons:
            cand += ICON_HINT.get(ic, [])
        for cat, words in TEXT_HINT.items():
            if any(w.lower() in raw.lower() for w in words):
                cand.append(cat)

        benefits.append({
            "raw_text": raw[:400],
            "icon_classes": icons,
            "rates_percent_found": sorted(set(rates), reverse=True),
            "category_candidates": sorted(set(cand)),   # 힌트일 뿐
            "mapped_categories": [],                    # ← 사람이 채운다
            "discount_percent": None,                   # ← 사람이 채운다
            "performance_start": None,
            "performance_end": None,
            "monthly_limit": None,
        })

    body = text_of(doc)
    perf = re.findall(r"전월\s*(?:이용금액|실적)[^.]{0,60}", body)[:4]
    limit = re.findall(r"(?:월\s*)?(?:통합\s*)?(?:할인|적립)\s*한도[^.]{0,60}", body)[:4]

    return {
        "issuer": "신한카드",
        "product_name": name,
        # 연회비는 JS로 채워지는 자리라 원문 HTML에 없다. 검수 때 직접 넣는다.
        "annual_fee": None,
        "benefits": benefits,
        "performance_notes_raw": [re.sub(r"\s+", " ", p).strip() for p in perf],
        "limit_notes_raw": [re.sub(r"\s+", " ", l).strip() for l in limit],
    }


# 혜택이 아닌데 %가 들어가 잡히는 문구들 — 대출·연체 안내가 대표적이다.
NOISE = re.compile(
    r"연체이자율|정상이자율|리볼빙|카드대출|중도상환|취급수수료|수수료율|"
    r"할부\s*수수료|여신금융협회\s*공시|개인신용평점|고정금리|변동금리"
)


def parse_kb(doc: str) -> dict:
    """KB국민카드 상품 페이지 파서.

    신한과 반대로 연회비는 HTML에 있고(cardDetailInfo), 혜택은 배너 문구로 흩어져 있다.
    """
    title = re.search(r"<title>(.*?)</title>", doc, re.S)
    raw_title = text_of(title.group(1)) if title else ""
    # 제목 형식: "[My WE:SH 카드] KB Pay/음식/OTT 10~30%, … - KB 국민카드"
    m = re.match(r"\[([^\]]+)\]", raw_title)
    name = m.group(1).strip() if m else raw_title.split("-")[0].strip() or None

    fee = None
    box = re.search(r'class="[^"]*cardDetailInfo[^"]*"[^>]*>(.{0,1200})', doc, re.S)
    if box:
        fees = re.findall(r"([\d,]+)\s*원", text_of(box.group(1)))
        if fees:
            fee = int(fees[0].replace(",", ""))   # 첫 값 = 국내 연회비

    body = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", doc, flags=re.S | re.I)
    seen, benefits = set(), []
    for tag in ("li", "dd", "p", "strong", "span"):
        for frag in re.findall(rf"<{tag}[^>]*>(.{{0,300}}?)</{tag}>", body, re.S):
            t = text_of(frag)
            if not (8 < len(t) < 160) or not re.search(r"\d+\s*%", t):
                continue
            if NOISE.search(t) or t in seen:
                continue
            seen.add(t)
            cand = [c for c, ws in TEXT_HINT.items()
                    if any(w.lower() in t.lower() for w in ws)]
            benefits.append({
                "raw_text": t,
                "icon_classes": [],
                "rates_percent_found": sorted({int(x) for x in re.findall(r"(\d+)\s*%", t)},
                                              reverse=True),
                "category_candidates": sorted(set(cand)),
                "mapped_categories": [],
                "discount_percent": None,
                "performance_start": None,
                "performance_end": None,
                "monthly_limit": None,
            })

    flat = text_of(body)
    return {
        "issuer": "KB국민카드",
        "product_name": name,
        "annual_fee": fee,
        "benefits": benefits,
        "performance_notes_raw": [re.sub(r"\s+", " ", p).strip()
                                  for p in re.findall(r"전월\s*(?:이용금액|실적)[^.]{0,60}", flat)[:4]],
        "limit_notes_raw": [re.sub(r"\s+", " ", l).strip()
                            for l in re.findall(r"(?:월\s*)?(?:통합\s*)?(?:할인|적립)\s*한도[^.]{0,60}", flat)[:4]],
        "title_summary": raw_title[:200],   # 제목에 혜택 요약이 담겨 검수에 쓸모 있다
    }


# 현재 쓰는 파서. 여기 없는 호스트는 원문만 보관하고 추출은 건너뛴다.
#
# ★ KB(parse_kb)는 일부러 뺐다 — 아래 '보류' 참조. 원문 HTML은 out/raw/에 남아 있으므로
#   파서만 고치면 다시 긁지 않고 추출부터 이어서 할 수 있다.
PARSERS = {
    "www.shinhancard.com": parse_shinhan,
}

# ── 보류: KB국민카드 (2026-07-28) ────────────────────────────────────────
# parse_kb는 페이지 전체에서 '%'가 든 문장을 훑는 방식이라 **같은 페이지에 붙은 다른 카드의
# 배너 혜택까지 가져온다.** 실제로 서로 다른 두 카드에서 동일한 문장이 추출됐다.
#
#   My WE:SH 카드     "OTT 50%, APP 30%, 여가 20%, 교통 20%…"
#   YOU Wish up 카드  "OTT 50%, APP 30%, 여가 20%, 교통 20%…"   ← 같은 문장
#
# 노이즈 정규식으로는 못 고친다(문장 자체는 멀쩡한 혜택 문구다). 신한처럼 '이 카드의 혜택'만
# 감싼 마크업을 찾아 그 블록 안에서만 뽑도록 재작성해야 한다.
# 그때까지 잘못된 데이터가 검수 단계로 넘어가지 않도록 PARSERS에서 제외한다.
# ─────────────────────────────────────────────────────────────────────


def main() -> None:
    if not META.exists():
        raise SystemExit(f"meta.json이 없습니다. 먼저 fetch.py를 실행하세요: {META}")
    meta = json.loads(META.read_text(encoding="utf-8"))

    cards, unsupported = [], set()
    for url, info in meta.items():
        fname = info.get("file")
        if not fname:
            continue
        path = RAW / fname
        if not path.exists():
            continue

        host = re.sub(r"^https?://([^/]+).*$", r"\1", url)
        parser = PARSERS.get(host)
        if parser is None:
            unsupported.add(host)
            continue

        doc = path.read_text(encoding="utf-8", errors="replace")
        card = parser(doc)
        card["source_url"] = url
        card["fetched_at"] = info.get("fetched_at")
        card["review_status"] = "pending"   # 사람이 매핑을 채우면 'done'으로 바꾼다
        cards.append(card)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(cards, ensure_ascii=False, indent=2), encoding="utf-8")

    total_benefits = sum(len(c["benefits"]) for c in cards)
    print(f"카드 {len(cards)}건 · 혜택 항목 {total_benefits}건 → {OUT}")
    if unsupported:
        print(f"파서 없는 호스트(무시): {', '.join(sorted(unsupported))}")
    print("\n다음 할 일 — cards.json을 열어 각 혜택의")
    print("  mapped_categories · discount_percent · performance_start/end · monthly_limit")
    print("을 채우고 review_status를 'done'으로 바꾸세요. 그 뒤 시드로 넣습니다.")


if __name__ == "__main__":
    main()
