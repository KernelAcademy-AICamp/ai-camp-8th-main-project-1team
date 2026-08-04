"""실제 사업자번호 → 우리 소비 중분류. 확정 분류 사전(`merchant_category`)의 씨앗을 만든다.

  입력:  _archive/realdatas.csv   (사업자등록번호 · 업종 · 업종코드(6자리))
  출력:  _archive/merchant-seed.tsv
  실행:  python3 scripts/industry/build_merchant_seed.py

## 왜 이게 '확정' 인가

이 목록의 업종은 **국세청 등록 정보**다. LLM 추정이 아니라 사실이라, 사전에 `USER_CSV` 출처로
바로 들어갈 수 있다. 같은 가맹점을 두 번 묻지 않게 하는 것이 사전의 임무이므로,
사람이 확인해 준 것과 같은 무게로 취급한다.

## 코드가 범위여도 중분류는 하나다

사업자번호로 업종을 조회하면 코드가 `851201~851207, 851209, 851219` 처럼 **범위·복수**로 나온다.
업종명이 여러 세세분류에 걸치기 때문이다. 그런데 우리가 필요한 것은 코드가 아니라 **중분류**이고,
한 업종명 안의 코드들은 전부 같은 중분류로 간다 — 실측으로 168곳 중 **갈리는 곳이 0곳**이었다.
그래서 코드의 모호함이 분류의 모호함으로 번지지 않는다.

## PG 는 사전에 넣지 않는다

PG 를 거친 결제는 사업자번호가 결제처를 말해 주지 않는다. 넣으면 그 PG 를 거친 모든 결제가
한 중분류로 오분류된다. `pg-사업자번호.tsv` 에 있으면 건너뛰고, 목록에 없는 PG 후보는 경고로 알린다.

**다날이 그 증거다** — PG 인데 등록 업종이 "응용 소프트웨어 개발 및 공급업"이다.
업종코드로는 PG 를 가릴 수 없다는 것이 실물로 확인된 자리다.

## 업종코드가 없는 사업자

지방자치단체(서울특별시 등)는 업종코드가 없다. 게다가 한 번호로 주차·과태료·상수도처럼
**성격이 다른 결제**가 들어온다 — 사업자번호만으로는 분류할 수 없고, 가맹점 풀네임이 갈라야 한다.
그래서 중분류를 비워 두고 사전에 넣지 않는다.
"""
import csv
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..')
SRC = os.path.join(ROOT, '_archive', 'realdatas.csv')
OUT = os.path.join(ROOT, '_archive', 'merchant-seed.tsv')
NTS = os.path.join(ROOT, 'reference', '업종코드-국세청-2025.csv')


def load_mid():
    mid = {}
    with io.open(os.path.join(HERE, 'nts-mid.tsv'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            c = line.rstrip('\n').split('\t')
            mid[c[0]] = c[1]
    return mid


def load_pg():
    pg = {}
    with io.open(os.path.join(HERE, 'pg-사업자번호.tsv'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            c = line.rstrip('\n').split('\t')
            pg[c[0]] = c[1] if len(c) > 1 else ''
    return pg


def expand(spec):
    """`851201~851207, 851209` → 코드 목록. 빈 값·'없음'은 빈 목록."""
    out = []
    for part in (spec or '').split(','):
        part = part.strip()
        if not part or part == '없음':
            continue
        m = re.fullmatch(r'(\d{6})\s*~\s*(\d{6})', part)
        if m:
            out += [f'{c:06d}' for c in range(int(m.group(1)), int(m.group(2)) + 1)]
        elif re.fullmatch(r'\d{6}', part):
            out.append(part)
        else:
            out.append(part)          # 형식이 깨진 것 — 아래에서 걸러 경고한다
    return out


# 업종명에 이것이 들어가면 PG·결제매개일 수 있다. 목록에 없으면 사람에게 묻는다.
PG_HINTS = ('금융 지원 서비스', '데이터베이스 및 온라인 정보 제공',
            '분류 안된 사업 지원 서비스', '상품권', '선불')


def main():
    if not os.path.exists(SRC):
        print(f'입력이 없다: {SRC}', file=sys.stderr)
        sys.exit(1)
    mid, pg = load_mid(), load_pg()
    with io.open(NTS, encoding='utf-8') as f:
        실재 = {r['업종코드'].strip() for r in csv.DictReader(f)}

    rows, skipped_pg, no_code, bad, 갈림, pg_후보, non_consumer = [], [], [], [], [], [], []
    with io.open(SRC, encoding='utf-8-sig') as f:
        for r in csv.DictReader(f):
            biz = re.sub(r'\D', '', r['사업자등록번호'])
            업종 = (r['업종'] or '').strip()
            codes = expand(r.get('업종코드(6자리)'))
            깨진 = [c for c in codes if c not in 실재]
            codes = [c for c in codes if c in 실재]
            if 깨진:
                bad.append((biz, 업종, 깨진))

            if biz in pg:
                skipped_pg.append((biz, pg[biz], 업종))
                continue
            if any(h in 업종 for h in PG_HINTS):
                pg_후보.append((biz, 업종))
            if not codes:
                no_code.append((biz, 업종))
                continue

            mids = {mid.get(c, '카테고리없음') for c in codes}
            mids.discard('카테고리없음')          # 범위 안에 비소비 코드가 섞인 경우
            if len(mids) > 1:
                갈림.append((biz, 업종, sorted(mids)))
                continue
            if not mids:
                # 등록 업종이 소비 중분류로 안 가는 곳(도매·제조·소프트웨어 등)이다.
                # **사전에 넣지 않는다** — 기본값이 이미 '카테고리없음'이라 넣어도 얻는 게 없고,
                # 오히려 LLM 이 가맹점명으로 고칠 기회를 막는다. 등록은 '떡류 제조업'인데
                # 실제로는 떡집에서 산 것일 수 있다(주업종만 등록되기 때문이다).
                non_consumer.append((biz, 업종))
                continue
            rows.append((biz, mids.pop(), 업종))

    rows.sort()
    with io.open(OUT, 'w', encoding='utf-8') as f:
        f.write('# 실제 사업자번호 → 우리 소비 중분류. 확정 분류 사전의 씨앗이다.\n'
                '# scripts/industry/build_merchant_seed.py 가 _archive/realdatas.csv 에서 만든다.\n'
                '# 출처는 국세청 등록 업종이라 LLM 추정이 아니라 **사실**이다.\n'
                '# PG·업종코드 없음(지자체 등)은 뺐다 — 사업자번호만으로 결제 성격을 말할 수 없다.\n'
                '#\n# 사업자번호\t중분류\t등록 업종(근거)\n')
        for biz, m, 업종 in rows:
            f.write(f'{biz}\t{m}\t{업종}\n')

    print(f'  {os.path.relpath(OUT, ROOT)} — 확정 {len(rows)}곳')
    dist = {}
    for _, m, _ in rows:
        dist[m] = dist.get(m, 0) + 1
    print('   중분류 분포: ' + ' · '.join(f'{k} {v}' for k, v in sorted(dist.items(), key=lambda x: -x[1])))
    print(f'   PG 라 제외: {len(skipped_pg)}곳 — ' + ', '.join(n for _, n, _ in skipped_pg))
    if non_consumer:
        print(f'   소비 업종이 아니라 뺌(LLM 보조 분류 대상): {len(non_consumer)}곳')
        for b, n in non_consumer[:20]:
            print(f'       {b}  {n}')
    if no_code:
        print(f'   업종코드 없음(사전에 안 넣음): {len(no_code)}곳 — ' + ', '.join(f'{b}({n})' for b, n in no_code))
    if 갈림:
        print(f'   ⚠ 중분류가 갈려 뺀 곳 {len(갈림)}: {갈림}', file=sys.stderr)
    if bad:
        print(f'   ⚠ 원천에 없는 코드 {len(bad)}: {bad[:5]}', file=sys.stderr)
    미등록 = [(b, n) for b, n in pg_후보]
    if 미등록:
        print(f'   ⚠ PG 후보인데 목록에 없다 {len(미등록)}곳 — 확인이 필요하다:', file=sys.stderr)
        for b, n in 미등록:
            print(f'       {b}  {n}', file=sys.stderr)


if __name__ == '__main__':
    main()
