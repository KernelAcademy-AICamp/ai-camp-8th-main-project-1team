"""카탈로그를 KSIC 4자리에서 국세청 업종코드 6자리로 옮긴다 (일회성, 멱등).

  contexts.json               ksicCode → industryCode
  merchants_independent.json  namePoolByKsic → namePoolByIndustry (4자리 풀을 파생 6자리에 복제)

**두 번 돌려도 안전하다** — 이미 옮겨져 있으면 아무것도 하지 않는다.

  실행:  python3 scripts/industry/migrate_catalog.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..')
CATALOG = os.path.join(ROOT, 'backend-mydata', 'src', 'main', 'resources', 'generation', 'catalog')
SOURCE = os.path.join(ROOT, 'reference', '업종코드-국세청-2025.csv')

# 소비맥락 → 국세청 업종코드.
#
# 4자리 시절의 **우회를 여기서 바로잡는다**: 화장품·드럭스토어에 `4712`(음ㆍ식료품 위주 종합
# 소매업)를 일부러 붙여 두었었다. KSIC 4781 이 의약품과 화장품을 한 코드에 묶어 올리브영이
# '의료'가 되는 것을 피하려던 것인데, 우리가 만드는 데이터에서만 통하는 방법이었다.
# 국세청 6자리는 523131(화장품) / 523111(의약품) 으로 가르므로 우회가 필요 없다.
CONTEXT_TO_CODE = {
    # ── 음식점 ──
    '한식': '552101', '고기구이': '552115', '횟집': '552116', '야식': '552114',
    '중식': '552102', '일식': '552103', '양식': '552104', '아시안': '552117',
    '뷔페': '552105', '분식': '552108', '휴게음식': '552123', '배달': '552119',
    '치킨': '552107', '피자': '552118', '패스트푸드': '552118',
    '카페': '552303', '베이커리': '552301', '주점': '552207',
    # ── 소매 ──
    '편의점': '521992', '대형마트': '521912', '백화점': '521910',
    # 드럭스토어(올리브영)는 화장품·의약품·생활용품 **종합**이라 521991 이 자리다.
    #   · 약국(523111)에 넣으면 '의료'의 재량성이 0.1 → 0.34 로 올라 **의료가 필수에서 빠진다**.
    #   · 화장품(523131)에 넣으면 '미용' 실현 비중이 의도의 4.2배가 된다.
    # 옮기기로 한 것은 **화장품 하나**다 — 드럭스토어는 원래대로 편의점/잡화에 둔다.
    '드럭스토어': '521991', '화장품': '523131', '약국': '523111',
    '생활잡화': '523332', '키즈육아': '523223', '의류패션': '523237', '디지털가전': '523321',
    '이커머스': '525101', '서점문구': '523511',
    '스포츠레저': '523931', '아웃도어캠핑': '523932', '취미공예': '523940',
    # ── 이동 ──
    '주유소': '505001', '대중교통': '602103', '철도': '601001', '택시': '602201',
    '고속버스': '602101', '항공': '621000', '통행료': '630311', '렌터카': '630304',
    # ── 고정지출 ──
    '통신비': '642005', '공과금': '401006', '스트리밍': '724000', '보험': '660301',
    # ── 그 밖 ──
    '간편결제': '642004',          # 무엇을 샀는지 모르는 결제 — 대조표에 없어 '카테고리없음'이 된다
    '여행숙박': '551001', '영화': '921200', '놀이공원': '921903', '공연전시': '921901',
    '키즈카페': '924309', '미용실': '930203', '헬스장': '924305', '반려동물': '852000',
}

# 옛 KSIC 4자리 → 새 국세청 6자리. **상수로 못박는다** — contexts.json 에서 유도하면
# 한 번 옮기고 난 뒤에는 대응이 사라져, 뒤이어 옮길 파일(상호 풀·취미 표)이 조용히 안 바뀐다.
# 실행 순서에 기대는 마이그레이션은 두 번째 실행에서 반쪽만 도는 법이다.
FOUR_TO_SIX = {
    '3520': ['401006'], '4711': ['521910', '521912'],
    '4712': ['521992', '523111', '523131'], '4732': ['523321'], '4741': ['523237'],
    '4759': ['523223', '523332'], '4761': ['523511'], '4763': ['523931', '523932'],
    '4764': ['523940'], '4771': ['505001'], '4781': ['523111'], '4791': ['525101'],
    '4910': ['601001'], '4921': ['602103'], '4922': ['602101'], '4923': ['602201'],
    '5110': ['621000'], '5291': ['630311'],
    '5611': ['552101', '552114', '552115', '552116'],
    '5612': ['552102', '552103', '552104', '552105', '552117'],
    '5615': ['552301'], '5616': ['552107', '552118'],
    '5619': ['552108', '552119', '552123'], '5621': ['552207'], '5622': ['552303'],
    '5914': ['921200'], '6031': ['724000'], '6122': ['642005'], '6312': ['642004'],
    '6512': ['660301'], '7310': ['852000'], '7521': ['551001'], '7611': ['630304'],
    '9011': ['921901'], '9113': ['924305'], '9121': ['921903'], '9122': ['924309'],
    '9611': ['930203'],
}

# 맥락이 아직 없어 안 쓰이던 풀도 옮겨 **보존**한다.
# 병원 상호 6천 개처럼, 지금 안 쓰여도 지울 이유가 없다.
EXTRA_POOL = {
    '5510': ['551001', '551002'],           # 일반·생활 숙박 → 호텔업 · 여관업
    '5590': ['551006'],                     # 기타 숙박 → 기타 일반 및 생활 숙박시설
    '5614': ['552308'],                     # 출장 및 이동 음식점 → 이동 음식점업
    '8610': ['851113', '851114'],           # 병원 → 종합병원 · 일반병원
    '8620': ['851201', '851211', '851212'], # 의원 → 일반의원 · 치과의원 · 한의원
    '8630': ['851901'],                     # 공중 보건 의료 → 그 외 기타 보건업
    '8690': ['851902'],                     # 기타 보건 → 유사 의료업
    '9612': ['930205', '930208'],           # 욕탕·마사지 → 피부 미용업 · 마사지업
    '9691': ['930201'],                     # 세탁업 → 이용업(주: 930100 가정용 세탁업)
}
EXTRA_POOL['9691'] = ['930100']             # 가정용 세탁업


def load_codes():
    import csv
    with open(SOURCE, encoding='utf-8') as f:
        return {r['업종코드'].strip() for r in csv.DictReader(f)}


def main():
    실재 = load_codes()
    bad = sorted({c for c in CONTEXT_TO_CODE.values() if c not in 실재} |
                 {c for v in EXTRA_POOL.values() for c in v if c not in 실재} |
                 {c for v in FOUR_TO_SIX.values() for c in v if c not in 실재})
    if bad:
        print(f'원천에 없는 코드: {bad}', file=sys.stderr)
        sys.exit(1)

    # ── contexts.json ──
    p = os.path.join(CATALOG, 'contexts.json')
    doc = json.load(io.open(p, encoding='utf-8'))
    if any('ksicCode' in c for c in doc['contexts']):
        미배정 = [c['category2'] for c in doc['contexts'] if c['category2'] not in CONTEXT_TO_CODE]
        if 미배정:
            print(f'맥락 미배정: {미배정}', file=sys.stderr)
            sys.exit(1)
        for c in doc['contexts']:
            c['industryCode'] = CONTEXT_TO_CODE[c['category2']]
            del c['ksicCode']
        doc['_note'] = doc['_note'].replace('ksicCode(KSIC 세분류 4자리)', 'industryCode(국세청 업종코드 6자리)')
        json.dump(doc, io.open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(f'  contexts.json — 맥락 {len(doc["contexts"])}개를 국세청 코드로')
    else:
        print('  contexts.json — 이미 옮겨져 있다')

    f2s = {k: list(v) for k, v in FOUR_TO_SIX.items()}
    f2s.update(EXTRA_POOL)

    # ── merchants_independent.json ──
    p = os.path.join(CATALOG, 'merchants_independent.json')
    d = json.load(io.open(p, encoding='utf-8'))
    if 'namePoolByKsic' in d:
        old, new, 미대응 = d['namePoolByKsic'], {}, []
        for k4, names in old.items():
            t = f2s.get(k4)
            if not t:
                미대응.append(k4)
                continue
            for k6 in t:
                new[k6] = names
        if 미대응:
            print(f'상호 풀 미대응(버려질 뻔): {미대응}', file=sys.stderr)
            sys.exit(1)
        json.dump({'_note': d.get('_note', '').replace('KSIC 세분류 4자리', '국세청 업종코드 6자리'),
                   'namePoolByIndustry': dict(sorted(new.items()))},
                  io.open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        총 = sum(len(v) for v in new.values())
        print(f'  merchants_independent.json — {len(old)}키 → {len(new)}키 · 상호 {총:,}개')
    else:
        print('  merchants_independent.json — 이미 옮겨져 있다')

    # ── taste/hobbies.json ── 취미 신호도 업종코드로 매겨져 있다.
    #
    # 한 4자리가 여러 6자리로 갈라지므로 **전부 넣는다** — 취미 신호는 '이 업종에서 쓰면 그 취미'
    # 라는 뜻이라, 파생된 코드 어느 쪽에서 써도 같은 신호다.
    p = os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'taste', 'hobbies.json')
    d = json.load(io.open(p, encoding='utf-8'))
    남은 = any('signatureKsic' in h for h in d.get('hobbies', []))
    if 남은:
        for h in d['hobbies']:
            out = []
            for c in h.pop('signatureKsic'):
                out.extend(f2s.get(c, [c]) if len(c) == 4 else [c])
            h['signatureIndustry'] = sorted(set(out))
        refine = {}
        for c, v in d.get('refineByMerchant', {}).items():
            if c.startswith('_'):
                refine[c] = v
                continue
            for k6 in (f2s.get(c, [c]) if len(c) == 4 else [c]):
                refine[k6] = v
        if refine:
            d['refineByMerchant'] = refine
        json.dump(d, io.open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f'  taste/hobbies.json — 취미 {len(d["hobbies"])}종의 업종코드를 6자리로')
    else:
        print('  taste/hobbies.json — 이미 옮겨져 있다')


if __name__ == '__main__':
    main()
