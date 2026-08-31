"""김하영(27, 마케팅 2년차)의 2026-05-01 ~ 2026-09-30 소비를 동선 위에서 만든다.

  python3 scripts/build-hayoung.py

출력은 `_archive/persona/` 로 나간다(저장소 추적 밖).

## 무엇을 정하고 무엇을 굴리나

**동선은 고정**이다. 집(관악구 봉천동 1607-1)과 회사(마포구 월드컵북로 396)는 바뀌지 않고,
그 사이를 잇는 2호선-6호선 환승 경로도 바뀌지 않는다. 그래서 **돈을 쓰는 위치는 다섯 곳**뿐이다.

  집 앞      서울대입구역 일대       출근 전 · 귀가 후
  환승       합정 / 홍대입구         친구를 만나는 날만
  회사 앞    월드컵북로 390~400번대   점심 · 오후 커피 · 편의점
  나들이     주말에 가는 곳
  여행       월 1회

**굴리는 것은 그날이 어떤 날인가**다. 친구를 만나는 평일인지, 혼자 퇴근하는 평일인지,
주말에 나가는지 집에 있는지. 날마다 가게를 새로 뽑지 않는다 — 사람은 같은 길에서
같은 자리를 반복해서 쓴다.

## 9월은 줄어든다

5~8월은 같은 패턴이고, 9월 1일부터 30일까지 **낭비 항목이 선형으로 빠진다**.
빠지는 순서가 곧 절약의 순서다.

  1. 택시            늦잠 값. 제일 먼저 없앤다
  2. 오후 편의점 간식  버릇으로 사던 것
  3. 카페 2차        저녁 먹고 또 마시던 것
  4. 충동 쇼핑        화장품 · 옷
  5. 외식 단가        양식 → 가벼운 메뉴

목표는 9월 마지막 주 지출이 5~8월 평균 주의 **절반**이 되는 것이다.
"""
import collections
import csv
import io
import os
import random
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
OUT = os.path.join(ROOT, '_archive', 'persona')

START, END = date(2026, 5, 1), date(2026, 9, 30)
SEED = 20260501

# ── 동선 위의 위치 ────────────────────────────────────────────────────
HOME      = '서울시 관악구 봉천동 1607-1 (집)'
HOME_TOWN = '서울시 관악구 봉천동 (서울대입구역 일대)'
OFFICE    = '서울시 마포구 월드컵북로 396 (회사)'
OFFICE_ST = '서울시 마포구 월드컵북로 400 (회사 근처)'
OFFICE_AL = '서울시 마포구 월드컵북로 388 (회사 뒷골목)'
TRANSFER  = '서울시 마포구 양화로 (합정·홍대입구 환승)'
HONGDAE   = '서울시 마포구 와우산로 (홍대)'
SUBWAY_H  = '서울시 관악구 관악로 서울대입구역'
SUBWAY_O  = '서울시 마포구 성암로 디지털미디어시티역'


def hhmm(r, lo, hi):
    """lo~hi 분 사이의 시각 하나."""
    m = r.randint(lo, hi)
    return f'{m // 60:02d}:{m % 60:02d}'


def won(r, base, spread):
    """base 를 중심으로 흔들되 100원 단위로 떨어뜨린다."""
    return int(round(r.gauss(base, spread) / 100.0)) * 100


# ══════════════════════════════════════════════════════════════════════
#  하루를 만든다
#
#  각 함수는 (시각, 위치, 쓴 곳, 금액, 내용) 목록을 돌려준다.
#  `save` 는 9월의 절약 진행도(0.0~1.0)다. 5~8월은 항상 0.0.
# ══════════════════════════════════════════════════════════════════════

def commute_out(r, save, rows):
    """출근. 늦잠을 자면 택시, 아니면 지하철.

    택시는 **가장 먼저 끊는 항목**이다 — 늦잠 값이라 본인도 아깝다고 느끼고,
    끊어도 생활이 안 바뀐다(30분 일찍 일어나면 된다).
    """
    late = r.random() < max(0.0, 0.30 - save * 0.30)
    if late:
        rows.append((hhmm(r, 7 * 60 + 20, 8 * 60 + 20), HOME, '카카오택시',
                     won(r, 17500, 2200), '늦잠을 자서 급하게 출근'))
    else:
        rows.append((hhmm(r, 7 * 60 + 5, 7 * 60 + 45), SUBWAY_H, '지하철',
                     1550, '2호선 → 6호선 환승'))
        if r.random() < max(0.05, 0.35 - save * 0.25):
            rows.append((hhmm(r, 8 * 60 + 5, 8 * 60 + 40), OFFICE_ST, '편의점',
                         won(r, 4000, 900), '출근길 커피와 간단한 아침'))


def lunch(r, save, rows):
    """점심. 회사 근처에서 동료들과. **줄이지 않는다** — 사람 관계가 걸린 지출이다."""
    rows.append((hhmm(r, 11 * 60 + 55, 13 * 60 + 5), OFFICE_ST, '점심',
                 won(r, 11500, 1800), '회사 근처에서 동료들과 식사'))


def afternoon(r, save, rows):
    """오후 커피와 간식.

    커피는 남기고 **간식만 끊는다**. 커피는 일하는 리듬에 붙어 있는데
    편의점 간식은 졸릴 때 손이 가는 것이라 성질이 다르다.
    """
    rows.append((hhmm(r, 13 * 60, 13 * 60 + 40), OFFICE_ST, '카페',
                 won(r, 4600, 500), '점심을 먹고 커피 한 잔'))
    if r.random() < max(0.0, 0.45 - save * 0.45):
        rows.append((hhmm(r, 15 * 60 + 10, 16 * 60 + 30), OFFICE_ST, '편의점',
                     won(r, 3400, 900), '오후에 졸려서 편의점에 들름'))


def commute_home(r, rows, at='19:10'):
    rows.append((at, SUBWAY_O, '지하철', 1550, '퇴근'))


def day_solo(r, save):
    """혼자 퇴근하는 평일. 이 사람의 기본값이다."""
    rows = []
    commute_out(r, save, rows)
    lunch(r, save, rows)
    afternoon(r, save, rows)
    commute_home(r, rows, hhmm(r, 18 * 60 + 50, 20 * 60))
    # 집에 와서 저녁. 해 먹지 않는 날이 많다.
    if r.random() < 0.75:
        if r.random() < max(0.15, 0.55 - save * 0.30):
            rows.append((hhmm(r, 20 * 60, 21 * 60 + 20), HOME, '배달',
                         won(r, 16000, 3500), '집에 와서 배달 주문'))
        else:
            rows.append((hhmm(r, 19 * 60 + 40, 20 * 60 + 50), HOME_TOWN, '분식',
                         won(r, 8000, 1800), '집 앞에서 간단히 저녁'))
    return rows


def day_friend(r, save):
    """친구를 만나는 평일. 첨부한 영수증이 이 유형이다."""
    rows = []
    commute_out(r, save, rows)
    lunch(r, save, rows)
    afternoon(r, save, rows)

    # 퇴근길에 환승역에서 만난다 — 집과 회사 사이라 둘 다 편하다.
    if r.random() < max(0.05, 0.40 - save * 0.40):
        rows.append((hhmm(r, 18 * 60 + 10, 18 * 60 + 50), HONGDAE, '화장품',
                     won(r, 36000, 9000), '친구를 기다리며 들름'))
    # 저녁 단가는 9월에 서서히 내려간다 — 안 만나는 것이 아니라 덜 비싼 곳으로 간다.
    dinner = won(r, 28000 - save * 11000, 4000)
    rows.append((hhmm(r, 19 * 60 + 10, 20 * 60), HONGDAE, '저녁',
                 dinner, '친구와 만나 저녁 식사'))
    if r.random() < max(0.10, 0.70 - save * 0.60):
        rows.append((hhmm(r, 20 * 60 + 40, 21 * 60 + 40), HONGDAE, '카페',
                     won(r, 6200, 900), '저녁 식사 후 2차'))
    rows.append((hhmm(r, 22 * 60, 23 * 60), TRANSFER, '버스', 1500, '집으로 돌아가는 길'))
    # 밤에 사 먹는 군것질. 노점은 카드가 잘 안 되니 **집 앞 편의점**으로 둔다.
    if r.random() < max(0.05, 0.35 - save * 0.30):
        rows.append((hhmm(r, 22 * 60 + 30, 23 * 60 + 20), HOME_TOWN, '편의점',
                     won(r, 4200, 1200), '집에 가는 길에 편의점'))
    return rows


def midnight_snack(r, save, rows, place=HOME):
    """야식. **밤에 시키는 것이 낮에 먹는 것보다 비싸다** — 배달비가 붙고 메뉴도 커진다.

    본인도 다음 날 아침에 후회하는 지출이라, 절약을 시작하면 빨리 빠진다.
    """
    if r.random() < max(0.03, 0.30 - save * 0.27):
        rows.append((hhmm(r, 22 * 60 + 40, 23 * 60 + 50),
                     place, '야식', won(r, 19000, 4500), '자기 전에 시킨 야식'))


def day_weekend_out(r, save):
    """주말에 나가는 날. 평일보다 크게 쓴다 — 시간이 있고, 하루가 통째로 비어 있다."""
    rows = []
    where = r.choice([
        '서울시 마포구 와우산로 (홍대)',
        '서울시 성동구 연무장길 (성수)',
        '서울시 강남구 강남대로 (강남)',
        '서울시 용산구 이태원로 (이태원)',
        '서울시 종로구 삼청로 (북촌)',
    ])
    rows.append((hhmm(r, 11 * 60, 12 * 60 + 20), SUBWAY_H, '지하철', 1550, '약속 장소로 이동'))
    rows.append((hhmm(r, 12 * 60 + 20, 13 * 60 + 40), where, '브런치',
                 won(r, 21000, 4000), '늦은 아침 겸 점심'))
    rows.append((hhmm(r, 14 * 60, 15 * 60 + 30), where, '카페',
                 won(r, 7000, 1500), '자리 잡고 오래 앉아 있음'))
    # 주말 쇼핑 — 계획에 없던 소비가 가장 잘 붙는 자리다.
    if r.random() < max(0.10, 0.55 - save * 0.45):
        big = r.random() < 0.30
        rows.append((hhmm(r, 15 * 60 + 40, 17 * 60 + 30), where,
                     '미용' if r.random() < 0.45 else '쇼핑',
                     won(r, 78000 if big else 34000, 18000 if big else 9000),
                     '구경하다 산 것'))
    dinner = won(r, 30000 - save * 12000, 5000)
    rows.append((hhmm(r, 18 * 60, 19 * 60 + 40), where, '저녁', dinner, '친구들과 저녁'))
    if r.random() < max(0.10, 0.60 - save * 0.50):
        rows.append((hhmm(r, 20 * 60, 21 * 60 + 30), where, '술집',
                     won(r, 24000 - save * 8000, 6000), '저녁 먹고 한 잔'))
    rows.append((hhmm(r, 22 * 60, 23 * 60 + 40), where, '택시' if r.random() < max(0.10, 0.55 - save * 0.40) else '지하철',
                 0, ''))
    # 위 줄의 금액을 정한다 — 택시면 거리요금, 지하철이면 정액.
    t, p, kind, _, _ = rows[-1]
    rows[-1] = (t, p, '카카오택시' if kind == '택시' else '지하철',
                won(r, 16000, 3000) if kind == '택시' else 1550,
                '늦어서 택시' if kind == '택시' else '집으로')
    midnight_snack(r, save, rows)
    return rows


def day_weekend_home(r, save):
    """주말에 집에 있는 날. 적게 쓰지만 **배달과 야식이 대신 붙는다.**"""
    rows = []
    if r.random() < 0.85:
        rows.append((hhmm(r, 12 * 60, 14 * 60), HOME, '배달',
                     won(r, 18000, 4000), '집에서 점심 배달'))
    if r.random() < 0.55:
        rows.append((hhmm(r, 15 * 60, 17 * 60 + 30), HOME_TOWN, '카페',
                     won(r, 5200, 900), '동네 카페에서 잠깐'))
    if r.random() < 0.60:
        rows.append((hhmm(r, 18 * 60 + 30, 20 * 60), HOME_TOWN, '편의점',
                     won(r, 12000, 3500), '저녁거리와 군것질'))
    midnight_snack(r, save, rows)
    return rows


def occasional(r, save, d, rows):
    """가끔 있는 큰 지출. 날마다 있는 것이 아니라 **한 달에 몇 번** 튄다."""
    # 미용실 — **두 달에 한 번**이다. 확률로 두었더니 이틀 연속 굴러 그 주가 통째로 튀었다.
    # 머리는 그렇게 자르지 않는다.
    if (d - START).days % 58 == 21:
        rows.append((hhmm(r, 11 * 60, 18 * 60), HONGDAE, '미용실',
                     won(r, 88000 if (d.month % 2 == 1) else 44000, 9000), '커트와 염색'))
    # 옷 — 계절이 바뀔 때 몰린다
    if r.random() < max(0.02, 0.09 - save * 0.07):
        rows.append((hhmm(r, 14 * 60, 21 * 60), '온라인', '의류',
                     won(r, 74000, 26000), '온라인으로 산 옷'))
    # 충동적으로 담는 것들 — 새벽에 결제가 많다
    if r.random() < max(0.02, 0.12 - save * 0.10):
        rows.append((hhmm(r, 22 * 60 + 30, 23 * 60 + 55), '온라인', '온라인쇼핑',
                     won(r, 31000, 12000), '자기 전에 결제'))
    # 병원·약국
    if r.random() < 0.02:
        rows.append((hhmm(r, 9 * 60, 19 * 60), HOME_TOWN, '병원',
                     won(r, 14000, 5000), '병원과 약국'))


def fixed_for(d, save):
    """그날 나가는 고정비.

    **하루 목록에 함께 넣는다.** 처음에는 마지막에 따로 붙였는데, 그러면 `fit_day` 가 못 보고
    헬스장 회비(89,000)가 붙은 날이 그대로 178,750원이 됐다 — 그날 하루만 평소의 두 배다.
    실제 사람은 큰 돈이 나간 날에 다른 것을 줄인다. 함께 넣어야 그 조절이 일어난다.
    """
    out = []
    if d.day == 3:
        out.append(('07:00', HOME_TOWN, '운동', 89000, '헬스장 월 회비'))
    if d.day == 14:
        out.append(('09:00', '자동결제', '구독', 13500, 'OTT 월 구독'))
    if d.day == 7 and not (d.month == 9 and save > 0.25):
        out.append(('09:00', '자동결제', '구독', 14900, '음악·영상 구독'))
    if d.day == 25:
        out.append(('09:00', '자동이체', '통신비', 55000, '휴대폰 요금'))
    return out


def trip(r, save, d):
    """여름 호캉스 — 토요일에 들어가 일요일에 나온다.

    **멀리 가지 않는다.** 처음에는 강릉·부산으로 보냈는데, 그러면 그 도시의 가맹점을
    따로 다 찾아야 한다. 서울 안에서 묵으면 이미 아는 상권(이태원)이 그대로 쓰인다 —
    실제로 이 나이대가 가장 자주 하는 여행이기도 하다.

    **줄이지 않는다.** 이 사람이 줄이려는 것은 '새는 돈'이지 '가고 싶었던 곳'이 아니다.
    다만 9월에는 아예 가지 않는다 — 목돈을 모으기 시작하면 여행이 제일 먼저 미뤄진다.
    """
    HOTEL = '서울시 용산구 소월로 (그랜드 하얏트 서울)'
    ITW   = '서울시 용산구 이태원로 (이태원)'
    out, back = [], []
    out.append((hhmm(r, 12 * 60, 13 * 60 + 30), ITW, '점심', won(r, 21000, 4000), '체크인 전 점심'))
    out.append((hhmm(r, 15 * 60, 16 * 60), HOTEL, '숙박', won(r, 165000, 18000), '1박'))
    out.append((hhmm(r, 17 * 60, 18 * 60 + 30), HOTEL, '카페', won(r, 14000, 3000), '수영장 옆에서'))
    out.append((hhmm(r, 19 * 60, 20 * 60 + 30), ITW, '저녁', won(r, 46000, 8000), '호텔 나와서 저녁'))
    out.append((hhmm(r, 21 * 60, 22 * 60 + 30), ITW, '술집', won(r, 32000, 7000), '한 잔 더'))
    back.append((hhmm(r, 10 * 60, 11 * 60 + 30), HOTEL, '카페', won(r, 12000, 2500), '체크아웃하고 커피'))
    back.append((hhmm(r, 12 * 60 + 30, 14 * 60), ITW, '점심', won(r, 24000, 5000), '돌아가기 전 점심'))
    back.append((hhmm(r, 15 * 60, 16 * 60 + 30), ITW, '카카오택시', won(r, 15000, 2500), '집으로'))
    return out, back


# ══════════════════════════════════════════════════════════════════════
#  하루 총액을 사람이 사는 범위로 맞춘다
#
#  하루 5~15만원이 보통이고 가끔 그 밖으로 나간다. 항목별 확률만 흔들면
#  어떤 날은 8천 원, 어떤 날은 40만 원이 되어 **사람의 하루처럼 안 보인다.**
#  그래서 만든 뒤에 **범위 안으로 당긴다** — 모자라면 그 자리에서 있을 법한 것을
#  더 붙이고, 넘치면 제일 나중에 붙은 군더더기를 뗀다.
# ══════════════════════════════════════════════════════════════════════

# 하루가 놓일 자리를 **유형마다 정해 둔다.**
#
# 처음에는 넓은 밴드(5~15만) 안에만 들어오게 했다. 그랬더니 같은 생활인 5~8월이
# 78.6 / 88.7 / 80.5 / 89.6 만원으로 **14% 씩 갈렸다** — 큰 날이 어떤 달엔 몰리고
# 어떤 달엔 안 몰렸기 때문이다. 사람은 매달 비슷하게 쓴다. 그래서 밴드가 아니라
# **그날 유형의 목표액**에 맞춘다. 달 평균이 흔들릴 자리가 없어진다.
#
# 첨부된 영수증(친구 만난 평일 115,500원)이 아래 `친구` 값의 근거다.
TARGET = {
    'solo':    62000,        # 혼자 퇴근하는 평일
    'friend': 112000,        # 친구를 만나는 평일 — 영수증이 이 유형
    'home':    68000,        # 주말에 집
    'out':    125000,        # 주말에 나가는 날
}
BAND = 0.16                  # 목표의 ±16% 안. 그보다 딱 맞으면 그것도 거짓이다.

# 모자랄 때 붙일 수 있는 것들 — 전부 그 동네에서 실제로 살 만한 것이다.
FILLERS = [
    (HOME_TOWN, '편의점',  9000,  2500, '집 앞 편의점에서 장보기'),
    (HOME_TOWN, '카페',    5200,  900,  '동네 카페'),
    (OFFICE_ST, '카페',    4800,  700,  '오후에 한 잔 더'),
    (HOME_TOWN, '베이커리', 7400,  1800, '빵집에서 내일 아침거리'),
    (HOME_TOWN, '생활용품', 13000, 4000, '생필품'),
    (HOME_TOWN, '분식',    8500,  2000, '집 앞에서 간단히'),
    (HOME_TOWN, '베이커리', 6200,  1500, '빵집에서 군것질'),
    (OFFICE_ST, '편의점',  4200,  1200, '회사 앞 편의점'),
    (HONGDAE,   '디저트',  9500,  2500, '디저트'),
    (HOME_TOWN, '주류',    11000, 3000, '집에서 마실 것'),
]

# 넘칠 때 먼저 떼는 순서 — **낭비부터 뗀다.** 저녁이나 점심을 떼면 하루가 이상해진다.
# 넘칠 때 먼저 떼는 순서 — **낭비부터, 그다음 채워 넣은 것.** 저녁·점심·교통은 안 뗀다.
#
# `FILLERS` 로 넣은 것이 여기 없으면 **넣을 수는 있는데 뺄 수가 없다.** 9월 말 하루가
# 62,950원에서 안 내려간 것이 그래서였다 — 생활용품 16,800원을 못 떼고 멈췄다.
DROP_FIRST = ['온라인쇼핑', '의류', '야식', '술집', '미용',
              '디저트', '주류', '생활용품', '베이커리', '분식', '카페', '편의점']


def total(rows):
    return sum(x[3] for x in rows)


def fit_day(r, rows, kind, save=0.0):
    """그날 유형의 목표액에 맞춘다. 9월에는 목표 자체가 내려간다.

    **하한을 절약과 함께 내리는 것이 핵심이다.** 처음에는 9월에도 같은 하한을 채우게 두었더니
    낭비를 뺀 자리를 이 함수가 도로 메워, 9월 마지막 주가 76%에서 안 내려갔다 —
    절약을 만들어 놓고 절약을 되밀고 있었다.
    """
    target = TARGET[kind] * (1 - 0.50 * save)
    lo, hi = target * (1 - BAND), target * (1 + BAND)
    if r.random() < 0.05:                      # 스무 날에 하루쯤은 그냥 둔다
        return sorted(rows, key=lambda x: x[0])
    guard = 0
    while total(rows) < lo and guard < 12:
        place, k, base, spread, note = r.choice(FILLERS)
        rows.append((hhmm(r, 8 * 60, 23 * 60), place, k, won(r, base, spread), note))
        guard += 1
    guard = 0
    while total(rows) > hi and len(rows) > 3 and guard < 12:
        order = {k: i for i, k in enumerate(DROP_FIRST)}
        drop = min(range(len(rows)), key=lambda i: (order.get(rows[i][2], 99), -rows[i][3]))
        if order.get(rows[drop][2], 99) == 99:
            break                              # 뗄 낭비가 없으면 멈춘다
        rows.pop(drop)
        guard += 1
    return sorted(rows, key=lambda x: x[0])


# ══════════════════════════════════════════════════════════════════════
#  5월 1일부터 9월 30일까지 이어 붙인다
# ══════════════════════════════════════════════════════════════════════

def save_level(d):
    """9월의 절약 진행도. 5~8월은 0, 9월 1일 0.12 → 9월 30일 1.0.

    **0 에서 시작하지 않는다.** 곧게 0부터 올렸더니 9월 첫 주가 8월과 똑같이(114%) 나왔다 —
    마음먹은 날부터 이미 조금은 달라야 절약을 시작한 사람이다. 첫날에 택시를 한 번 참고
    야식을 한 번 건너뛰는 정도가 0.12 다. 나머지는 한 달에 걸쳐 몸에 붙는다.
    """
    if d < date(2026, 9, 1):
        return 0.0
    return min(1.0, 0.12 + 0.88 * (d - date(2026, 9, 1)).days / 29.0)


def build():
    r = random.Random(SEED)
    rows = []                                    # (날짜, 시각, 위치, 쓴 곳, 금액, 내용)
    trip_back = None                             # 여행 다음 날 붙일 것
    trip_months = set()

    d = START
    while d <= END:
        save = save_level(d)
        day = []

        kind = None
        if trip_back is not None:
            day = trip_back
            trip_back = None
        elif d.weekday() >= 5:                                    # 주말
            # 여행은 그 달에 한 번, 토요일에만 떠난다. 9월은 한 번 건너뛴다.
            # 여행은 **여름에 한 번**이다. 달마다 한 번씩 두었더니 5~8월 평균이
            # 여행 유무로 갈렸다 — 같은 생활인데 달 평균이 흔들리면 안 된다.
            go = (d.weekday() == 5 and d.month == 8 and 8 <= d.day <= 21
                  and not trip_months)
            if go:
                trip_months.add(d.month)
                day, trip_back = trip(r, save, d)
            else:
                out = d.weekday() == 5                      # 토요일에 나간다
                if r.random() < 0.22:                       # 가끔 뒤바뀐다
                    out = not out
                if save > 0 and r.random() < save * 0.45:   # 9월엔 나가는 날이 준다
                    out = False
                day, kind = ((day_weekend_out(r, save), 'out') if out
                             else (day_weekend_home(r, save), 'home'))
        else:                                                     # 평일
            # 친구는 주로 목·금에 만난다. 9월에는 횟수가 아니라 단가가 줄어든다.
            # **한 주의 모양을 고정한다.** 날마다 확률을 굴렸더니 어떤 달은 친구를
            # 아홉 번 만나고 어떤 달은 다섯 번 만나, 그것만으로 달 평균이 12% 갈렸다.
            # 사람의 한 주는 무작위가 아니다 — 목요일에 만나고, 금요일은 격주다.
            wk = d.isocalendar()[1]
            meet = d.weekday() == 3 or (d.weekday() == 4 and wk % 2 == 0)
            if r.random() < 0.20:                # 다섯 번에 한 번은 어긋난다
                meet = not meet
            if meet:
                day, kind = day_friend(r, save), 'friend'
            else:
                day, kind = day_solo(r, save), 'solo'
            midnight_snack(r, save, day)

        day.extend(fixed_for(d, save))
        # **여행 중에는 큰 지출을 겹치지 않는다.** 호캉스(20만)에 온라인 옷(11만)이 겹쳐
        # 하루 415,800원이 나온 적이 있다 — 사람은 호텔에 있으면서 옷을 또 사지 않는다.
        if kind is not None:
            occasional(r, save, d, day)
        # 여행은 목표에 맞추지 않는다 — 원래 튀는 날이고, 튀어야 여행이다.
        if kind is not None:
            day = fit_day(r, day, kind, save)
        else:
            day = sorted(day, key=lambda x: x[0])

        for t, place, kind, amount, note in day:
            rows.append((d, t, place, kind, amount, note))
        d += timedelta(days=1)

    rows.sort(key=lambda x: (x[0], x[1]))
    return rows


# ══════════════════════════════════════════════════════════════════════
#  업종을 실제 가맹점으로 바꾼다
#
#  여기까지는 '점심'·'카페' 같은 **업종**으로 만들었다. 그래야 하루의 모양을 먼저 잡을 수
#  있기 때문이다. 이제 그 자리에 **그 동네에 실제로 있는 가게**를 앉힌다.
#
#  **무작위로 고르지 않는다.** 열 곳을 고르게 도는 사람은 없다 — 자주 가는 두세 곳이
#  절반을 가져가고 나머지가 꼬리로 붙는다. 그래서 후보 목록의 앞쪽에 가중치를 몰아준다
#  (i 번째 가게의 무게는 1/(i+1.2) — 첫 집이 둘째의 두 배쯤 된다).
# ══════════════════════════════════════════════════════════════════════

def load_plan():
    import json
    d = json.load(io.open(os.path.join(OUT, '배정표.json'), encoding='utf-8'))
    return ({tuple(k.split('|')): v for k, v in d['plan'].items()}, d['biz'])


def assign(rows, plan, biz):
    """(위치, 업종) 마다 가게를 앉힌다. 가중치는 **사람마다 고정**이다 — 날마다 새로
    뽑으면 단골이 안 생긴다. 같은 씨앗을 쓰므로 5월의 1등이 9월에도 1등이다."""
    r = random.Random(SEED + 7)
    out, unmapped = [], collections.Counter()
    for d, t, place, kind, amount, note in rows:
        cands = plan.get((place, kind))
        if not cands:
            unmapped[(place, kind)] += 1
            out.append((d, t, place, kind, kind, '', amount, note))
            continue
        w = [1.0 / (i + 1.2) for i in range(len(cands))]
        shop = r.choices(cands, weights=w, k=1)[0]
        out.append((d, t, place, kind, shop, biz.get(shop, ''), amount, note))
    return out, unmapped


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = build()

    plan, biz = load_plan()
    rows, unmapped = assign(rows, plan, biz)
    if unmapped:
        print('  ** 가게를 못 앉힌 조합 **')
        for (loc, kind), n in unmapped.most_common():
            print(f'     {loc[:34]:<36}{kind:<10}{n}건')

    # ── 사람이 읽는 표 ──
    human = os.path.join(OUT, '김하영-소비내역.tsv')
    with io.open(human, 'w', encoding='utf-8') as f:
        f.write('일시\t위치\t가맹점\t업종\t사업자번호\t금액\t내용\n')
        for d, t, place, kind, shop, bz, amount, note in rows:
            f.write(f'{d.year}. {d.month}. {d.day}. {t}\t{place}\t{shop}\t{kind}\t{bz}\t{amount}원\t{note}\n')

    # ── 적재용 명세서 ──
    card = os.path.join(OUT, '김하영-명세서.csv')
    with io.open(card, 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['거래일', '가맹점명', '이용금액', '사업자번호', '업종코드'])
        for d, t, place, kind, shop, bz, amount, note in rows:
            w.writerow([d.isoformat(), shop, amount, bz, ''])

    # ── 요약 ──
    from collections import defaultdict
    by_month = defaultdict(int)
    by_day = defaultdict(int)
    by_kind = defaultdict(int)
    for d, t, place, kind, shop, bz, amount, note in rows:
        by_month[(d.year, d.month)] += amount
        by_day[d] += amount
        by_kind[shop] += amount

    print(f'  결제 {len(rows):,}건 · {START} ~ {END}')
    print('\n  달마다')
    for (y, m), v in sorted(by_month.items()):
        days = sum(1 for k in by_day if k.year == y and k.month == m)
        print(f'    {y}-{m:02d}   {v:>10,}원   하루 평균 {v // max(1, days):>7,}원')

    vals = sorted(by_day.values())
    print(f'\n  하루 지출  최저 {vals[0]:,} · 중앙 {vals[len(vals) // 2]:,} · 최고 {vals[-1]:,}')
    inr = sum(1 for v in vals if 50000 <= v <= 150000)
    print(f'    5~15만 안 {inr}/{len(vals)}일 ({inr * 100 // len(vals)}%)')

    print('\n  9월 절약 (9/1 부터 7일씩)')
    base_days = [v for k, v in by_day.items() if k.month < 9]
    base = sum(base_days) // len(base_days)
    print(f'    5~8월 하루 평균  {base:,}원')
    for i in range(0, 30, 7):
        seg = [v for k, v in by_day.items()
               if k.month == 9 and i < k.day <= min(30, i + 7)]
        if not seg:
            continue
        avg = sum(seg) // len(seg)
        print(f'    9/{i + 1:02d}~9/{min(30, i + 7):02d}   하루 {avg:>7,}원   ({avg * 100 // base}%)')

    print('\n  자주 간 곳 상위')
    for kind, v in sorted(by_kind.items(), key=lambda x: -x[1])[:12]:
        print(f'    {kind:<10} {v:>10,}원')

    print(f'\n  {human}')
    print(f'  {card}')


if __name__ == '__main__':
    main()
