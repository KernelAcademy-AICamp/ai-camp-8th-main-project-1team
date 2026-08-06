"""대조표에서 런타임 리소스를 만든다 — 사람이 검토한 TSV 가 원천이고 JSON 은 파생이다.

  industry-mid.json   국세청 업종코드 → 우리 소비 중분류 + PG 사업자번호 (양 모듈)
  midmap.json         우리 중분류 → 맥락이 있는 업종코드들 (생성기의 페르소나 가중 분배)

원천이 TSV 하나이므로 둘이 갈라질 수 없다. 코드에 카테고리 이름을 박지 않기 위해
데이터로 둔다(마스터 §4 원칙 4).

  실행:  python3 scripts/industry/build_industry.py
"""
import collections
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..')
CATALOG = os.path.join(ROOT, 'backend-mydata', 'src', 'main', 'resources', 'generation', 'catalog')
BACKEND = os.path.join(ROOT, 'backend', 'src', 'main', 'resources')
CONTEXTS = os.path.join(CATALOG, 'contexts.json')
SOURCE = os.path.join(ROOT, 'reference', '업종코드-국세청-2025.csv')

# 이 값 미만이면 '필수 무대'로 본다. 낭비 판정의 무대 구분에 쓰이며, 낭비확률 자체는 아니다.
# (재량 ≠ 낭비 — 취미 지출이 재량이어도 본인 취미면 보호한다.)
ESSENTIAL_THRESHOLD = 0.30


def read_tsv(name):
    """주석(#)과 빈 줄을 걷어낸 탭 구분 행들."""
    path = os.path.join(HERE, name)
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            yield n, line.split('\t')


def load_mid():
    """업종코드 → 중분류. **원천 CSV 에 실재하는 코드인지 검증한다.**

    오타 하나가 조용히 '카테고리없음'이 되어 그 업종의 소비가 통째로 사라지는 것을 막는다 —
    없는 코드를 적어 두면 아무 일도 안 일어나기 때문에 눈치채기 어렵다.
    """
    import csv
    with open(SOURCE, encoding='utf-8') as f:
        실재 = {r['업종코드'].strip(): r for r in csv.DictReader(f)}

    mid, names, bad = {}, {}, []
    for n, cols in read_tsv('nts-mid.tsv'):
        if len(cols) < 2:
            bad.append(f'{n}행: 칸이 2개 미만 — {cols}')
            continue
        code, m = cols[0].strip(), cols[1].strip()
        if code not in 실재:
            bad.append(f'{n}행: {code} 는 원천에 없는 코드다')
        if code in mid:
            bad.append(f'{n}행: {code} 가 두 번 나온다')
        mid[code] = m
        # 업종 **이름**도 모은다. LLM 보조 분류가 "이 가맹점은 어느 업종인가"를 이름으로
        # 답하게 하려면 축이 필요하다 — 6자리 숫자는 불투명해서 모델이 추론하지 못하고
        # 외운 것에 기대는데, 국세청은 구 분류 세대라 그 기억이 맞을 가능성이 낮다.
        # 이름은 원천 CSV 의 세세분류를 정본으로 쓴다(TSV 3번째 칸은 사람이 읽는 사본이다).
        if code in 실재:
            nm = 실재[code]['세세분류'].strip()
            if nm:
                prev = names.get(nm)
                if prev is not None and prev != m:
                    bad.append(f'{n}행: 세세분류 "{nm}" 이 {prev} 와 {m} 두 중분류에 걸린다')
                names[nm] = m
    if bad:
        print('대조표에 문제가 있다:', file=sys.stderr)
        for b in bad:
            print(f'  {b}', file=sys.stderr)
        sys.exit(1)
    return mid, names, 실재


def load_pg():
    """PG·간편결제 사업자번호. 하이픈 없는 10자리로 정규화해 둔다(원장이 그 형태다)."""
    pg, bad = {}, []
    for n, cols in read_tsv('pg-사업자번호.tsv'):
        num = ''.join(ch for ch in cols[0] if ch.isdigit())
        if len(num) != 10:
            bad.append(f'{n}행: 사업자번호가 10자리가 아니다 — {cols[0]}')
            continue
        pg[num] = cols[1].strip() if len(cols) > 1 else ''
    if bad:
        print('PG 목록에 문제가 있다:', file=sys.stderr)
        for b in bad:
            print(f'  {b}', file=sys.stderr)
        sys.exit(1)
    return pg


def load_multi_business():
    """한 번호에 성격이 다른 사업이 여럿 붙은 곳. 완화를 끄는 대상이다."""
    out, bad = {}, []
    for n, cols in read_tsv('복합사업자-사업자번호.tsv'):
        num = ''.join(ch for ch in cols[0] if ch.isdigit())
        if len(num) != 10:
            bad.append(f'{n}행: 사업자번호가 10자리가 아니다 — {cols[0]}')
            continue
        out[num] = cols[1].strip() if len(cols) > 1 else ''
    if bad:
        print('복합사업자 목록에 문제가 있다:', file=sys.stderr)
        for b in bad:
            print(f'  {b}', file=sys.stderr)
        sys.exit(1)
    return out


def discretionary_by_mid(mid_of):
    """중분류별 재량성 = 그 중분류 맥락들의 discretionaryBase 빈도가중 평균.

    ESSENTIAL 목록이 네 곳에 손으로 복사돼 있었고, 한 곳만 고치면 학습과 추론의 특징이
    갈라지는데 아무도 몰랐다. 카탈로그가 이미 갖고 있는 재량성에서 유도한다.
    """
    contexts = json.load(open(CONTEXTS, encoding='utf-8'))['contexts']
    acc = collections.defaultdict(lambda: [0.0, 0.0])
    for c in contexts:
        m = mid_of.get(c['industryCode'])
        if m is None:
            continue
        w = c['frequencyWeight']
        acc[m][0] += c['discretionaryBase'] * w
        acc[m][1] += w
    return {m: round(s / w, 4) for m, (s, w) in sorted(acc.items()) if w > 0}


def main():
    mid, names, 실재 = load_mid()
    pg = load_pg()
    multi = load_multi_business()
    disc = discretionary_by_mid(mid)
    essential = sorted(m for m, d in disc.items() if d < ESSENTIAL_THRESHOLD)

    out = {
        '_note': ('국세청 업종코드(6자리) → 우리 소비 중분류. 결정론 1:1이며 ML이 관여하지 않는다. '
                  'scripts/industry/nts-mid.tsv 에서 생성한다(단일 원천). '
                  '**KSIC 가 아니다** — 국세청은 구 분류 세대라 번호가 겹치지 않는다. '
                  '여기 없는 코드는 전부 카테고리없음이다.'),
        '_pgNote': ('PG·간편결제 사업자번호. 이 번호가 붙은 결제는 업종코드를 분류 근거로 쓰지 않는다 — '
                    'PG 를 거치면 "사업자가 무슨 일을 하는가"와 "이 결제가 무엇에 쓴 돈인가"가 어긋난다. '
                    'scripts/industry/pg-사업자번호.tsv 가 원천.'),
        '_multiNote': ('한 사업자번호에 **성격이 다른 사업이 여럿** 붙은 곳(백화점 입점, 배 안의 편의점). '
                       'PG 와 달리 번호는 그 사업자의 것이 맞지만, 번호로 분류하면 서로 다른 가게가 '
                       '한 분류로 오염된다. 이 번호는 **(번호, 가맹점명) 정확일치만** 쓰고 완화를 끈다. '
                       '"상호가 여럿인가"로는 못 가른다 — 택시·KTX 도 상호가 여럿이지만 같은 사업이고 '
                       '완화가 꼭 필요하다. scripts/industry/복합사업자-사업자번호.tsv 가 원천.'),
        '_nameNote': ('업종 **이름** → 중분류. LLM 보조 분류가 중분류를 직접 고르지 않고 '
                      '"이 가맹점은 어느 업종인가"를 답하게 하려고 둔다 — 그러면 축 배정은 '
                      '이 표가 하고 모델은 업종의 사실만 말한다(마스터 §4-1). '
                      '이름 하나가 두 중분류에 걸치면 빌드가 실패하므로 1:1 이 보장된다.'),
        'midByIndustry': dict(sorted(mid.items())),
        'midByIndustryName': dict(sorted(names.items())),
        'pgBusinessNumbers': dict(sorted(pg.items())),
        'multiBusinessNumbers': dict(sorted(multi.items())),
        'discretionaryByMid': disc,
        'essentialThreshold': ESSENTIAL_THRESHOLD,
        'essentialCategories': essential,
    }
    for path in (os.path.join(CATALOG, 'industry-mid.json'), os.path.join(BACKEND, 'industry-mid.json')):
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        print(f'  {os.path.relpath(path, ROOT)} — 소비 코드 {len(mid)}개 · 업종명 {len(names)}종 · PG {len(pg)}곳 · 복합 {len(multi)}곳 · 필수 {len(essential)}개')

    # 맥락이 실제로 존재하는 코드만 담는다 — 없는 중분류에 페르소나 비중을 주면 그만큼 사라진다.
    contexts = json.load(open(CONTEXTS, encoding='utf-8'))['contexts']
    live = {c['industryCode'] for c in contexts}
    # 맥락이 '카테고리없음'인 것은 정상이다 — 간편결제(642004)가 그렇다. 무엇을 샀는지 모르는
    # 결제라 중분류를 붙이면 안 되고, 페르소나의 중분류 비중을 푸는 데도 쓰이지 않는다
    # (일상 추첨에서는 여전히 나온다). midmap 에서만 뺀다.
    by = collections.defaultdict(list)
    분류없는맥락 = []
    for code in sorted(live):
        m = mid.get(code)
        if m is None:
            분류없는맥락.append(code)
            continue
        by[m].append(code)
    if 분류없는맥락:
        names = ', '.join(f"{c}({실재[c]['세세분류'][:14]})" for c in 분류없는맥락)
        print(f'  · 카테고리없음으로 두는 맥락: {names}')
    midmap = {
        '_note': ('우리 중분류 → 그 중분류에 속하며 **맥락이 존재하는** 국세청 업종코드 목록. '
                  'scripts/industry/build_industry.py 가 nts-mid.tsv + contexts.json 에서 만든다. '
                  '페르소나의 categoryMix(중분류 단위 지출비중)를 업종 단위 방문가중으로 푸는 데 쓴다.'),
        'industryByMid': dict(by),
    }
    path = os.path.join(CATALOG, 'midmap.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(midmap, f, ensure_ascii=False, indent=1)
    print(f'  {os.path.relpath(path, ROOT)} — 중분류 {len(by)}개 (맥락 있는 것만)')

    empty = [m for m in set(mid.values()) if m not in by]
    if empty:
        print(f'  ⚠ 맥락이 없어 생성에 안 나타나는 중분류: {sorted(empty)}')


if __name__ == '__main__':
    main()
