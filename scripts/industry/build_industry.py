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
import re
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

# 카드혜택 축 — nts-mid.tsv 4번째 칸에 쓸 수 있는 값. 오타를 빌드에서 잡는다.
#
# 정본은 카드 검색 서비스가 실제로 쓰는 분류를 따랐다. 우리가 새로 지으면 카드사 공시의
# 묶음과 어긋나 매칭이 안 된다. 여기 없는 두 축은 업종코드로 판정할 수 없어 뺐다 —
#   · 배달      배달의민족이 통신판매업으로 등록돼 '쇼핑'이 된다 → 브랜드로만 풀린다
#   · 간편결제   결제수단이라 업종이 아니다 → 표시만 하고 계산에서 뺀다
#
# '혜택축없음'은 "소비가 아니다"가 아니라 "그 업종에 걸리는 카드 혜택 축이 없다"이다.
# 카드로 결제됐으면 **전월 실적에는 그대로 들어간다**(실적 제외는 카드사가 정하는 별개 목록).
CARD_AXES = {
    '주유', '온라인쇼핑', '통신', '여행/항공', '대중교통', '카페/디저트', '공과금/렌탈',
    '쇼핑', '편의점', '마트', '외식', '병원/약국', '디지털구독', '영화/문화', '뷰티',
    '택시', '백화점/면세점', '교육/육아', '스포츠/레저', '애완', '혜택축없음',
}


def read_tsv(name):
    """주석(#)과 빈 줄을 걷어낸 탭 구분 행들."""
    path = os.path.join(HERE, name)
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            yield n, line.split('\t')


def load_vague():
    """**중분류 판정에서 빼는 업종코드.** 원천은 모호업종.tsv.

    판매 방식만 말하고 무엇을 파는지 말하지 않는 업종이다 — '전자상거래 소매업' 하나에
    배달의민족·넥슨·야놀자가 함께 등록돼 있고, 온라인이라고 단정할 수도 없다(같은 업종으로
    등록한 사업자가 오프라인 매장을 함께 운영한다).

    **카드혜택 축에서는 안 뺀다.** 소비 중분류는 틀리고 카드축은 맞기 때문이다 —
    카드사도 온라인 할인을 이 업종코드로 판정한다. 그래서 load_mid 가 이 목록을
    mid·names 에서만 걷어내고 axes 에는 그대로 남긴다.
    """
    vague = {}
    for n, cols in read_tsv('모호업종.tsv'):
        if not cols or not cols[0].strip():
            continue
        vague[cols[0].strip()] = cols[1].strip() if len(cols) > 1 else ''
    return vague


def load_mid():
    """업종코드 → 중분류. **원천 CSV 에 실재하는 코드인지 검증한다.**

    오타 하나가 조용히 '카테고리없음'이 되어 그 업종의 소비가 통째로 사라지는 것을 막는다 —
    없는 코드를 적어 두면 아무 일도 안 일어나기 때문에 눈치채기 어렵다.
    """
    import csv
    with open(SOURCE, encoding='utf-8') as f:
        실재 = {r['업종코드'].strip(): r for r in csv.DictReader(f)}

    vague = load_vague()
    mid, names, axes, bad = {}, {}, {}, []
    unseen = set(vague)
    for n, cols in read_tsv('nts-mid.tsv'):
        if len(cols) < 2:
            bad.append(f'{n}행: 칸이 2개 미만 — {cols}')
            continue
        code, m = cols[0].strip(), cols[1].strip()
        if code not in 실재:
            bad.append(f'{n}행: {code} 는 원천에 없는 코드다')
        if code in mid:
            bad.append(f'{n}행: {code} 가 두 번 나온다')
        # 모호 업종은 **중분류에만** 안 담는다 — 아래 카드혜택 축에는 그대로 들어간다.
        if code in vague:
            unseen.discard(code)
        else:
            mid[code] = m
        # 4번째 칸 — 카드혜택 축. 중분류와 다른 축이라 따로 모은다(같은 교통/자동차가
        # 주유·대중교통·택시로 갈린다). 비어 있으면 빌드를 세운다 — 조용히 빠지면
        # 그 업종에 걸리는 카드 혜택이 통째로 계산되지 않는데 아무 표시도 안 난다.
        ax = cols[3].strip() if len(cols) >= 4 else ''
        if not ax:
            bad.append(f'{n}행: {code} 에 카드혜택 축이 비어 있다')
        elif ax not in CARD_AXES:
            bad.append(f'{n}행: {code} 의 카드혜택 축 "{ax}" 은 목록에 없다')
        else:
            axes[code] = ax
        # 업종 **이름**도 모은다. LLM 보조 분류가 "이 가맹점은 어느 업종인가"를 이름으로
        # 답하게 하려면 축이 필요하다 — 6자리 숫자는 불투명해서 모델이 추론하지 못하고
        # 외운 것에 기대는데, 국세청은 구 분류 세대라 그 기억이 맞을 가능성이 낮다.
        # 이름은 원천 CSV 의 세세분류를 정본으로 쓴다(TSV 3번째 칸은 사람이 읽는 사본이다).
        if code in 실재 and code not in vague:
            nm = 실재[code]['세세분류'].strip()
            if nm:
                prev = names.get(nm)
                if prev is not None and prev != m:
                    bad.append(f'{n}행: 세세분류 "{nm}" 이 {prev} 와 {m} 두 중분류에 걸린다')
                names[nm] = m
    # 모호업종.tsv 에 적었는데 nts-mid.tsv 에 없는 코드는 오타다 — 아무 일도 안 일어나
    # 눈치채기 어려우므로 빌드를 세운다.
    for code in sorted(unseen):
        bad.append(f'모호업종.tsv: {code} 가 nts-mid.tsv 에 없다')
    if bad:
        print('대조표에 문제가 있다:', file=sys.stderr)
        for b in bad:
            print(f'  {b}', file=sys.stderr)
        sys.exit(1)
    return mid, names, axes, 실재, vague


def load_sub(names, 실재):
    """**소분류** 세 표를 읽고 불변식을 검사한다 — 소분류 · 업종이름→소분류 · 브랜드→소분류.

    소분류는 중분류보다 작고 브랜드보다 큰 칸이다(카카오T=브랜드, 택시=소분류,
    교통/자동차=중분류). 두 곳에서 온다:

      · 업종 이름에서   sub-name.tsv   이미 가진 이름에서 뽑으므로 **새로 물어볼 것이 없다**
      · 브랜드에서      sub-brand.tsv  업종 이름이 답을 못 주는 자리를 메운다

    **불변식: 소분류는 정확히 한 중분류에만 속한다**(sub-mid.tsv). 그래서 소분류를 알면
    중분류가 결정되고, 같은 브랜드가 통로(업종코드·등록조회·LLM)에 따라 갈리지 않는다.
    이 함수가 그 불변식과 아래 넷을 빌드에서 잠근다.
    """
    bad = []

    # ① 소분류 → 중분류. 한 낱말이어야 하고 중분류 이름과 같으면 안 된다.
    mid_of_sub, mids = {}, set(names.values())
    for n, cols in read_tsv('sub-mid.tsv'):
        if len(cols) < 2:
            bad.append(f'sub-mid.tsv {n}행: 칸이 2개 미만 — {cols}')
            continue
        sub, m = cols[0].strip(), cols[1].strip()
        if sub in mid_of_sub:
            bad.append(f'sub-mid.tsv {n}행: 소분류 "{sub}" 이 두 번 나온다 — {mid_of_sub[sub]} / {m}')
        if re.search(r'[·/,]', sub):
            bad.append(f'sub-mid.tsv {n}행: 소분류 "{sub}" 에 둘이 섞였다 — 한 낱말로 적는다')
        if sub in mids:
            bad.append(f'sub-mid.tsv {n}행: 소분류 "{sub}" 이 중분류 이름과 같다')
        mid_of_sub[sub] = m
    for sub, m in mid_of_sub.items():
        if m not in mids:
            bad.append(f'sub-mid.tsv: "{sub}" 의 중분류 "{m}" 은 없는 중분류다')

    # ② 업종 이름 → 소분류. 모호 업종을 뺀 이름 **전부**가 소분류를 얻어야 한다.
    sub_of_name = {}
    for n, cols in read_tsv('sub-name.tsv'):
        if len(cols) < 2:
            bad.append(f'sub-name.tsv {n}행: 칸이 2개 미만 — {cols}')
            continue
        nm, sub = cols[0].strip(), cols[1].strip()
        if nm not in names:
            bad.append(f'sub-name.tsv {n}행: "{nm}" 은 중분류가 없는 업종이다')
        elif sub in mid_of_sub and mid_of_sub[sub] != names[nm]:
            bad.append(f'sub-name.tsv {n}행: "{nm}"({names[nm]}) 에 다른 중분류의 '
                       f'소분류 "{sub}"({mid_of_sub[sub]}) 를 붙였다')
        if sub not in mid_of_sub:
            bad.append(f'sub-name.tsv {n}행: 소분류 "{sub}" 이 sub-mid.tsv 에 없다')
        sub_of_name[nm] = sub
    for nm in sorted(set(names) - set(sub_of_name)):
        bad.append(f'sub-name.tsv: 업종 "{nm}" 에 소분류가 없다')

    # ③ 브랜드 → 소분류. brand-forms.json 의 브랜드 **전부**가 답을 얻거나 '-' 여야 한다.
    with open(os.path.join(BACKEND, 'brand-forms.json'), encoding='utf-8') as f:
        brands = set(json.load(f)['brandByForm'].values())
    sub_of_brand, skipped = {}, {}
    for n, cols in read_tsv('sub-brand.tsv'):
        if len(cols) < 2:
            bad.append(f'sub-brand.tsv {n}행: 칸이 2개 미만 — {cols}')
            continue
        b, sub = cols[0].strip(), cols[1].strip()
        if b not in brands:
            bad.append(f'sub-brand.tsv {n}행: "{b}" 는 brand-forms.json 에 없는 브랜드다')
        if b in sub_of_brand or b in skipped:
            bad.append(f'sub-brand.tsv {n}행: 브랜드 "{b}" 가 두 번 나온다')
        if sub == '-':
            # 회사명·결제수단처럼 **소분류가 정해지지 않는** 브랜드. 붙이지 않는 것이 답이다 —
            # 대표 업태를 찍으면 그 브랜드 전체가 한꺼번에 틀린다(카카오→멜론 72곳).
            if len(cols) < 3 or not cols[2].strip():
                bad.append(f'sub-brand.tsv {n}행: "{b}" 를 빼는 이유를 안 적었다')
            skipped[b] = cols[2].strip() if len(cols) > 2 else ''
            continue
        if sub not in mid_of_sub:
            bad.append(f'sub-brand.tsv {n}행: 소분류 "{sub}" 이 sub-mid.tsv 에 없다')
        sub_of_brand[b] = sub
    for b in sorted(brands - set(sub_of_brand) - set(skipped)):
        bad.append(f'sub-brand.tsv: 브랜드 "{b}" 에 소분류가 없다')

    # ④ **접두 충돌.** '쿠팡' 만 넣고 '쿠팡이츠' 를 빠뜨리면 배달이 온라인몰로 먹힌다 —
    #    표기를 긴 것부터 맞추므로(MerchantBrandService), 긴 형제가 표에 없으면 짧은 쪽이
    #    삼킨다. 실제로 '카카오' 가 멜론의 표기였을 때 카카오택시 72곳이 멜론이 됐다.
    for b in sorted(sub_of_brand):
        longer = [o for o in brands if o != b and o.startswith(b)]
        missing = [o for o in longer if o not in sub_of_brand and o not in skipped]
        if missing:
            bad.append(f'sub-brand.tsv: "{b}" 에 소분류를 붙였는데 더 긴 형제 {missing} 가 표에 없다')

    if bad:
        print('소분류 표에 문제가 있다:', file=sys.stderr)
        for b in bad:
            print(f'  {b}', file=sys.stderr)
        sys.exit(1)
    return mid_of_sub, sub_of_name, sub_of_brand, skipped


def fine_name_index(실재):
    """**세세분류 이름 → 국세청 업종코드들.** 바깥 조회처의 답을 우리 번호 체계로 옮기는 칸이다.

    사업자등록번호로 업종을 물어 오는 조회처는 KSIC(한국표준산업분류) 세대의 이름을 돌려주고,
    우리 대조표는 국세청 업종코드 세대다. 번호끼리는 겹치지 않아 바로 못 잇는데,
    **이름은 이어진다** — 국세청 업종코드표의 `세세분류` 칸이 KSIC 세세분류 이름을 그대로 쓴다.
    (실측 2026-08-07: 조회처가 답한 업종명 10종 중 9종이 이 칸에 그대로 있었다.)

    그래서 이름을 정규화해 색인한다. 세대가 다르면 띄어쓰기·가운뎃점만 달라지는 일이 잦다
    (`그 외 기타` ↔ `그외 기타`, `정보 제공업` ↔ `정보제공업`). 글자를 지워 맞추면 붙는다.

    코드는 **목록**이다. 한 이름에 국세청 코드가 여럿 달릴 수 있어(같은 업종을 규모로 쪼갠 것),
    중분류가 하나로 모일 때만 쓰고 갈리면 쓰지 않는다 — 그 판단은 런타임이 한다.
    여기는 `nts-mid.tsv` 에 없는 코드까지 **전부** 담는다. 이 색인의 임무는 분류가 아니라
    번호 옮기기이고, 중분류가 있는지는 다음 칸이 판단할 일이기 때문이다.
    """
    index = collections.defaultdict(list)
    for code, row in sorted(실재.items()):
        key = normalize_industry_name(row['세세분류'])
        if key:
            index[key].append(code)
    return {k: v for k, v in sorted(index.items())}


def normalize_industry_name(name):
    """이름 결합용 정규화 — **자바 쪽과 글자 하나까지 같아야 한다**(IndustryCategoryMapper).

    한쪽만 고치면 색인은 멀쩡한데 아무것도 안 붙는 조용한 실패가 난다.
    """
    return re.sub(r'[\s·‧ㆍ･·․.,()（）\[\]/\\-]', '', name or '')


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
    mid, names, axes, 실재, vague = load_mid()
    mid_of_sub, sub_of_name, sub_of_brand, skipped = load_sub(names, 실재)
    fine = fine_name_index(실재)
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
        '_fineNameNote': ('**세세분류 이름 → 국세청 업종코드들.** 바깥 조회처(사업자등록번호로 등록 업종을 '
                          '돌려주는 곳)의 답을 우리 번호 체계로 옮기는 칸이다. 조회처는 KSIC 세대의 이름을 '
                          '주고 우리는 국세청 세대라 번호끼리는 못 잇는데, 국세청 업종코드표의 `세세분류` 칸이 '
                          'KSIC 세세분류 이름을 그대로 써서 **이름은 이어진다**(2026-08-07 실측: 조회된 업종명 '
                          '19종 중 18종이 이 칸에 있었다). 키는 띄어쓰기·가운뎃점·괄호를 지운 형태이며 '
                          '자바 쪽 정규화와 글자 하나까지 같아야 한다. 중분류가 있는지는 여기서 따지지 않는다 — '
                          '이 칸의 임무는 번호 옮기기이고, 판단은 midByIndustry 가 한다.'),
        '_cardAxisNote': ('**업종코드 → 카드혜택 축.** 중분류(midByIndustry)와 다른 축이다 — 중분류는 '
                          '소비분석용이라 교통/자동차 하나에 주유·시내버스·택시가 함께 들어 있는데 카드는 '
                          '셋을 전부 다르게 취급한다. 한 파일(nts-mid.tsv)에서 두 축이 나가고, '
                          '소비분석은 중분류를 카드추천은 이 칸을 읽는다. '
                          "'혜택축없음'은 소비가 아니라는 뜻이 아니라 그 업종에 걸리는 카드 혜택 축이 "
                          '없다는 뜻이고, **전월 실적에는 그대로 들어간다.** '
                          '배달·간편결제는 업종코드로 판정할 수 없어 축에서 뺐다 — 배달은 브랜드로 풀고 '
                          '간편결제는 표시만 한다. 그래서 카드추천 판정은 브랜드가 1순위, 이 표가 2순위다.'),
        '_subNote': ('**소분류** — 중분류보다 작고 브랜드보다 큰 칸이다(카카오T=브랜드, 택시=소분류, '
                     '교통/자동차=중분류). 원장의 category3 이 이 값을 든다. '
                     '**소분류는 정확히 한 중분류에만 속한다**(midBySub) — 그래서 소분류를 알면 중분류가 '
                     '결정되고, 같은 브랜드가 통로(업종코드·등록조회·LLM)에 따라 갈리지 않는다. '
                     '이미 적힌 중분류가 midBySub 와 다르면 그 자체가 오분류의 증거다. '
                     'subByIndustryName 은 이미 가진 업종 이름에서 뽑으므로 **새로 물어볼 것이 없다**. '
                     'subByBrand 는 업종 이름이 답을 못 주는 자리를 메운다 — 배달 플랫폼은 업종을 '
                     '전자상거래로 등록해서 "배달" 이라는 업종 이름이 국세청 표에 아예 없다. '
                     '회사명(카카오·애플·구글)에는 붙이지 않는다: 여러 업태를 겸해 하나로 안 정해지고, '
                     '대표 업태를 찍으면 그 브랜드 전체가 한꺼번에 틀린다. '
                     'scripts/industry 의 sub-mid.tsv · sub-name.tsv · sub-brand.tsv 가 원천.'),
        '_vagueNote': ('**중분류 판정에서 뺀 업종**(모호업종.tsv). 판매 방식만 말하고 무엇을 파는지 '
                       '말하지 않는다 — 전자상거래 소매업 하나에 배달의민족·넥슨·야놀자가 함께 등록돼 '
                       '있고, 온라인이라고 단정할 수도 없다(오프라인 매장을 함께 운영한다). '
                       '여기 있는 코드는 midByIndustry 와 midByIndustryName 에서 빠져 LLM 목록에도 '
                       '안 나가지만, **cardAxisByIndustry 에는 그대로 남는다** — 소비 중분류는 틀리고 '
                       '카드축은 맞기 때문이다(카드사도 온라인 할인을 이 업종코드로 판정한다).'),
        'midByIndustry': dict(sorted(mid.items())),
        'cardAxisByIndustry': dict(sorted(axes.items())),
        'midByIndustryName': dict(sorted(names.items())),
        'midBySub': dict(sorted(mid_of_sub.items())),
        'subByIndustryName': dict(sorted(sub_of_name.items())),
        'subByBrand': dict(sorted(sub_of_brand.items())),
        'vagueIndustries': sorted(vague),
        'ntsByFineName': fine,
        'pgBusinessNumbers': dict(sorted(pg.items())),
        'multiBusinessNumbers': dict(sorted(multi.items())),
        'discretionaryByMid': disc,
        'essentialThreshold': ESSENTIAL_THRESHOLD,
        'essentialCategories': essential,
    }
    for path in (os.path.join(CATALOG, 'industry-mid.json'), os.path.join(BACKEND, 'industry-mid.json')):
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        print(f'  {os.path.relpath(path, ROOT)} — 소비 코드 {len(mid)}개 · 업종명 {len(names)}종 · '
              f'세세분류 색인 {len(fine)}종 · PG {len(pg)}곳 · 복합 {len(multi)}곳 · 필수 {len(essential)}개')
        print(f'      소분류 {len(mid_of_sub)}개 · 업종명에서 {len(sub_of_name)}종 · '
              f'브랜드에서 {len(sub_of_brand)}개(안 붙임 {len(skipped)}) · 모호 업종 {len(vague)}코드')

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
