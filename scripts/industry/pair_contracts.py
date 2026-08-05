"""계약 맥락(구독·통신비·공과금)의 **상호와 요금제 짝**을 맞춘다. 멱등.

  실행:  python3 scripts/industry/pair_contracts.py

## 왜 필요한가

`resolveMerchant(cat, anchor, productName, r)` 는 `BrandEntry.serves` 로 "그 품목을 파는 사업자"만
고른다. 그런데 **`serves` 가 비어 있으면 아무 품목이나 판다**(`canSell` 이 그렇게 생겼다).
대중교통 17곳에는 `serves` 가 다 채워져 있는데 계약 셋은 **0곳**이라, 상호를 품목에 맞춰 뽑도록
호출을 고쳐도 아무 효과가 없었다. 2026-08-04 실측으로 이런 명세서가 나와 있었다.

    애플티비플러스        │ 넷플릭스 광고형
    지니뮤직              │ 스포티파이
    서울시상수도사업본부  │ 정수기렌탈
    서울도시가스          │ 전기요금

## 짝을 맞추기 전에 목록부터 어긋나 있었다

- 스트리밍 — 브랜드 18 · 품목 17 인데 1:1 이 아니다. 라프텔·벅스·플로·윌라·애플티비플러스는
  **품목이 없고**, 애플뮤직은 **파는 브랜드가 없다.** 품목을 채우고, 애플뮤직은 애플티비플러스가
  판다(둘 다 애플이 청구하며 실제 명세서도 APPLE.COM/BILL 한 줄로 찍힌다).
- 통신비 — 알뜰폰 4사가 `5G 6~7만원대`·`인터넷+TV 결합`을 팔 수 있는 상태였다. MVNO 는 그런
  요금제를 팔지 않는다. MNO/MVNO 로 가른다.
- 공과금 — `아파트관리비`·`정수기렌탈` 은 **파는 사업자가 카탈로그에 없다.** 지어 넣지 않고 뺀다.
  브랜드를 새로 만들려면 사업자번호가 필요한데, 우리가 지금 만드는 사전이 바로
  `사업자번호 → 중분류` 다. **거짓 번호를 넣으면 그 사전이 거짓이 된다.** 게다가 이 원장은
  카드사용내역이고 관리비는 대개 계좌이체라, 빼는 편이 오히려 사실에 가깝다.
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CAT = os.path.join(HERE, '..', '..', 'backend-mydata', 'src', 'main', 'resources', 'generation', 'catalog')

# ── 스트리밍: 브랜드가 있는데 품목이 없던 것들. 값은 2025~2026 한국 정가.
NEW_PRODUCTS = [
    ['라프텔 베이직', 9900, 9900, 0.60],
    ['벅스 정기결제', 8900, 8900, 0.55],
    ['플로 무제한듣기', 8900, 8900, 0.55],
    ['윌라 무제한', 9900, 9900, 0.50],      # 오디오북 — 밀리의서재(0.5)와 같은 성격
    ['애플티비플러스', 6500, 6500, 0.60],
]

# ── 상호 → 그 상호가 파는 품목의 접두사(`canSell` 이 startsWith 로 본다).
SERVES = {
    '스트리밍': {
        '넷플릭스': ['넷플릭스'], '유튜브프리미엄': ['유튜브프리미엄'],
        '디즈니플러스': ['디즈니플러스'], '티빙': ['티빙'], '웨이브': ['웨이브'],
        '왓챠': ['왓챠'], '쿠팡플레이': ['쿠팡플레이'], '라프텔': ['라프텔'],
        # 애플은 TV+ 와 뮤직을 한 청구로 받는다 — 명세서도 APPLE.COM/BILL 한 줄이다.
        '애플티비플러스': ['애플티비플러스', '애플뮤직'],
        '멜론': ['멜론'], '지니뮤직': ['지니뮤직'], '스포티파이': ['스포티파이'],
        '벅스': ['벅스'], '플로': ['플로'], '밀리의서재': ['밀리의서재'],
        '리디': ['리디'], '윌라': ['윌라'], '챗지피티플러스': ['챗지피티플러스'],
    },
    # MNO 는 5G·결합·단말할부를, MVNO 는 알뜰폰 요금제를 판다. 부가서비스는 둘 다 판다.
    '통신비': {
        'SK텔레콤': ['5G', '휴대폰 단말 할부금', '인터넷+TV 결합', '부가서비스'],
        'KT': ['5G', '휴대폰 단말 할부금', '인터넷+TV 결합', '부가서비스'],
        'LG유플러스': ['5G', '휴대폰 단말 할부금', '인터넷+TV 결합', '부가서비스'],
        'KT엠모바일': ['알뜰폰 요금제', '부가서비스'],
        'SK세븐모바일': ['알뜰폰 요금제', '부가서비스'],
        'U+유모바일': ['알뜰폰 요금제', '부가서비스'],
        '헬로모바일': ['알뜰폰 요금제', '부가서비스'],
    },
    '공과금': {
        '한국전력공사': ['전기요금'],
        '서울도시가스': ['도시가스요금'], '삼천리': ['도시가스요금'],
        '코원에너지서비스': ['도시가스요금'],
        '서울시상수도사업본부': ['수도요금'],
    },
}

DROP_PRODUCTS = {'공과금': ['아파트관리비', '정수기렌탈']}


def load(name):
    with io.open(os.path.join(CAT, name), encoding='utf-8') as f:
        return json.load(f)


def save(name, obj):
    with io.open(os.path.join(CAT, name), 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write('\n')


def main():
    prods, brands = load('products.json'), load('merchants_brand.json')
    P, B = prods['productsByCategory2'], brands['byCategory2']

    # ① 빠진 품목 채우기(멱등)
    have = {p[0] for p in P['스트리밍']}
    added = [p for p in NEW_PRODUCTS if p[0] not in have]
    P['스트리밍'].extend(added)

    # ② 팔 사람이 없는 품목 빼기(멱등)
    dropped = []
    for cat, names in DROP_PRODUCTS.items():
        keep = [p for p in P[cat] if p[0] not in names]
        dropped += [p[0] for p in P[cat] if p[0] in names]
        P[cat] = keep

    # ③ serves 채우기
    for cat, table in SERVES.items():
        for b in B[cat]:
            if b['name'] in table:
                b['serves'] = table[b['name']]

    # ④ 검증 — 짝이 실제로 맞는가. 한쪽이라도 비면 생성이 조용히 어긋난다.
    bad = []
    for cat, table in SERVES.items():
        names = [p[0] for p in P[cat]]
        for b in B[cat]:
            s = b.get('serves') or []
            if not s:
                bad.append(f'{cat}/{b["name"]}: serves 가 비었다 — 아무 품목이나 팔게 된다')
            elif not any(n.startswith(pre) for pre in s for n in names):
                bad.append(f'{cat}/{b["name"]}: 파는 품목이 하나도 없다 (serves={s})')
        for n in names:
            if not any(any(n.startswith(pre) for pre in (b.get('serves') or [])) for b in B[cat]):
                bad.append(f'{cat}/{n}: 이 품목을 파는 상호가 없다')
    if bad:
        print('  ✗ 짝이 맞지 않는다:', file=sys.stderr)
        for x in bad:
            print(f'      {x}', file=sys.stderr)
        sys.exit(1)

    save('products.json', prods)
    save('merchants_brand.json', brands)
    for cat in SERVES:
        print(f'  {cat}: 상호 {len(B[cat])}곳 · 품목 {len(P[cat])}종 — 짝 맞음')
    if added:
        print(f'   품목 추가: {[p[0] for p in added]}')
    if dropped:
        print(f'   품목 제외(파는 사업자 없음): {dropped}')


if __name__ == '__main__':
    main()
