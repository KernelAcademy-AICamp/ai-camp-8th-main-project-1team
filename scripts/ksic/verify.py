"""대조표 검증 — 생성기를 건드리기 전에 반드시 통과해야 한다.

표가 틀린 채로 11M을 재생성하면 통째로 헛돌고, 그때는 원인을 찾기도 어렵다.
그래서 사람이 눈으로 볼 표를 만들기 전에 기계가 잡을 수 있는 것부터 전부 잡는다.

  1. 모든 KSIC가 reference/업종코드.csv(502개)에 실재하는가
  2. 모든 KSIC가 우리 중분류 15개 중 하나로 가는가
  3. 원천이 실제로 갖고 있는 업태값이 표에 빠짐없이 있는가 (역방향 — 표에만 있고
     원천에 없는 줄, 원천에 있는데 표에 없는 값 둘 다)
  4. 상호가 0개인 중분류가 있는가

  실행:  python3 scripts/ksic/verify.py
"""
import collections
import csv
import io
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import sources as S  # noqa: E402

HERE = os.path.dirname(__file__)
MID15 = ['식비', '카페/간식', '편의점/잡화', '대형마트', '술/유흥', '쇼핑', '취미/여가',
         '의료', '건강/피트니스', '주거/통신', '미용', '교통/자동차', '여행/숙박',
         '생활', '카테고리없음']

# 원천 구분 → 리더. 표의 source 값과 1:1로 맞춘다.
READERS = {
    'seoul_food': lambda: S.read_csv('서울시 일반음식점 인허가 정보.csv', '사업장명', '상세영업상태명', '업태구분명'),
    'seoul_beauty': lambda: S.read_csv('서울시 미용업 인허가 정보.csv', '사업장명', '상세영업상태명', '업태구분명'),
    'seoul_gym': lambda: S.read_csv('서울시 체력단련장업 인허가 정보.csv', '사업장명', '상세영업상태명'),
    'chungnam_laundry': lambda: S.read_csv('인허가 충청남도 세탁업 정보.csv', '사업장명', '상세영업상태명', '업태구분명'),
    'gyeongbuk_stay': lambda: S.read_csv('경상북도 숙박업 현황_20240331.csv', '숙박업체명', None, '업종'),
    'gyeongbuk_bath': lambda: S.read_dbf('경상북도 목욕탕 공간정보/경상북도 목욕탕 공간정보.dbf', 'compy_nm'),
    'hira_pharmacy': lambda: S.read_xlsx('전국 병의원 및 약국 현황 2026.6/2.약국정보서비스(2026.6.).xlsx', '요양기관명', '종별코드명'),
    'hira_hospital': lambda: S.read_xlsx('전국 병의원 및 약국 현황 2026.6/1.병원정보서비스(2026.6.).xlsx', '요양기관명', '종별코드명'),
    'vet': lambda: S.read_xls('animalhospital.xls', '병원명'),
}


def load_ksic():
    """reference/업종코드.csv — KSIC 세분류 502개."""
    path = os.path.join(S.REF, '업종코드.csv')
    txt = open(path, 'rb').read().decode('utf-8-sig')
    rows = list(csv.reader(io.StringIO(txt)))[1:]
    return {r[0].strip(): r[1].strip() for r in rows if len(r) >= 2 and r[0].strip()}


def load_mapping():
    """대조표. 주석(#)과 빈 줄은 건너뛴다."""
    path = os.path.join(HERE, 'ksic-mapping.tsv')
    out = []
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) < 4:
            raise ValueError(f'열이 4개가 아니다: {line!r}')
        out.append(tuple(p.strip() for p in parts[:4]))
    return out


def main():
    ksic = load_ksic()
    rows = load_mapping()
    fail = []

    # ── 1. KSIC 실재 ──────────────────────────────────────────────────────
    missing = [(s, l, k) for s, l, k, m in rows if k != '-' and k not in ksic]
    print(f'  1. 502표 미실재 코드 ....... {len(missing)}건')
    for s, l, k in missing:
        print(f'       ✗ [{s}] {l} → {k}')
    if missing:
        fail.append('KSIC 미실재')

    # ── 2. 중분류 유효 ────────────────────────────────────────────────────
    badmid = [(s, l, m) for s, l, k, m in rows if m != 'DROP' and m not in MID15]
    print(f'  2. 중분류 오탈자 ........... {len(badmid)}건')
    for s, l, m in badmid:
        print(f'       ✗ [{s}] {l} → {m}')
    if badmid:
        fail.append('중분류 오탈자')

    # 한 KSIC가 두 중분류로 가면 1:1이 깨진다
    bycode = collections.defaultdict(set)
    for s, l, k, m in rows:
        if k != '-' and m != 'DROP':
            bycode[k].add(m)
    split = {k: v for k, v in bycode.items() if len(v) > 1}
    print(f'  2b. 한 코드가 여러 중분류로 .. {len(split)}건')
    for k, v in split.items():
        print(f'       ✗ {k} → {v}')
    if split:
        fail.append('1:1 위반')

    # ── 3. 원천 업태 커버리지 (양방향) ────────────────────────────────────
    print('  3. 원천 업태 커버리지')
    counts = collections.Counter()
    for src, read in READERS.items():
        actual = collections.Counter(kind for kind, _ in read())
        table = {l for s, l, k, m in rows if s == src}
        wildcard = '*' in table
        if wildcard:
            counts[src] = sum(actual.values())
            print(f'       ✓ {src:<18}업태 구분 없음 — 전량 사용 ({sum(actual.values()):,})')
            continue
        uncovered = {k: v for k, v in actual.items() if k not in table}
        unused = table - set(actual)
        for s, l, k, m in rows:
            if s == src and m != 'DROP':
                counts[src] += actual.get(l, 0)
        mark = '✓' if not uncovered and not unused else '✗'
        print(f'       {mark} {src:<18}표 {len(table)}종 / 원천 {len(actual)}종 · 사용 {counts[src]:,}')
        for k, v in sorted(uncovered.items(), key=lambda x: -x[1]):
            print(f'           ✗ 표에 없는 원천 업태: {k!r} ({v:,}개)')
        for k in sorted(unused):
            print(f'           ✗ 원천에 없는 표 항목: {k!r}')
        if uncovered or unused:
            fail.append(f'{src} 커버리지')

    # ── 4. 중분류별 상호 수 ───────────────────────────────────────────────
    permid = collections.Counter()
    codes_per_mid = collections.defaultdict(set)
    for src, read in READERS.items():
        actual = collections.Counter(kind for kind, _ in read())
        for s, l, k, m in rows:
            if s != src or m == 'DROP':
                continue
            n = sum(actual.values()) if l == '*' else actual.get(l, 0)
            permid[m] += n
            if n:
                codes_per_mid[m].add(k)
    for s, l, k, m in rows:
        if s == 'brand' and m != 'DROP':
            codes_per_mid[m].add(k)
    # 브랜드가 실제로도 지배적인 업종은 독립 상호가 없어도 정상이다.
    # 주유소는 정유 4사, 편의점은 5사, 이동통신은 3사가 시장을 나눠 갖는다 —
    # 여기에 '동네 독립 주유소' 상호를 억지로 만들면 오히려 현실과 멀어진다.
    BRAND_ONLY = {'대형마트', '편의점/잡화', '쇼핑', '주거/통신', '교통/자동차', '카테고리없음'}
    print('  4. 중분류별 상호')
    empty = []
    brand_names = collections.Counter()
    bj = os.path.join(S.REF, '..', 'backend-mydata', 'src', 'main', 'resources',
                      'generation', 'catalog', 'merchants_brand.json')
    import json
    pool = json.load(open(bj, encoding='utf-8'))['byCategory2']
    for s, l, k, m in rows:
        if s == 'brand' and m != 'DROP':
            brand_names[m] += len(pool.get(l, []))
    for m in MID15:
        n = permid.get(m, 0)
        b = brand_names.get(m, 0)
        cs = sorted(codes_per_mid.get(m, []))
        note = '' if n else ('   ← 브랜드 전용(정상)' if m in BRAND_ONLY else '   ← 상호 없음')
        print(f'       {m:<14}독립 {n:>8,} + 브랜드 {b:>3}  코드 {len(cs)}개  {", ".join(cs)}{note}')
        if n == 0 and b == 0:
            empty.append(m)
        elif n == 0 and m not in BRAND_ONLY:
            empty.append(m)
    if empty:
        fail.append(f'상호 없는 중분류 {empty}')

    print()
    if fail:
        print(f'  ✗ 검증 실패: {fail}')
        return 1
    print(f'  ✓ 전부 통과 — 표 {len(rows)}줄 · 상호 {sum(permid.values()):,}개')
    return 0


if __name__ == '__main__':
    sys.exit(main())
