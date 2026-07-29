"""대조표에서 런타임 리소스를 만든다 — 코드가 읽을 형태로.

TSV는 사람이 검토하기 좋고 JSON은 프로그램이 읽기 좋다. **원천은 TSV 하나**이고
여기서 파생하므로 둘이 갈라질 수 없다.

  ksic-mid.json   KSIC → 우리 중분류 (마이데이터·백엔드 양쪽)
  midmap.json     우리 중분류 → 맥락이 있는 KSIC들 (생성기의 페르소나 가중 분배)

  실행:  python3 scripts/ksic/build_resources.py
"""
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from verify import load_mapping  # noqa: E402

HERE = os.path.dirname(__file__)
ROOT = os.path.join(HERE, '..', '..')
CATALOG = os.path.join(ROOT, 'backend-mydata', 'src', 'main', 'resources', 'generation', 'catalog')
BACKEND = os.path.join(ROOT, 'backend', 'src', 'main', 'resources')
CONTEXTS = os.path.join(CATALOG, 'contexts.json')


# 이 값 미만이면 '필수 무대'로 본다. 낭비 판정의 무대 구분에 쓰이며, 낭비확률 자체는 아니다.
# (재량 ≠ 낭비 — 취미 지출이 재량이어도 본인 취미면 보호한다.)
ESSENTIAL_THRESHOLD = 0.30


def discretionary_by_mid(mid_of):
    """중분류별 재량성 = 그 중분류 맥락들의 discretionaryBase 빈도가중 평균.

    ESSENTIAL 목록이 네 곳(train.py · WasteFeatureExtractor · backend-mydata yml · WasteLabeler)에
    **손으로 복사돼** 있었다. 한 곳만 고치면 학습과 추론의 특징이 갈라지는데 아무도 모른다.
    이제 카탈로그가 이미 갖고 있는 재량성에서 유도한다 — 사람이 옮겨 적을 일이 없다.
    """
    contexts = json.load(open(CONTEXTS, encoding='utf-8'))['contexts']
    acc = collections.defaultdict(lambda: [0.0, 0.0])
    for c in contexts:
        m = mid_of[c['ksicCode']]
        w = c['frequencyWeight']
        acc[m][0] += c['discretionaryBase'] * w
        acc[m][1] += w
    return {m: round(s / w, 4) for m, (s, w) in sorted(acc.items()) if w > 0}


def main():
    rows = load_mapping()
    mid = {k: m for s, l, k, m in rows if m != 'DROP' and k != '-'}
    disc = discretionary_by_mid(mid)
    essential = sorted(m for m, d in disc.items() if d < ESSENTIAL_THRESHOLD)

    ksic_mid = {
        '_note': ('KSIC 세분류 → 우리 소비 중분류. 결정론 1:1이며 ML이 관여하지 않는다. '
                  'scripts/ksic/ksic-mapping.tsv 에서 생성한다(단일 원천). '
                  '마이데이터는 업종코드만 넘기고, 이 표로 앱이 소비 카테고리를 붙인다.'),
        'midByKsic': dict(sorted(mid.items())),
        'discretionaryByMid': disc,
        'essentialThreshold': ESSENTIAL_THRESHOLD,
        'essentialCategories': essential,
    }
    for path in (os.path.join(CATALOG, 'ksic-mid.json'), os.path.join(BACKEND, 'ksic-mid.json')):
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(ksic_mid, f, ensure_ascii=False, indent=1)
        print(f'  {os.path.relpath(path, ROOT)} — 코드 {len(mid)}개 · 필수 {len(essential)}개')

    # 맥락이 실제로 존재하는 코드만 담는다 — 없는 중분류에 페르소나 비중을 주면 그만큼 사라진다.
    live = {c['ksicCode'] for c in json.load(open(CONTEXTS, encoding='utf-8'))['contexts']}
    by = collections.defaultdict(list)
    for code in sorted(live):
        by[mid[code]].append(code)
    midmap = {
        '_note': ('우리 중분류 → 그 중분류에 속하며 **맥락이 존재하는** KSIC 목록. '
                  'scripts/ksic/build_resources.py 가 ksic-mapping.tsv + contexts.json 에서 만든다. '
                  '페르소나의 categoryMix(중분류 단위 지출비중)를 업종 단위 방문가중으로 푸는 데 쓴다.'),
        'ksicByMid': dict(by),
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
