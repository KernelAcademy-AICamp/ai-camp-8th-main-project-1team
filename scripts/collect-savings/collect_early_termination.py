#!/usr/bin/env python3
"""중도해지이율 수집 — 금감원에 없는 칸(M10)을 각 은행 상품공시에서 모은다.

    python3 scripts/collect-savings/collect_early_termination.py \
        --out backend/src/main/resources/savings/early-termination.json

**왜 따로 모으나.** 금감원 금융상품통합비교공시는 중도해지이율을 주지 않는다. `mtrt_int`가 이름이
비슷하지만 *만기 후* 이자율이라 다른 값이다. 그런데 FP-01의 M2가 파킹통장을 위로 올리는 근거가
"적금은 중도에 깨면 손해"라서, 그 손해의 크기를 모른 채 규칙만 서 있었다
(`07_취향분석및추천_Agent_설계.md` §4.5 M10).

**지키는 선** — 카드 수집(§4.4)과 같다.
  1. 각 은행 자사 공시(1차 원천)만 쓴다. 집계처는 거치지 않는다.
  2. `robots.txt`가 막아 둔 곳은 **우회하지 않는다.** 은행연합회 소비자포털은 `Disallow: /`라
     쓰지 않는다(카드다모아와 같은 판단).
  3. 수집 기준일(`as_of`)을 반드시 남긴다. 화면이 "이 시점 공시 기준"이라고 밝혀야 한다.

**표기 모양은 실제 공시에서 역으로 뽑았다** (케이뱅크 `코드K 자유적금`):

    1개월(30일) 미만    연 0.10 %
    1개월(30일) 이상    연 0.30 %
    6개월(180일) 이상   기본금리 x 70% x 경과일수/계약일수 (최저 연 0.50 %)

구간은 `이상`(하한)으로 끊기고, 배수 구간에는 **경과일수 비례**와 **최저이율**이 붙는다.
`미만`으로 적힌 첫 줄은 하한 0으로 접는다.

**수집 현황은 `--out` 파일의 `_현황`이 정본이다.** 지금 뚫린 곳만 들어 있고, 나머지 은행은
`robots.txt` 확인부터 다시 해야 한다. 없는 은행의 상품은 로더가 `null`로 두고 화면이 그 자리를
비운다 — 0%로 메우면 "깨도 손해 없다"는 거짓말이 된다.
"""

import argparse
import html
import json
import re
import sys
import urllib.request
from datetime import date, timezone, timedelta

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# 수집 대상 — `robots.txt`가 명시 허용한 곳만 넣는다(2026-08-11 확인).
#   케이뱅크 `Allow: /` (llms.txt까지 제공)   · 토스뱅크 `Allow: /`
#   카카오뱅크는 `*`에 /app-ads.txt만 허용    · IBK기업은행은 `Disallow: /`  → 둘 다 제외
#
# 상품 목록은 케이뱅크가 llms.txt로 직접 알려 준 것이다(추측한 URL이 아니다).
TARGETS = [
    {
        "bank": "주식회사 케이뱅크",
        "robots": "Allow: /",
        "render": False,
        "products": [
            ("코드K 자유적금", "https://www.kbanknow.com/web/product/deposit/codek-saving"),
            ("코드K 정기예금", "https://www.kbanknow.com/web/product/deposit/codek-fixed"),
            ("주거래우대 자유적금", "https://www.kbanknow.com/web/product/deposit/primary-saving"),
            ("마이키즈 적금", "https://www.kbanknow.com/web/product/deposit/mykids-saving"),
            ("데굴데굴 농장", "https://www.kbanknow.com/web/product/deposit/rolling-farm"),
            ("궁금한 적금", "https://www.kbanknow.com/web/product/deposit/curious-saving"),
            ("챌린지박스", "https://www.kbanknow.com/web/product/deposit/challengebox"),
        ],
    },
    {
        # 자바스크립트로 그리는 사이트라 정적 요청으로는 빈 껍데기만 온다 → `--render` 필요.
        # 상품 URL은 공시 페이지(`/customer/product-disclosure`)가 나열해 준 것을 그대로 썼다.
        "bank": "토스뱅크 주식회사",
        "robots": "Allow: /",
        "render": True,
        "products": [
            ("토스뱅크 굴비 적금", "https://www.tossbank.com/product-service/savings/savings-gulbi"),
            ("토스뱅크 자유 적금", "https://www.tossbank.com/product-service/savings/savings-freedom"),
            ("토스뱅크 아이 적금", "https://www.tossbank.com/product-service/savings/savings-fetus"),
            ("토스뱅크 먼저 이자 받는 정기예금",
             "https://www.tossbank.com/product-service/savings/time-deposit"),
        ],
    },
]

# 뚫으려다 막힌 곳 — **재조사하지 말 것**(2026-08-12 확인).
BLOCKED = {
    "은행연합회 소비자포털": "robots.txt `Disallow: /` — 카드다모아와 같은 판단으로 쓰지 않는다",
    "국민은행": "대문(www.kbstar.com)은 Allow지만 **상품 페이지 호스트 obank.kbstar.com 이 "
                "`User-agent: * / Disallow: /`** 다. 검색봇만 허용돼 있다",
    "카카오뱅크": "`*`에 /app-ads.txt 만 허용",
    "IBK기업은행": "robots.txt `Disallow: /`",
    "하나은행": "`Disallow: /*.do$` + `Disallow: /*?` — 상품 페이지가 대부분 그 모양이라 사실상 막힘",
    "부산은행": "robots는 Allow인데 메뉴가 전부 `javascript:goSiteMenu()`라 상품 URL을 못 얻었다. "
                "렌더링으로 메뉴를 눌러 들어가야 한다 — 미착수",
}

# 표의 한 줄. **기간 표기가 다섯 가지**다(2026-08-12 실측). 한쪽만 맞춰 두면 나머지가 조용히 빠진다.
#   A `1개월(30일) 미만 연 0.10 %`              ← 케이뱅크 코드K 자유적금
#   B `30일 미만 연 0.10 %`                     ← 케이뱅크 마이키즈 적금 (일 단위만)
#   C `중도해지금리 적용금리 : 연 0.10 %`        ← 케이뱅크 데굴데굴 농장 (구간표 없음, FLAT_RATE)
#   D `6개월 미만 연 1.8%`                      ← 토스뱅크 굴비 적금 (개월만)
#   E `1개월 초과 3개월 이하 연 0.30%`           ← 토스뱅크 정기예금 (구간 · 하한 미포함)
#
# **E의 `초과`가 결과를 바꾼다.** `3개월 초과 6개월 이하`를 `6개월 이상`으로 읽으면 12개월 상품을
# 6개월에 깼을 때 다음 구간(70%)을 집어 실제(50%)보다 많이 준다고 말하게 된다.
_PERIOD = (r"(?:(?P<x_m>\d+)\s*개월(?:\s*\(\s*[\d,]+\s*일\s*\))?|(?P<x_d>[\d,]+)\s*일)"
           r"\s*(?P<bound>미만|이상|이하|초과)"
           r"(?:\s*(?:(?:\d+)\s*개월(?:\s*\(\s*[\d,]+\s*일\s*\))?|(?:[\d,]+)\s*일)\s*(?:이하|미만))?")
# 배수 표기도 은행마다 다르다 — `기본금리 1) x 70%`(케이뱅크) vs `가입시점 기본금리 X 50%`(토스뱅크).
# **`기본 금리`처럼 띄어 쓴 페이지가 있다**(토스뱅크 자유 적금). 붙여 쓴 것만 받으면 그 상품의
# 구간 넷이 통째로 빠지는데, 남은 둘로도 그럴듯해 보여서 눈에 안 띈다 → `\s*`를 넣는다.
_RATE = (r"(?:연\s*(?P<fixed>[\d.]+)\s*%"
         r"|(?:가입시점\s*)?기본\s*금리[^xX×]{0,8}[xX×]\s*(?P<mult>\d+)\s*%"
         r"(?P<prorate>\s*[xX×]\s*경과일수\s*/\s*계약일수)?"
         r"(?:\s*\(\s*최저\s*연\s*(?P<floor>[\d.]+)\s*%\s*\))?)")
ROW = re.compile(_PERIOD + r"\s*" + _RATE)

# 표에 몇 줄이 있었는지 세는 용도. 파싱한 구간 수보다 많으면 **읽다 흘린 줄이 있다**는 뜻이다.
PERIOD_ONLY = re.compile(_PERIOD)

# 표가 시작하는 자리. `중도해지금리`라는 낱말은 본문에도 나오므로(부분인출 설명 등) 그것만 보고
# 자르면 엉뚱한 데를 집는다. 바로 뒤에 표 머리말이 오는 자리를 골라야 한다.
TABLE_HEAD = re.compile(r"중도해지\s*금리.{0,60}?(보유기간|가입기간|예치기간)")

# 구간표가 아예 없고 **한 줄로 끝나는** 상품이 있다(`데굴데굴 농장`). 표가 없다고 건너뛰면
# 실제로는 공시된 값을 못 구한 것으로 잘못 남는다.
FLAT_RATE = re.compile(r"중도해지금리\s*적용금리\s*[:：]?\s*연\s*([\d.]+)\s*%")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", errors="replace")


class Renderer:
    """자바스크립트로 그리는 사이트용. 없으면 조용히 꺼지고 해당 은행만 건너뛴다.

    Playwright를 **선택 의존성**으로 두는 이유: 이 스크립트는 배치로 가끔 돌리는 것이고,
    브라우저가 없는 곳에서도 정적 사이트(케이뱅크) 수집은 그대로 돌아가야 한다.
    """

    def __init__(self):
        self.available = False
        self._pw = self._browser = None
        try:
            from playwright.sync_api import sync_playwright
        except ImportError:
            return
        try:
            self._pw = sync_playwright().start()
            self._browser = self._pw.chromium.launch()
            self.available = True
        except Exception:                                   # noqa: BLE001 — 브라우저 미설치 등
            self.close()

    def get(self, url):
        page = self._browser.new_page(user_agent=UA)
        try:
            page.goto(url, wait_until="networkidle", timeout=45000)
            page.wait_for_timeout(2000)                     # 표가 늦게 붙는 페이지가 있다
            return page.content()
        finally:
            page.close()

    def close(self):
        for obj, meth in ((self._browser, "close"), (self._pw, "stop")):
            try:
                if obj:
                    getattr(obj, meth)()
            except Exception:                               # noqa: BLE001
                pass
        self._browser = self._pw = None
        self.available = False


def flatten(page):
    """태그·스크립트를 걷어 한 줄로. 표 구조가 은행마다 달라 텍스트로 읽는 편이 덜 깨진다."""
    text = re.sub(r"<script.*?</script>", " ", page, flags=re.S)
    text = re.sub(r"<style.*?</style>", " ", text, flags=re.S)
    text = re.sub(r"<[^>]+>", " ", text)
    return re.sub(r"\s+", " ", html.unescape(text))


def slice_table(text):
    """표 머리말이 붙은 `중도해지금리`부터 `만기 후 금리` 직전까지.

    두 표가 붙어 있어 안 자르면 **만기 후 이자율이 중도해지이율로 섞여 들어온다** — 값의 모양이
    비슷해서(`기본금리 x 50%`) 섞여도 눈에 안 띈다.
    """
    head = TABLE_HEAD.search(text)
    if not head:
        return ""
    start = head.start()
    end = text.find("만기 후 금리", start)
    return text[start:end if end > start else start + 1200]


def parse_tiers(text):
    """중도해지금리 표 → 구간 배열. 못 읽으면 빈 배열이고, 호출부가 그 상품을 건너뛴다."""
    tiers = []
    for m in ROW.finditer(text):
        if m.group("x_m") is not None:
            months = int(m.group("x_m"))
        else:
            # 일 단위로만 적힌 표기(`180일 이상`). 공시가 1개월=30일로 셈하므로 그대로 나눈다.
            months = round(int(m.group("x_d").replace(",", "")) / 30)

        bound = m.group("bound")
        if bound in ("미만", "이하"):
            # 그 아래 전부라는 뜻이라 하한 0으로 접는다.
            from_months, exclusive = 0, False
        elif bound == "초과":
            from_months, exclusive = months, True      # 하한 미포함
        else:                                          # 이상
            from_months, exclusive = months, False

        tier = {"from_months": from_months}
        if exclusive:
            tier["from_exclusive"] = True
        if m.group("fixed") is not None:
            tier["rate"] = float(m.group("fixed"))
        else:
            tier["multiplier"] = int(m.group("mult")) / 100
            if m.group("prorate"):
                tier["prorated"] = True
            if m.group("floor"):
                tier["floor_rate"] = float(m.group("floor"))
        tiers.append(tier)

    # 하한 오름차순으로 고정한다 — 판정이 "하한이 가장 큰 구간"을 고르므로 순서가 결과를 바꾼다.
    # 같은 하한이면 포함(이상)을 먼저, 미포함(초과)을 뒤에 둔다.
    tiers.sort(key=lambda t: (t["from_months"], t.get("from_exclusive", False)))
    # 같은 경계가 두 번 나오면(표가 두 벌 실린 페이지가 있다) 먼저 것만 남긴다.
    deduped, seen = [], set()
    for t in tiers:
        mark = (t["from_months"], t.get("from_exclusive", False))
        if mark in seen:
            continue
        seen.add(mark)
        deduped.append(t)
    return deduped


def collect(verbose=True, renderer=None):
    today = date.today().isoformat()
    banks = []
    for target in TARGETS:
        products, missed = [], []
        needs_render = target.get("render")
        if needs_render and not (renderer and renderer.available):
            reason = "자바스크립트로 그리는 사이트라 브라우저가 필요하다(playwright 미설치)"
            if verbose:
                print(f"  - {target['bank']} 건너뜀 — {reason}")
            missed = [{"name": n, "url": u, "reason": reason} for n, u in target["products"]]
            banks.append({"bank": target["bank"], "robots": target["robots"],
                          "as_of": today, "products": [], "missed": missed})
            continue

        for name, url in target["products"]:
            try:
                page = flatten(renderer.get(url) if needs_render else fetch(url))
            except Exception as e:                              # noqa: BLE001
                missed.append({"name": name, "url": url, "reason": f"조회 실패: {e}"})
                continue
            table = slice_table(page)
            tiers = parse_tiers(table)
            if not tiers:
                flat = FLAT_RATE.search(page)                   # 구간표 없이 한 줄로 끝나는 상품
                if flat:
                    tiers = [{"from_months": 0, "rate": float(flat.group(1))}]
            if not tiers:
                missed.append({"name": name, "url": url, "reason": "중도해지금리 표를 못 찾았다"})
                continue

            # **흘린 줄 감지.** 표에 기간 표현이 N개인데 구간을 M(<N)개만 읽었다면 표기를 놓친 것이다.
            # 이런 누락은 남은 구간만으로도 그럴듯해 보여서 눈에 안 띈다 — 세어서 드러낸다.
            rows_in_table = len(PERIOD_ONLY.findall(table))
            product = {"name": name, "url": url, "tiers": tiers}
            if rows_in_table > len(tiers):
                product["warning"] = (f"표에 기간 표현이 {rows_in_table}개인데 {len(tiers)}개만 읽었다 "
                                      "— 표기를 흘렸을 수 있다")
            products.append(product)
            if verbose:
                mark = "!" if "warning" in product else "O"
                note = f"  ← {product['warning']}" if "warning" in product else ""
                print(f"  {mark} {name:<16} 구간 {len(tiers)}개{note}")
        for m in missed:
            if verbose:
                print(f"  X {m['name']:<16} {m['reason']}")
        banks.append({
            "bank": target["bank"],
            "robots": target["robots"],
            "as_of": today,
            "products": products,
            "missed": missed,
        })
    return banks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="쓸 JSON 경로")
    ap.add_argument("--render", action="store_true",
                    help="자바스크립트로 그리는 사이트도 받는다(playwright 필요). 없으면 그 은행만 건너뛴다")
    args = ap.parse_args()

    print("중도해지이율 수집")
    renderer = Renderer() if args.render else None
    if args.render and not renderer.available:
        print("  ! playwright 를 못 써서 렌더링이 필요한 은행은 건너뛴다")
    try:
        banks = collect(renderer=renderer)
    finally:
        if renderer:
            renderer.close()
    total = sum(len(b["products"]) for b in banks)
    missed = sum(len(b["missed"]) for b in banks)

    payload = {
        "_": "중도해지이율 — 금감원에 없어 각 은행 자사 공시에서 모은 값이다(M10).",
        "_현황": {
            "수집일": date.today().isoformat(),
            "은행": [b["bank"] for b in banks],
            "상품": total,
            "실패": missed,
            "남은_일": "은행권 전체는 18곳이다. robots.txt가 막지 않은 곳부터 TARGETS에 추가한다.",
        },
        "_막힌_곳": BLOCKED,
        "banks": banks,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"\n상품 {total}건 수집 · {missed}건 실패 → {args.out}")
    return 0 if total else 1


if __name__ == "__main__":
    sys.exit(main())
