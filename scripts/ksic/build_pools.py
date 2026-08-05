"""상호 풀 재구성 — 대조표대로 원천에서 다시 뽑는다.

**왜 다시 만드는가.** 기존 `merchants_independent.json`은 상호명 키워드로 분류한 흔적이 있어
오염돼 있었다 — `카페` 풀에 롯데쇼핑·옥천보쌈·맨메이드우영미(패션 브랜드),
`치킨` 풀에 곽선생왕만두·박혜남 홍어무침. 그런데 **원본에 정답이 있었다.**
서울시 인허가 자료의 `업태구분명`은 서울시가 인허가 때 부여한 공식 업태다.
그걸 안 쓰고 이름을 파싱한 것이 오염의 원인이다.

**키가 KSIC 코드다.** 예전에는 소비맥락(category2) 이름이 키였는데, 그러면 맥락이 바뀔 때마다
풀을 다시 갈라야 한다. 업종코드를 키로 두면 맥락은 코드를 가리키기만 하면 된다.

  실행:  python3 scripts/ksic/build_pools.py
  산출:  backend-mydata/src/main/resources/generation/catalog/merchants_independent.json
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _guard import blocked  # noqa: E402
blocked('상호 풀을 4자리 KSIC 키로 쓴다', 'merchants_independent.json', 'namePoolByIndustry')

import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import sources as S  # noqa: E402
from verify import READERS, load_ksic, load_mapping  # noqa: E402

HERE = os.path.dirname(__file__)
OUT = os.path.join(HERE, '..', '..', 'backend-mydata', 'src', 'main', 'resources',
                   'generation', 'catalog', 'merchants_independent.json')

# 한 코드에 상호가 너무 많으면 JSON이 비대해진다. 생성기는 균등 추출이라
# 표본이 충분하면 더 늘려도 현실성이 오르지 않는다.
CAP = 2000


def main():
    ksic = load_ksic()
    rows = load_mapping()
    # (원천, 업태) → KSIC
    assign = {(s, l): k for s, l, k, m in rows if m != 'DROP' and k != '-'}
    wildcard = {s for s, l, k, m in rows if l == '*' and m != 'DROP'}

    pools = collections.defaultdict(list)
    dropped = collections.Counter()
    for src, read in READERS.items():
        for kind, name in read():
            code = assign.get((src, '*')) if src in wildcard else assign.get((src, kind))
            if code is None:
                dropped[(src, kind)] += 1
                continue
            pools[code].append(name)

    # 중복 제거 + 결정론 정렬 후 상한을 건다. 정렬은 재현성 전제다(마스터 §4 원칙 3).
    out = {}
    for code, names in sorted(pools.items()):
        uniq = sorted(set(names))
        out[code] = uniq[:CAP] if len(uniq) > CAP else uniq

    payload = {
        '_note': ('KSIC 세분류(4자리) → 실제 상호 풀. scripts/ksic/build_pools.py 가 '
                  'reference/ 의 공공데이터에서 생성한다. 손으로 고치지 말 것 — '
                  '분류 근거는 원천의 업태구분명이고, 대조표는 scripts/ksic/ksic-mapping.tsv 다.'),
        'namePoolByKsic': out,
    }
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)

    print(f'  코드 {len(out)}개 · 상호 {sum(len(v) for v in out.values()):,}개 → {os.path.relpath(OUT, os.getcwd())}')
    for code, names in sorted(out.items()):
        raw = len(set(pools[code]))
        cap = f'  (원본 {raw:,} 중 상한 적용)' if raw > CAP else ''
        print(f'   {code}  {ksic[code]:<34}{len(names):>6,}{cap}')
    if dropped:
        print(f'\n  버린 행 {sum(dropped.values()):,}개')
        for (src, kind), n in dropped.most_common(6):
            print(f'   [{src}] {kind or "(빈값)"}: {n:,}')


if __name__ == '__main__':
    main()
