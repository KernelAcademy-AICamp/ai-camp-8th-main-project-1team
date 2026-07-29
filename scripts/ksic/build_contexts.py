"""contexts.json 재편 — 맥락에 KSIC 코드를 붙이고 category1을 걷어낸다.

**왜.** 예전 `category1`(7대분류)이 그대로 앱의 소비 카테고리가 됐다. 한 축이 "가맹점 업종"과
"사용자 소비 종류"를 겸하다 보니 지하철이 '온라인'에 들어가는 왜곡이 났다. 이제 맥락은
업종코드만 가리키고, 소비 카테고리는 앱이 대조표로 붙인다.

**술/유흥 맥락을 신설한다.** 주점 상호 10,023개가 대응 맥락이 없어 사장돼 있었다.
빈도는 야식(0.75)·치킨(0.60)에서 떼어 온다 — 재량성이 높아 술자리와 겹치는 소비다.
전체 합을 유지해야 다른 카테고리 비중이 밀리지 않는다.

  실행:  python3 scripts/ksic/build_contexts.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from verify import load_ksic, load_mapping  # noqa: E402

HERE = os.path.dirname(__file__)
PATH = os.path.join(HERE, '..', '..', 'backend-mydata', 'src', 'main', 'resources',
                    'generation', 'catalog', 'contexts.json')

# 술/유흥에 떼어 줄 빈도. (맥락, 떼어낼 **비율**) — 합이 신설 맥락의 빈도가 된다.
# 야식·치킨은 재량성 0.75·0.60으로 술자리와 가장 많이 겹친다(야식은 술안주, 치킨은 치맥).
# 절대량이 아니라 비율로 두는 이유: 원본 빈도가 바뀌어도 맥락이 죽지 않는다.
DONORS = [('야식', 0.40), ('치킨', 0.35)]
NEW = {
    'category2': '주점',
    'channel': 'OFFLINE',
    'locationType': 'POI',
    'discretionaryBase': 0.80,      # 술자리는 식비보다 재량성이 높다
    'merchantSource': 'INDEPENDENT',
}


def main():
    ksic = load_ksic()
    rows = load_mapping()
    # 맥락 이름 → KSIC. brand 줄과 context 줄 어느 쪽에 있어도 받는다.
    code_of = {}
    for src, label, code, mid in rows:
        if src in ('brand', 'context') and mid != 'DROP' and code != '-':
            code_of.setdefault(label, code)

    data = json.load(open(PATH, encoding='utf-8'))
    contexts = data['contexts']

    missing = [c['category2'] for c in contexts if c['category2'] not in code_of]
    if missing:
        raise SystemExit(f'  ✗ 대조표에 없는 맥락: {missing}\n'
                         f'    ksic-mapping.tsv 에 줄을 추가하고 다시 실행하라.')

    by = {c['category2']: c for c in contexts}
    donated = 0.0
    for name, ratio in DONORS:
        if name not in by:
            raise SystemExit(f'  ✗ 빈도를 뗄 맥락이 없다: {name}')
        before = by[name]['frequencyWeight']
        amount = round(before * ratio, 4)
        by[name]['frequencyWeight'] = round(before - amount, 4)
        donated += amount
        print(f'   {name} 빈도 {before} → {by[name]["frequencyWeight"]}  (-{amount}, {ratio:.0%})')

    out = []
    for c in contexts:
        out.append({
            'category2': c['category2'],
            'ksicCode': code_of[c['category2']],
            'channel': c['channel'],
            'locationType': c['locationType'],
            'frequencyWeight': c['frequencyWeight'],
            'discretionaryBase': c['discretionaryBase'],
            'merchantSource': c['merchantSource'],
        })
    if NEW['category2'] not in by:
        out.append({**NEW, 'ksicCode': code_of[NEW['category2']],
                    'frequencyWeight': round(donated, 4)})
        print(f"   {NEW['category2']} 신설 — 빈도 {round(donated, 4)}")

    out.sort(key=lambda x: (x['ksicCode'], x['category2']))   # 결정론 정렬(원칙 3)
    data = {
        '_note': ('소비맥락 = 거래를 현실적으로 만드는 무대. ksicCode(KSIC 세분류 4자리)가 '
                  '업종을 가리키고, 소비 카테고리는 앱이 scripts/ksic/ksic-mapping.tsv 로 붙인다. '
                  'category1은 제거했다 — 한 축이 업종과 소비종류를 겸하던 것이 왜곡의 원인이었다. '
                  '이 파일은 scripts/ksic/build_contexts.py 가 만든다.'),
        'contexts': out,
    }
    with open(PATH, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)

    total = sum(c['frequencyWeight'] for c in out)
    codes = sorted({c['ksicCode'] for c in out})
    print(f'\n  맥락 {len(out)}개 · KSIC {len(codes)}종 · 빈도합 {total:.2f}')
    for code in codes:
        names = [c['category2'] for c in out if c['ksicCode'] == code]
        w = sum(c['frequencyWeight'] for c in out if c['ksicCode'] == code)
        print(f'   {code}  {ksic[code]:<30}{w:5.2f}  {", ".join(names)}')


if __name__ == '__main__':
    main()
