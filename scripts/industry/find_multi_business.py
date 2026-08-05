"""한 번호에 **성격이 다른 사업**이 붙은 곳 후보를 찾는다 — `복합사업자-사업자번호.tsv` 의 재료.

  실행:  .venv/bin/python scripts/industry/find_multi_business.py [명세서.xlsx]
         (기본 `_archive/cardrealdata1.xlsx`)

## 왜 자동으로 못 정하나

"한 번호에 상호가 여럿"은 **판정이 아니라 신호**다. 택시는 표시명 뒤에 차량번호가 붙어 상호가
수만 종이지만 전부 같은 사업이고, KTX 는 노선마다 다르지만 역시 하나다. 그런 곳에 완화를 끄면
사전이 영영 재사용되지 않는다.

그래서 이 도구는 **후보만 추린다.** 상호들이 같은 것을 파는지는 사람이 본다.

## 어떻게 추리나

같은 번호의 상호들에서 **공통 접두어**를 뺀 나머지가 서로 얼마나 다른지 본다.

  카카오택시-경북11바1150 / 카카오택시-경북11바1153  → 공통 접두어가 길다  → 같은 사업
  롯데백영플라자)러쉬 / 롯데백영플라자)무인양품        → 공통 접두어가 길다  → **놓친다**
  울릉크루즈 주식회사 / GS25 울룽크루즈점             → 공통 접두어가 없다  → 후보

접두어만으로는 백화점을 못 잡으므로, **괄호·구분자 뒤가 다른 것**도 함께 본다. 그래도
못 잡는 것이 남는다 — 이 도구는 사람의 눈을 돕는 것이지 대신하지 않는다.
"""
import io
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
DEFAULT = os.path.join(ROOT, '_archive', 'cardrealdata1.xlsx')


def load(path):
    if not path.lower().endswith(('.xlsx', '.xlsm')):
        sys.exit(f'xlsx 만 읽는다: {path}')
    try:
        import openpyxl
    except ImportError:
        sys.exit('openpyxl 이 필요하다:  .venv/bin/pip install openpyxl')
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    return [list(r) for r in wb[wb.sheetnames[0]].iter_rows(values_only=True)]


def known(name):
    """이미 선언된 번호들 — PG 와 복합. 다시 제안하지 않는다."""
    out = set()
    p = os.path.join(HERE, name)
    if not os.path.exists(p):
        return out
    for line in io.open(p, encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        num = ''.join(ch for ch in line.split('\t')[0] if ch.isdigit())
        if len(num) == 10:
            out.add(num)
    return out


def brand(n):
    """상호에서 **변하는 꼬리를 떼어낸 브랜드**를 남긴다.

    차량번호(`경북11바1150`)·노선(`포항-서울`)·지점(`홍대입구역점`)처럼 **같은 사업 안에서만
    달라지는 부분**을 지운다. 남은 것이 서로 다르면 그때가 진짜 다른 사업이다.
    """
    x = n
    x = re.sub(r'[가-힣]{2}\s?\d{2,3}[가-힣]\s?\d{3,4}', '', x)   # 차량번호
    x = re.sub(r'[가-힣]+\s?-\s?[가-힣]+', '', x)                    # 노선(포항-서울)
    x = re.sub(r'\d+', '', x)                                        # 남은 숫자
    x = re.sub(r'(지|역|본|분)?점$', '', x.strip())                    # 지점 표기
    x = re.sub(r'\(?주\)?|주식회사|유한회사|\(유\)|㈜', '', x)
    return re.sub(r'[\s()（）\[\]_·\-]', '', x).upper()


# 이 낱말이 상호마다 들어 있으면 브랜드가 여럿이어도 **같은 성격의 사업**일 공산이 크다.
# 판정이 아니라 **정렬 힌트**다 — 사람이 위에서부터 보게 하려는 것이지 거르려는 게 아니다.
SAME_TRADE = [
    ('교통', ('택시', '버스', '지하철', '철도', 'KTX', '무궁화', '새마을', '티머니', '카카오T')),
    ('편의점', ('GS25', 'CU', '세븐일레븐', '이마트24')),
    ('영화', ('CGV', '메가박스', '롯데시네마')),
]


def same_trade_hint(names):
    """상호 전부가 한 성격의 낱말을 갖고 있으면 그 성격을 돌려준다."""
    for label, words in SAME_TRADE:
        if all(any(w in n for w in words) for n in names):
            return label
    return None


def common_prefix(names):
    a, b = min(names), max(names)
    i = 0
    while i < len(a) and i < len(b) and a[i] == b[i]:
        i += 1
    return i


def tail_after_separator(n):
    """`롯데백영플라자)러쉬` → `러쉬`. 구분자가 없으면 원문."""
    m = re.split(r'[)\]|·\-_]', n, maxsplit=1)
    return m[-1].strip() if len(m) > 1 else n


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    rows = load(path)
    skip = known('pg-사업자번호.tsv') | known('복합사업자-사업자번호.tsv')

    by_biz = defaultdict(lambda: defaultdict(lambda: [0, 0.0]))
    for r in rows[4:]:
        if not r or not r[0] or len(r) < 7 or r[6] is None:
            continue
        try:
            amount = float(r[6])
        except (TypeError, ValueError):
            continue
        if amount <= 0:
            continue
        biz = re.sub(r'\D', '', str(r[5] or ''))
        if len(biz) != 10:
            continue
        cell = by_biz[biz][str(r[4]).strip()]
        cell[0] += 1
        cell[1] += amount

    print(f'   명세서: {os.path.relpath(path, ROOT)}')
    print(f'   이미 선언된 번호(PG·복합) {len(skip)}곳은 건너뛴다\n')

    found, cands = 0, []
    for biz, merchants in by_biz.items():
        if biz in skip or len(merchants) < 2:
            continue
        names = list(merchants)
        # **브랜드가 둘 이상이면** 후보다. 차량번호·노선·지점은 지운 뒤에 본다 —
        # 그것들은 같은 사업 안에서만 달라지므로 지우고 나면 하나로 모인다.
        brands = {b for b in (brand(n) for n in names) if b}
        if len(brands) < 2:
            continue
        # **법인격을 지운 뒤 공통 접두어가 길면 같은 브랜드의 지점·표기 변형**이다.
        #   씨제이올리브영(주)홍대입구역점 / …성수연방점  → 공통 '씨제이올리브영' 7글자
        #   주식회사 교보문고 / (주)교보문고            → 공통 '교보문고' 4글자
        #   울릉크루즈 / GS25울룽크루즈점              → 공통 0글자  ← 진짜 후보
        if common_prefix([brand(n) for n in names]) >= 4:
            continue
        hint = same_trade_hint(names)
        total = sum(v[1] for v in merchants.values())
        cands.append((hint is not None, -total, biz, merchants, brands, hint))

    # 같은 성격으로 보이는 것은 **뒤로** 보낸다. 위에서부터 보면 진짜가 먼저 온다.
    for _, _, biz, merchants, brands, hint in sorted(cands):
        found += 1
        names = list(merchants)
        total = sum(v[1] for v in merchants.values())
        if hint:
            print(f'   [{hint} 계열 — 같은 사업일 수 있다]', end=' ')
        print(f'   {biz[:3]}-{biz[3:5]}-{biz[5:]}  상호 {len(names)}종 → **브랜드 {len(brands)}종**'
              f' · {total:,.0f}원')
        for n, (c, a) in sorted(merchants.items(), key=lambda x: -x[1][1])[:5]:
            print(f'        {c:>3}건 {a:>10,.0f}원  {n[:44]}')
        print()

    if found == 0:
        print('   새 후보 없음.')
    else:
        print(f'   후보 {found}곳. **같은 것을 파는지 사람이 보고** 맞으면')
        print('   scripts/industry/복합사업자-사업자번호.tsv 에 적는다.')


if __name__ == '__main__':
    main()
