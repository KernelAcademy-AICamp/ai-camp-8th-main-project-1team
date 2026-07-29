"""취향 신호표를 업종코드 축으로 파생한다 — 백엔드가 읽을 형태로.

**왜 파생이 필요한가.** 생성기의 `hobbies.json`은 취미를 *소비맥락*(일식·백화점·스트리밍…)으로
말한다. 그 축은 제공자 DB에만 있고 앱에는 넘어오지 않는다 — 앱이 받는 것은 업종코드뿐이다.
그래서 백엔드용 표는 **업종코드 축**이어야 한다. 예전에는 생성기 파일을 그대로 복사해 두어
맥락 이름 27개가 전부 앱에서 조회 불가였고, `HobbyCatalog`가 조용히 빈 매핑으로 폴백해
**취향 분석이 언제나 빈 결과**를 냈다(크래시가 없어 아무도 몰랐다).

**어떤 업종코드를 취향 신호로 인정하는가.** 한 업종코드에는 여러 맥락이 섞여 있다
(`5611 한식 음식점업` = 한식·야식·횟집·고기구이). 그중 취미 signature가 소수면, 그 업종의
결제를 취미로 세는 순간 **일상 지출이 취향으로 오검출**된다 — 편의점에 간 사람이 전부
패션쇼핑 애호가가 된다. 그래서 그 업종 방문 중 signature가 차지하는 **빈도 비율**이
{SIGNATURE_SHARE_MIN} 이상일 때만 채택한다. 판단이 아니라 카탈로그에서 유도되는 값이다.

실측(현 카탈로그)은 채택군이 0.73 이상, 기각군이 0.46 이하로 갈려 경계에 여유가 있다:

  기각  4711(0.26) 대형마트에 백화점  ·  4712(0.46) 편의점에 화장품·드럭스토어
        4759(0.26) 생활잡화에 키즈육아 ·  5611(0.27) 한식에 고기구이
  채택  5612(0.73) 외국식 — 뷔페·아시안·양식·일식이 대부분이라 "외국식을 자주 먹는다"가
        곧 미식탐방 신호가 된다. 나머지 18개 코드는 1.00(맥락 전부가 signature).

  실행:  python3 scripts/ksic/build_taste.py
"""
import collections
import json
import os

HERE = os.path.dirname(__file__)
ROOT = os.path.join(HERE, '..', '..')
CATALOG = os.path.join(ROOT, 'backend-mydata', 'src', 'main', 'resources', 'generation', 'catalog')
OUT = os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'taste', 'hobbies.json')

# 이 값 미만이면 취향 신호로 안 쓴다. 일상 지출에 묻힌 취미 신호는 신호가 아니라 잡음이다.
SIGNATURE_SHARE_MIN = 0.60


def main():
    contexts = json.load(open(os.path.join(CATALOG, 'contexts.json'), encoding='utf-8'))['contexts']
    hobbies = json.load(open(os.path.join(CATALOG, 'hobbies.json'), encoding='utf-8'))['hobbies']
    mid = json.load(open(os.path.join(CATALOG, 'ksic-mid.json'), encoding='utf-8'))['midByKsic']

    ksic_of = {c['category2']: c['ksicCode'] for c in contexts}
    signatures = {s for h in hobbies for s in h['signatureCategories']}

    # 업종코드별 (signature 빈도, 전체 빈도)
    share = collections.defaultdict(lambda: [0.0, 0.0])
    for c in contexts:
        w = c['frequencyWeight']
        share[c['ksicCode']][1] += w
        if c['category2'] in signatures:
            share[c['ksicCode']][0] += w

    out, dropped = [], []
    for h in hobbies:
        codes = []
        for s in h['signatureCategories']:
            code = ksic_of.get(s)
            if code is None:
                dropped.append((h['type'], s, '맥락 없음'))
                continue
            sig, tot = share[code]
            r = sig / tot if tot > 0 else 0.0
            if r < SIGNATURE_SHARE_MIN:
                dropped.append((h['type'], s, f'{code} 비율 {r:.2f}'))
                continue
            if code not in codes:
                codes.append(code)
        if codes:
            out.append({'type': h['type'], 'signatureKsic': sorted(codes)})
        else:
            print(f'  ⚠ 신호가 하나도 안 남은 취미: {h["type"]}')

    doc = {
        '_note': ('취미유형 → 그 취미를 신호하는 업종코드(KSIC 세분류). '
                  'scripts/ksic/build_taste.py 가 생성기 카탈로그에서 파생한다(단일 원천). '
                  '앱은 업종코드까지만 받으므로 소비맥락(일식·백화점…)으로는 조회할 수 없다. '
                  f'그 업종 방문 중 취미 signature 비율이 {SIGNATURE_SHARE_MIN} 이상인 코드만 담는다 — '
                  '일상 지출에 묻힌 신호는 취향이 아니라 잡음이다.'),
        'signatureShareMin': SIGNATURE_SHARE_MIN,
        'hobbies': out,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)

    print(f'  {os.path.relpath(OUT, ROOT)} — 취미 {len(out)}개')
    for h in out:
        sig = ' '.join('%s(%s)' % (c, mid.get(c, '?')) for c in h['signatureKsic'])
        print(f'      {h["type"]:<10} {sig}')
    if dropped:
        print(f'  일상 지출에 묻혀 제외한 신호 {len(dropped)}개:')
        for t, s, why in dropped:
            print(f'      {t:<10} {s:<8} — {why}')


if __name__ == '__main__':
    main()
