"""페르소나 categoryMix를 우리 중분류 15개로 재편한다.

**왜.** `categoryMix`는 "이 사람이 무엇에 얼마를 쓰는가"다 — 소비자 행동이지 업종 분류가 아니다.
그런데 지금은 옛 7대분류(식비·카페/간식·편의점·쇼핑·생활·여가·온라인)를 키로 쓴다.
그 축을 없앴으므로 페르소나도 소비 축(중분류 15개)으로 옮겨야 한다.
안 옮기면 생성기가 `pickCategory2("식비")`처럼 부르는데 이제 인자가 업종코드라 전부 폴백으로
떨어진다 — 모든 거래가 한식이 된다.

**옛 7 → 새 15 배분.** 옛 대분류가 담고 있던 것을 새 중분류로 나눠 준다. 합은 보존한다.

  실행:  python3 scripts/ksic/build_personas.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from verify import load_mapping  # noqa: E402

HERE = os.path.dirname(__file__)
PATH = os.path.join(HERE, '..', '..', 'backend-mydata', 'src', 'main', 'resources',
                    'generation', 'catalog', 'personas.json')

# 옛 대분류 → (새 중분류, 비율). 비율 합은 1.0이어야 한다.
#
# 근거:
#  · 편의점(옛)에는 편의점·드럭스토어·화장품·생활잡화가 섞여 있었다 → 편의점/잡화로 대부분,
#    대형마트는 별도 칸이 생겼으므로 일부를 넘긴다.
#  · 생활(옛)은 미용실·약국·주유소·통행료·공과금·반려동물이 뒤섞인 칸이었다 →
#    미용·의료·교통/자동차·주거/통신·생활로 흩어진다.
#  · 여가(옛)는 영화·헬스장·놀이공원·여행숙박·스포츠레저 → 취미/여가가 주력,
#    건강/피트니스와 여행/숙박이 갈려 나온다.
#  · 온라인(옛)은 이커머스·배달·스트리밍·통신비·교통 → 쇼핑·식비·취미/여가·주거/통신·교통으로.
#  · 식비(옛)에서 술/유흥을 떼어 준다. 외식형·과소비형은 술 비중이 높고 절약형은 낮다 —
#    아래 PERSONA_ALCOHOL이 그 편차를 준다.
SPLIT = {
    '식비':      [('식비', 1.0)],
    '카페/간식':  [('카페/간식', 1.0)],
    '편의점':    [('편의점/잡화', 0.75), ('대형마트', 0.25)],
    '쇼핑':      [('쇼핑', 1.0)],
    '생활':      [('미용', 0.22), ('의료', 0.20), ('교통/자동차', 0.28),
                 ('주거/통신', 0.18), ('생활', 0.12)],
    '여가':      [('취미/여가', 0.68), ('건강/피트니스', 0.18), ('여행/숙박', 0.14)],
    '온라인':    [('쇼핑', 0.34), ('식비', 0.20), ('취미/여가', 0.18),
                 ('주거/통신', 0.16), ('교통/자동차', 0.07), ('카테고리없음', 0.05)],
}

# 페르소나별 술 비중 — 식비에서 이 비율만큼 떼어 술/유흥으로 보낸다.
PERSONA_ALCOHOL = {
    '절약형': 0.04, '균형형': 0.09, '과소비형': 0.16, '구독과다형': 0.07, '외식형': 0.20,
}

MID15 = {'식비', '카페/간식', '편의점/잡화', '대형마트', '술/유흥', '쇼핑', '취미/여가',
         '의료', '건강/피트니스', '주거/통신', '미용', '교통/자동차', '여행/숙박',
         '생활', '카테고리없음'}


def main():
    # 중분류가 실제로 도달 가능한지(맥락이 있는지) 대조표로 확인한다.
    reachable = {m for s, l, k, m in load_mapping() if s in ('brand', 'context') and m != 'DROP'}
    for old, parts in SPLIT.items():
        if abs(sum(w for _, w in parts) - 1.0) > 1e-9:
            raise SystemExit(f'  ✗ {old} 배분 합이 1.0이 아니다: {sum(w for _, w in parts)}')
        for mid, _ in parts:
            if mid not in MID15:
                raise SystemExit(f'  ✗ 중분류 오탈자: {mid}')
            if mid not in reachable:
                raise SystemExit(f'  ✗ {mid}에 도달할 맥락이 없다 — contexts에 맥락을 추가하라')

    data = json.load(open(PATH, encoding='utf-8'))
    for p in data['personas']:
        name = p['name']
        old = p['categoryMix']
        unknown = set(old) - set(SPLIT)
        if unknown:
            raise SystemExit(f'  ✗ {name}: 배분표에 없는 옛 대분류 {unknown}')

        new = {}
        for oldcat, share in old.items():
            for mid, ratio in SPLIT[oldcat]:
                new[mid] = new.get(mid, 0.0) + share * ratio

        # 식비에서 술을 뗀다.
        rate = PERSONA_ALCOHOL[name]
        drink = new['식비'] * rate
        new['식비'] -= drink
        new['술/유흥'] = new.get('술/유흥', 0.0) + drink

        total = sum(new.values())
        new = {k: round(v / total * 100, 2) for k, v in sorted(new.items())}
        # 반올림 오차를 가장 큰 칸에서 흡수해 합을 정확히 100으로 만든다.
        drift = round(100.0 - sum(new.values()), 2)
        if drift:
            top = max(new, key=new.get)
            new[top] = round(new[top] + drift, 2)
        p['categoryMix'] = new
        print(f'  {name:<10} {" · ".join(f"{k} {v}" for k, v in sorted(new.items(), key=lambda x: -x[1])[:6])}')

    with open(PATH, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f'\n  personas.json 갱신 — 페르소나 {len(data["personas"])}개')


if __name__ == '__main__':
    main()
