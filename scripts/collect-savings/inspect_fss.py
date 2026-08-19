#!/usr/bin/env python3
"""금감원 오픈API 실측 — 매칭 규칙(M5·M6·M10)이 쓸 칸이 실제로 오는지 센다.

`07_취향분석및추천_Agent_설계.md` §4.5 「실측 — 규칙이 상품 데이터와 어긋난 세 곳」의 숫자를 만드는
스크립트다. 문서에 박힌 값(83% · 74% · 4/25 …)이 아직 맞는지 확인하려면 이걸 다시 돌린다.

    FSS_API_KEY=... python3 scripts/collect-savings/inspect_fss.py

**인증키는 어디에도 찍지 않는다.** 환경변수로만 받고, 예외 메시지에 URL이 섞여 나올 수 있으므로
출력 직전에 마스킹한다. 키를 인자로 받지 않는 것도 같은 이유다(셸 히스토리·프로세스 목록에 남는다).

**호출의 함정 셋**(`SavingsCompareService` 주석과 같은 내용):
  1. https 필수 — http는 307로 튕긴다.
  2. User-Agent 필수 — 없으면 WAF가 상태코드도 없이 연결을 끊는다.
  3. 비표준 JSON — `spcl_cnd`에 이스케이프 안 된 생 개행이 들어와 표준 파서가 통째로 거부한다.

**세는 것은 셋.**
  - 우대조건(`spcl_cnd`): 종류별 등장 빈도 · 우리가 판정할 수 있는 축의 커버리지 · 가산폭 검산
  - 가입 금액(`max_limit`·`etc_note`): M5가 읽을 상·하한이 실제로 오는가
  - 중도해지: 오지 않는다는 것의 확인(`mtrt_int`는 *만기 후* 이자율이라 다른 값이다)

조건 사전은 **키워드 매칭**이라 ±가 있다. 자릿수를 보려는 것이지 정밀 계측이 아니다 — 실제 라벨링은
LLM + 규칙 파서 폴백으로 한다(§4.5 M6 · `EligibilityLabelService`와 같은 틀).
"""

import json
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import Counter

BASE = "https://finlife.fss.or.kr/finlifeapi"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
BANK_GROUP = "020000"  # 은행. 저축은행은 030300

SERVICES = {
    "적금": "savingProductsSearch",
    "정기예금": "depositProductsSearch",
}

# 우대조건 종류 사전. 값은 정규식이고, 한 상품이 여러 종류를 가질 수 있다.
# `판정` = 우리가 마이데이터로 충족 여부를 가릴 수 있는가(§4.5 M6).
CONDITIONS = [
    ("자동이체·공과금", r"자동이체|공과금", False),
    ("첫거래·신규", r"첫\s*거래|신규|처음", False),
    ("마케팅 동의", r"마케팅|광고성|동의", False),
    ("예적금 보유·주거래", r"(예금|적금)\s*보유|주거래", False),
    ("급여이체", r"급여|월급|연금\s*이체", True),
    ("카드 실적", r"(신용|체크|카드).{0,12}(결제|이용|실적)|카드사?\s*(신용|체크)", True),
    ("비대면·앱", r"비대면|스마트폰|모바일|앱", False),
    ("금리쿠폰", r"쿠폰", False),
    ("이벤트·추첨", r"이벤트|추첨|응모", False),
]

# 우대조건이 "없다"고 적힌 것 — 파싱 실패와 다르다. 빈 집합이면 곧바로 최고금리다(M6).
NO_CONDITION = re.compile(r"^\s*(없음|해당\s*없음|-|없습니다)\s*$")

# 당행 한정 — 조건이 특정 금융사의 거래를 요구하는가(M6 ③④).
SAME_BANK = re.compile(r"당행|본\s*은행|해당\s*은행|입출(금|식)\s*계좌|주거래")

BONUS = re.compile(r"(\d+(?:\.\d+)?)\s*%\s*[pP]")
AMOUNT_WORD = re.compile(r"가입금액|가입한도|납입금액|월\s*납입|최소가입")
AMOUNT_KRW = re.compile(r"([0-9,]+)\s*(만원|원)")


def api_key():
    key = (os.environ.get("FSS_API_KEY") or "").strip()
    if not key:
        sys.exit("FSS_API_KEY 미설정 — 환경변수로 넣어 주세요(값은 출력하지 않습니다).")
    return key


def fetch(service, key, page=1, attempts=3):
    """한 페이지 조회. 실패해도 인증키가 새어 나가지 않게 예외 문구를 마스킹한다.

    적금 응답이 90KB를 넘어 첫 호출이 곧잘 시간 초과된다(캐시가 없을 때). 같은 요청을 몇 번 다시
    보내면 붙으므로 짧게 재시도한다 — 서버를 두드리는 게 아니라 느린 첫 응답을 기다리는 것이다.
    """
    query = urllib.parse.urlencode(
        {"auth": key, "topFinGrpNo": BANK_GROUP, "pageNo": page})
    req = urllib.request.Request(f"{BASE}/{service}.json?{query}", headers={"User-Agent": UA})
    last = ""
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(req, timeout=40) as resp:
                raw = resp.read().decode("utf-8")
            break
        except Exception as e:                                # noqa: BLE001 — 문구만 쓰고 다시 던진다
            last = str(e).replace(key, "***")
    else:
        raise RuntimeError(last)
    # 생 개행·제어문자를 공백으로 눌러야 json이 읽는다(비표준 응답).
    raw = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", " ", raw)
    result = json.loads(raw).get("result") or {}
    if result.get("err_cd") != "000":
        raise RuntimeError(f"err_cd={result.get('err_cd')} {result.get('err_msg')}")
    return result


def fetch_all(service, key):
    """전 페이지의 baseList·optionList를 모은다."""
    base, opts, page = [], [], 1
    while True:
        result = fetch(service, key, page)
        base.extend(result.get("baseList") or [])
        opts.extend(result.get("optionList") or [])
        if page >= int(result.get("max_page_no") or 1):
            return base, opts
        page += 1


def pct(n, total):
    return f"{n}/{total} ({round(n * 100 / total) if total else 0}%)"


def report_conditions(base):
    """우대조건 — 종류 분포 · 판정 가능한 축의 커버리지 · 가산폭 검산."""
    n = len(base)
    empty = [b for b in base if NO_CONDITION.match(b.get("spcl_cnd") or "")]
    texts = {b["fin_prdt_cd"]: (b.get("spcl_cnd") or "") for b in base}

    print(f"\n  우대조건 문구 없음/`없음` : {pct(len(empty), n)}"
          f"   ← 빈 집합이라 곧바로 최고금리(M6)")
    freq = Counter()
    for text in texts.values():
        for name, pattern, _ in CONDITIONS:
            if re.search(pattern, text):
                freq[name] += 1
    print("\n  종류별 등장 상품 수 (한 상품이 여러 개 가짐):")
    judgeable = {name for name, _, ok in CONDITIONS if ok}
    for name, count in freq.most_common():
        mark = "판정 가능" if name in judgeable else "판정 불가"
        print(f"    {name:<16} {pct(count, n):>14}   {mark}")

    covered = sum(1 for t in texts.values()
                  if any(re.search(p, t) for _, p, ok in CONDITIONS if ok))
    print(f"\n  ▸ 판정 가능한 축을 하나라도 언급 : {pct(covered, n)}")
    print(f"  ▸ 하나도 언급 안 함             : {pct(n - covered, n)}   ← M6 3분기가 필요한 이유")

    same = sum(1 for t in texts.values() if SAME_BANK.search(t))
    print(f"  ▸ 당행 한정 표현 포함           : {pct(same, n)}   ← M6 ③④")


def report_bonus_check(base, opts):
    """가산폭 합 = (최고금리 − 기본금리) 인가. M6의 파싱 신뢰도 게이트."""
    rates = {}
    for o in opts:
        key = (o["fin_co_no"], o["fin_prdt_cd"])
        # 같은 상품의 여러 기간 중 가장 긴 것 하나로 본다(가산폭은 대개 최장 기간 기준).
        prev = rates.get(key)
        if prev is None or float(o.get("save_trm") or 0) > float(prev.get("save_trm") or 0):
            rates[key] = o

    checked = matched = 0
    for b in base:
        text = b.get("spcl_cnd") or ""
        bonuses = [float(x) for x in BONUS.findall(text)]
        row = rates.get((b["fin_co_no"], b["fin_prdt_cd"]))
        if not bonuses or not row:
            continue
        try:
            gap = float(row["intr_rate2"]) - float(row["intr_rate"])
        except (TypeError, ValueError):
            continue
        checked += 1
        if abs(sum(bonuses) - gap) < 0.001:
            matched += 1

    print(f"\n  가산폭(%p)이 숫자로 적힌 상품     : {checked}건")
    print(f"  그중 합 = (최고 − 기본) 일치      : {pct(matched, checked)}"
          f"   ← 낮으면 조건별 부분 가산 금지(§8.1 D2)")


def report_amounts(base):
    """M5가 읽을 가입 금액 — max_limit(정수)과 etc_note(자연어)."""
    n = len(base)
    has_limit = [b for b in base if (b.get("max_limit") or "") not in ("", "0", "None")]
    has_note = [b for b in base
                if b.get("etc_note") and AMOUNT_WORD.search(b["etc_note"])
                and AMOUNT_KRW.search(b["etc_note"])]
    print(f"\n  max_limit 값 있음               : {pct(len(has_limit), n)}   ← 대개 상한")
    print(f"  etc_note 에 금액 문구 있음       : {pct(len(has_note), n)}   ← 하한은 여기에만 있다")
    for b in has_note[:3]:
        note = re.sub(r"\s+", " ", b["etc_note"])[:110]
        name = re.sub(r"\s+", " ", b["fin_prdt_nm"])[:16]
        print(f"      예) {name:<18} {note}")


def report_early_termination(base):
    """중도해지이율은 오지 않는다 — mtrt_int 는 *만기 후* 이자율이라 다른 값이다."""
    fields = set()
    for b in base:
        fields.update(b.keys())
    suspects = sorted(f for f in fields if "int" in f or "trm" in f)
    print(f"\n  baseList 의 금리·기간 관련 칸    : {', '.join(suspects) or '(없음)'}")
    print("  ▸ 중도해지이율 칸 없음 — M10은 은행 공시에서 따로 모은다")
    sample = next((b.get("mtrt_int") for b in base if b.get("mtrt_int")), "")
    if sample:
        print(f"      mtrt_int 표본: {re.sub(r'[ ]+', ' ', sample.replace(chr(10), ' / '))[:120]}")


def main():
    key = api_key()
    print(f"인증키 설정됨({len(key)}자)")
    for label, service in SERVICES.items():
        try:
            base, opts = fetch_all(service, key)
        except RuntimeError as e:
            print(f"\n{'='*72}\n{label} — 조회 실패: {e}")
            continue
        print(f"\n{'='*72}\n{label} ({service}) — 상품 {len(base)}건 · 금리줄 {len(opts)}건\n{'='*72}")
        report_conditions(base)
        report_bonus_check(base, opts)
        report_amounts(base)
        report_early_termination(base)


if __name__ == "__main__":
    main()
