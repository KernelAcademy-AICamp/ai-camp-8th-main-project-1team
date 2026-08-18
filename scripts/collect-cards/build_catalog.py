"""공시에서 뽑은 초안을 런타임 카탈로그로 만든다 — 사람이 대조한 JSON 이 원천이고 카탈로그는 파생이다.

  schema-draft.json ─┐
  cards/*.json     ──┴→  backend/src/main/resources/card-catalog.json

  실행:  python3 scripts/collect-cards/build_catalog.py

`build_industry.py` 와 같은 자리에 있는 스크립트다. 원천은 사람이 원문과 대조한 파일이고,
런타임이 읽는 것은 여기서 만든 파생물이다. 원천이 하나이므로 둘이 갈라질 수 없다.

## 왜 초안을 그대로 안 넣나

초안은 **공시가 적은 대로** 생겼다 — 실적 구간이 배열이고, 한도가 구간 금액을 키로 하는
사전이고, 대상이 브랜드와 업종이 섞인 목록이다. 표(V36)는 그걸 행으로 편 모양이라 옮기는
일이 필요하고, 그 김에 **게이트 3(규칙 검산)** 을 여기서 돌린다.

## 게이트 3 — 사람이 안 봐도 이상을 잡는다

    실적 제외 항목 수 < 5             → 참고   ← KaPick 사고(12개를 1개로 읽음)를 잡았을 규칙
    총연회비 != 기본 + 제휴            → 참고
    실적 구간이 오르는데 한도가 내려감   → 참고
    한도의 구간 키가 tiers 에 없음      → 참고
    셈할 수 있는 혜택이 하나도 없음      → 참고
    as_of 없음                       → 참고

하나라도 걸리면 `grade = REFERENCE` 로 두고 **화면에 숫자를 안 보여준다.** 빌드를 실패시키지
않는 것은, 나중에 카드가 수천 장이 될 때 한 장의 결함이 전체 적재를 막으면 안 되기 때문이다.
대신 이유를 `gradeReason` 에 적어 두고 요약을 찍는다.

## '셈할 수 있는 혜택'(countable)이 이 파일의 핵심 판단이다

공시의 혜택 중에는 **금액으로 옮길 수 없는 것**이 섞여 있다. 셋 다 계산에서 빼되 표시는 한다.

    무이자할부·비금전(라운지)   금액 환산 자체가 안 된다
    매칭 대상이 없는 혜택        '온누리상품권 가맹점'·'해외 가맹점' — 승인내역에서 못 고른다
    결제수단 조건               '간편결제 경유 시 제외' — 승인내역에 결제수단 칸이 없다

빼면 절감액이 **하한 방향**으로 틀린다. "채운 줄 알았는데 못 채웠다"가 구조적으로 안 나는
쪽이라 이 방향을 고른다(07 §4.4).

## 업종은 축 이름으로 옮긴다 — 그리고 모르는 말은 빌드를 세운다

카드 공시는 "시내버스·지하철"이라 적고 우리 축은 '대중교통'이다. 이 옮김을 자동으로 하면
안 된다 — 이름으로 업종을 찾아보면 '시내버스'는 걸리는데 '지하철'은 한 건도 안 걸린다
(2026-08-11 실측). 그래서 **사람이 검토한 표**로만 옮기고, 표에 없는 말이 나오면 `sys.exit(1)`
로 세운다. `build_industry.py` 가 축 오타를 빌드에서 잡는 것과 같은 규율이다.
"""
import json
import glob
import re
import os
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)          # 같은 폴더의 후보 선정 규칙을 그대로 가져다 쓴다
import select_youth_cards         # noqa: E402  (경로를 넣은 뒤라야 import 된다)

ROOT = os.path.join(HERE, '..', '..')
DRAFT = os.path.join(HERE, 'schema-draft.json')
CARDS = os.path.join(HERE, 'cards')
REPORT = os.path.join(HERE, 'extraction-report.md')
# 개인 추천 대상이 아니라 카탈로그에서 뺀 카드 (이름, 사유). 요약에 그대로 찍는다.
EXCLUDED = []
BACKEND = os.path.join(ROOT, 'backend', 'src', 'main', 'resources')
INDUSTRY = os.path.join(BACKEND, 'industry-mid.json')

BASELINE_INDUSTRIES = {'시내버스', '지하철', 'SKT/KT/LGU+ 휴대폰요금 자동이체'}
BASELINE_EXCLUSION_CODES = {
    'TRANSIT', 'PUBLIC_DUES', 'UTILITY', 'SOCIAL_INS', 'TAX', 'PENALTY', 'HOUSING',
    'TUITION', 'CASH_ADVANCE', 'CARD_LOAN', 'FEE', 'GIFT_CARD', 'INSTALLMENT_FREE',
    'PUBLIC_MERCHANT',
}

# 실적 제외 항목이 이보다 적으면 추출이 지면의 일부만 읽었다고 본다.
# 실측 근거: BC 3장이 각각 10·9·12 개였고, KaPick 을 1개로 잘못 읽은 적이 있다.
MIN_PERFORMANCE_EXCLUSIONS = 5

# LLM 이 category 칸에 자유롭게 쓴 말을 접는 표. **정본은 axis-aliases.json 하나다** —
# 여기에 같은 목록을 또 두면 둘이 갈라진다(실제로 3개씩 어긋나 있었다, 2026-08-14).
#
#   axisAliases  '커피전문점' → '카페/디저트'      브랜드도 업종도 없을 때만 쓴다
#   notAxis      접지 않는 말 + 접지 않는 이유
#     · '모든가맹점'  "어디서 쓰든 다" — 셀 것이 없는 게 아니라 **전부가 대상**이다.
#                    V36 의 scope='ALL' 이 이 자리를 위해 있다. 카드 절반이 이런 기본
#                    적립을 하나씩 달고 있는데(국내외 가맹점 0.7%) 대상 행이 비어 있다는
#                    이유로 셈에서 빠져 있었다.
#     · 그 밖         해외·결제수단·브랜드·범위불명 — 표시만 하고 계산에서 뺀다.
#
# **'해외' 는 '모든가맹점' 이 아니다.** 승인내역에 국내·해외를 가르는 칸이 없어
# (MyDataPayment 에 통화도 해외 플래그도 없다) 국내 결제까지 해외 요율로 세게 된다.
# 그러면 없는 절감액이 생긴다 — 안 세면 하한 방향으로만 틀리므로 그쪽을 고른다.
def load_axis_aliases():
    with open(os.path.join(HERE, 'axis-aliases.json'), encoding='utf-8') as f:
        root = json.load(f)
    aliases = {k: v for k, v in root['axisAliases'].items() if not k.startswith('_')}
    not_axis = {k: v for k, v in root['notAxis'].items() if not k.startswith('_')}
    all_merchant = {k for k, v in not_axis.items() if v == '모든가맹점'}
    return aliases, not_axis, all_merchant


CATEGORY_TO_AXIS, NOT_AXIS, ALL_MERCHANT = load_axis_aliases()

# 표에 없어서 못 접은 category. 요약에 찍어 사람이 표를 늘릴 거리를 남긴다.
UNMAPPED_CATEGORIES = Counter()

# 칸 길이 때문에 자른 값. 조용히 자르지 않고 요약에 찍는다.
TRUNCATED = Counter()


def fit_column(value, limit, log):
    """표의 칸 길이에 맞춘다. 자른 것은 세어 둔다."""
    text = value or ''
    if len(text) <= limit:
        return text
    log[text] += 1
    return text[:limit - 1] + '…'

# 카드 공시의 업종 표현 → 카드혜택 축(21종). **사람이 검토해서만 는다.**
# 여기 없는 말이 나오면 빌드가 선다 — 조용히 '혜택축없음'으로 떨어뜨리면 그 혜택이
# 계산에서 사라진 것을 아무도 눈치채지 못한다.
INDUSTRY_TO_AXIS = {
    '시내버스': '대중교통',
    '지하철': '대중교통',
    'SKT/KT/LGU+ 휴대폰요금 자동이체': '통신',
    'SKT': '통신',
    'KT': '통신',
    'LGU+': '통신',
    '통신요금': '통신',
    '통신': '통신',          # 축 이름을 그대로 쓴 경우(이마트 e카드 Edition2)
    # ── KB 387장 추출에서 나온 표현(2026-08-18). 축은 지어내지 않고 기존 선례를 따랐다 —
    #    좁은 말부터 넓은 말 순으로 규칙을 세워 옮기고, 어느 축에도 안 맞으면 '혜택축없음'
    #    으로 둔다(표시는 하고 절감액 매칭에서만 뺀다). 자동차 정비·보험·사무기기가 그런 예다.
    #    **'해외' 가 붙은 말은 축으로 옮기지 않는다** — 승인내역에 국내·해외 칸이 없다.
    "4대 사회보험료": '공과금/렌탈',
    "도시가스 업종": '공과금/렌탈',
    "소노시즌 렌탈요금 자동납부": '공과금/렌탈',
    "수도": '공과금/렌탈',
    "수도요금": '공과금/렌탈',
    "청호나이스 렌탈요금 자동납부": '공과금/렌탈',
    "기술/사무/가정계": '교육/육아',
    "기술학원": '교육/육아',
    "기타 학원 업종": '교육/육아',
    "독서실": '교육/육아',
    "문리계": '교육/육아',
    "문리계 학원": '교육/육아',
    "문리계/외국어/예체능/기술학원, 서점 업종": '교육/육아',
    "밀크T 학습비 자동납부": '교육/육아',
    "스터디카페": '교육/육아',
    "예체능": '교육/육아',
    "예체능학원": '교육/육아',
    "외국어": '교육/육아',
    "외국어 학원": '교육/육아',
    "웅진씽크빅 자동납부": '교육/육아',
    "유아전문 교육기관/놀이기관": '교육/육아',
    "유아전문교육기관": '교육/육아',
    "유치원/어린이집/놀이방": '교육/육아',
    "자동차학원": '교육/육아',
    "초중고 학교납입금": '교육/육아',
    "고속/시외버스": '대중교통',
    "고속·시외버스": '대중교통',
    "대중교통": '대중교통',
    "버스, 지하철": '대중교통',
    "전국 버스": '대중교통',
    "전국버스": '대중교통',
    "철도(일반/KTX/SRT)": '대중교통',
    "후불교통(시내버스/지하철)": '대중교통',
    "3대 대형마트": '마트',
    "농·수·축협 직판장": '마트',
    "농수축산 직판장": '마트',
    "농수축협 직판매장": '마트',
    "농축협직판장": '마트',
    "대형 할인마트": '마트',
    "대형마트 업종": '마트',
    "마트": '마트',
    "슈퍼": '마트',
    "슈퍼마켓": '마트',
    "국내 면세점": '백화점/면세점',
    "면세점(기내제외)": '백화점/면세점',
    "백화점, 면세점 업종": '백화점/면세점',
    "온라인면세": '백화점/면세점',
    "전국백화점": '백화점/면세점',
    "건강검진": '병원/약국',
    "건강식품점": '병원/약국',
    "동물병원 애완동물 업종": '병원/약국',
    "병의원": '병원/약국',
    "의료기관": '병원/약국',
    "의료기기 가맹점": '병원/약국',
    "의료기기 및 용품": '병원/약국',
    "의료품도매": '병원/약국',
    "의약품도매업체": '병원/약국',
    "일반/치과/한방병원": '병원/약국',
    "일반/치과/한의원": '병원/약국',
    "제약회사": '병원/약국',
    "종합병원, 일반병원(한의원), 동물병원, 약국 업종": '병원/약국',
    "치과병원": '병원/약국',
    "한약방": '병원/약국',
    "미용": '뷰티',
    "미용실, 화장품, 피부미용업종": '뷰티',
    "미용업종": '뷰티',
    "미용원": '뷰티',
    "피부미용": '뷰티',
    "피부미용실": '뷰티',
    "피부미용업종": '뷰티',
    "피부미용원": '뷰티',
    "헤어샵(미용실)": '뷰티',
    "화장품점": '뷰티',
    "가스판매점": '쇼핑',
    "가전제품점": '쇼핑',
    "문구": '쇼핑',
    "문구업종": '쇼핑',
    "문구점": '쇼핑',
    "문방구": '쇼핑',
    "문방구점": '쇼핑',
    "서점 업종": '쇼핑',
    "서점업종": '쇼핑',
    "연탄 및 유류판매점": '쇼핑',
    "완구점 업종": '쇼핑',
    "총포류판매점": '쇼핑',
    "팬시용품점": '쇼핑',
    "프리미엄 아울렛": '쇼핑',
    "경기장": '스포츠/레저',
    "골프(연습)장": '스포츠/레저',
    "골프/골프연습장": '스포츠/레저',
    "골프장, 골프연습장 업종": '스포츠/레저',
    "기타 레저업소": '스포츠/레저',
    "놀이공원": '스포츠/레저',
    "당구": '스포츠/레저',
    "당구장": '스포츠/레저',
    "레저용품 업종": '스포츠/레저',
    "레포츠용품점": '스포츠/레저',
    "볼링": '스포츠/레저',
    "수영": '스포츠/레저',
    "스크린골프": '스포츠/레저',
    "스키": '스포츠/레저',
    "스포츠 관련 업종": '스포츠/레저',
    "스포츠센터": '스포츠/레저',
    "스포츠용품": '스포츠/레저',
    "요가 업종": '스포츠/레저',
    "체력단련장": '스포츠/레저',
    "테니스": '스포츠/레저',
    "휘트니스클럽": '스포츠/레저',
    "애완동물 업종": '애완',
    "관광여행사": '여행/항공',
    "기타관광호텔": '여행/항공',
    "기타호텔": '여행/항공',
    "숙박": '여행/항공',
    "숙박(호텔, 민박, 펜션, 기타숙박)": '여행/항공',
    "온라인항공": '여행/항공',
    "일반관광호텔": '여행/항공',
    "일반호텔": '여행/항공',
    "특급/일반/기타관광호텔": '여행/항공',
    "특급관광호텔": '여행/항공',
    "펜션": '여행/항공',
    "하나투어": '여행/항공',
    "항공사": '여행/항공',
    "항공사, 기타관광호텔, 기타숙박업, 일반관광호텔, 특급관광호텔, 펜션/민박, 관광여행사 업종": '여행/항공',
    "호텔": '여행/항공',
    "PC방": '영화/문화',
    "PC방 업종": '영화/문화',
    "게임방": '영화/문화',
    "공연장/전시장": '영화/문화',
    "만화방 등": '영화/문화',
    "문화센터": '영화/문화',
    "비디오방": '영화/문화',
    "온라인티켓": '영화/문화',
    "3대 소셜커머스": '온라인쇼핑',
    "3대 온라인쇼핑몰": '온라인쇼핑',
    "온라인몰": '온라인쇼핑',
    "전자상거래": '온라인쇼핑',
    "전자상거래 PG": '온라인쇼핑',
    "전자상거래 오픈마켓": '온라인쇼핑',
    "홈쇼핑": '온라인쇼핑',
    "음식": '외식',
    "일반 주점": '외식',
    "일반/휴게음식점": '외식',
    "일반음식점": '외식',
    "일반음식점, 휴게음식점, 일반주점 업종": '외식',
    "일식/생선회집": '외식',
    "주점": '외식',
    "패밀리 레스토랑": '외식',
    "패스트푸드업종": '외식',
    "패스트푸드점": '외식',
    "호프": '외식',
    "휴게음식점": '외식',
    "GS주유소": '주유',
    "SK주유소": '주유',
    "경유": '주유',
    "국내 GS주유소": '주유',
    "국내 SK주유소": '주유',
    "등유": '주유',
    "수소차 충전소": '주유',
    "전 주유소(충전소 포함)": '주유',
    "전 주유소/충전소": '주유',
    "전기차 충전기": '주유',
    "주유(충전)": '주유',
    "주유/충전소": '주유',
    "주유소 업종(충전소 제외)": '주유',
    "주유소(휘발유,경유)": '주유',
    "충전소(LPG)": '주유',
    "화물차 우대 주유소": '주유',
    "화물특화 주유소": '주유',
    "휘발유": '주유',
    "베이커리": '카페/디저트',
    "아이스크림": '카페/디저트',
    "아이스크림점": '카페/디저트',
    "제과": '카페/디저트',
    "제과, 아이스크림, 패스트푸드 업종": '카페/디저트',
    "제과/아이스크림": '카페/디저트',
    "제과/아이스크림점": '카페/디저트',
    "커피 전문점": '카페/디저트',
    "커피/음료": '카페/디저트',
    "커피/음료 전문점": '카페/디저트',
    "커피/제과/패스트푸드 업종": '카페/디저트',
    "커피음료전문점": '카페/디저트',
    "커피전문점 업종": '카페/디저트',
    "IPTV": '통신',
    "IPTV(QOOK TV)": '통신',
    "IPTV(올레TV)": '통신',
    "KB Liiv M": '통신',
    "KMVNO 회원사 알뜰폰 통신요금 자동이체": '통신',
    "KT LTE 월정액 67 이상 요금제": '통신',
    "KT Olleh": '통신',
    "KT olleh": '통신',
    "KT 스카이라이프 이용료": '통신',
    "KT 이동통신 요금": '통신',
    "KT 이동통신요금": '통신',
    "KT 휴대폰요금": '통신',
    "KT스카이라이프": '통신',
    "LG U+ 통신료 자동납부": '통신',
    "LG U+ 통신요금 자동납부": '통신',
    "LG U플러스": '통신',
    "Liiv M 통신료 자동이체": '통신',
    "Liiv M 통신비 자동납부": '통신',
    "Olleh": '통신',
    "SK 7mobile 통신료 자동이체": '통신',
    "SKT 이동통신요금": '통신',
    "SKT 이동통신요금 자동납부": '통신',
    "SKT, KT Olleh, LG U+ 자동이체": '통신',
    "SKT, KT olleh, LG U+ 이동통신요금 자동납부": '통신',
    "SKT, KT, LG U+, Liiv M 자동납부": '통신',
    "SKT/KT/LG U+ 이동통신 자동납부": '통신',
    "SKT/KT/LG U+ 자동납부 통신요금": '통신',
    "U+유모바일 통신료 자동납부": '통신',
    "kt M mobile 통신료 자동납부": '통신',
    "kt M mobile 통신요금 자동납부": '통신',
    "kt 이동통신요금 자동납부": '통신',
    "kt 통신 단말기 포인트연계할부서비스(세이브)": '통신',
    "국제전화": '통신',
    "소액결제": '통신',
    "알뜰폰 통신비 자동이체": '통신',
    "와이브로": '통신',
    "우체국 알뜰폰": '통신',
    "유/무선 인터넷": '통신',
    "유무선 통신": '통신',
    "유선전화": '통신',
    "이동통신요금 자동이체": '통신',
    "인터넷 상품": '통신',
    "인터넷 쇼핑몰": '통신',
    "인터넷 이용료": '통신',
    "인터넷요금": '통신',
    "인터넷이용료": '통신',
    "인터넷전화": '통신',
    "자동납부(이동통신, 아파트관리비, 도시가스)": '통신',
    "전화 요금": '통신',
    "전화요금": '통신',
    "초고속인터넷": '통신',
    "케이블": '통신',
    "케이블 TV": '통신',
    "케이블TV": '통신',
    "토스모바일 통신요금 자동납부": '통신',
    "휴대폰": '통신',
    "OA기기": '혜택축없음',
    "공무원 연금매장": '혜택축없음',
    "공무원연금매장": '혜택축없음',
    "군휴양시설": '혜택축없음',
    "기타 차량 서비스 업종": '혜택축없음',
    "기타보험": '혜택축없음',
    "목욕탕": '혜택축없음',
    "방역": '혜택축없음',
    "보안경비": '혜택축없음',
    "보안경비업종": '혜택축없음',
    "복사기": '혜택축없음',
    "부품": '혜택축없음',
    "사무용기기판매/수리업종": '혜택축없음',
    "삼성페이, 네이버페이, 카카오페이, KB Pay 등": '혜택축없음',
    "생명/손해/기타보험 업종": '혜택축없음',
    "생활": '혜택축없음',
    "손해보험 업종": '혜택축없음',
    "우체국 쇼핑": '혜택축없음',
    "우체국 우편료": '혜택축없음',
    "인테리어": '혜택축없음',
    "자동차 정비 업종": '혜택축없음',
    "자동차 정비업종": '혜택축없음',
    "찜질방": '혜택축없음',
    "차량정비": '혜택축없음',
    "차량정비/부품/인테리어": '혜택축없음',
    "청소대행": '혜택축없음',
    "청소대행/방역업종": '혜택축없음',
    "팜스넷 가맹점": '혜택축없음',
    # ── B군(발급중단) 가이드북에서 나온 표현. 축은 지어내지 않고 기존 선례를 따랐다
    #    (서점→쇼핑은 '온라인서점', 특급호텔→여행/항공은 '국내 특급호텔' 과 같은 처리).
    '외식': '외식',
    '병원/약국': '병원/약국',
    '온라인쇼핑몰': '온라인쇼핑',
    '면세점': '백화점/면세점',
    '항공': '여행/항공',
    '국내 항공사': '여행/항공',
    '여행사': '여행/항공',
    '특급호텔': '여행/항공',
    '화장품': '뷰티',
    '이미용': '뷰티',
    '의류': '쇼핑',
    '제화/잡화': '쇼핑',
    '서점': '쇼핑',
    '대형마트': '마트',
    '창고형 할인점': '마트',
    '시내·시외버스': '대중교통',
    '철도': '대중교통',
    '이동통신요금': '통신',
    'kt M mobile 통신요금 자동이체': '통신',
    'SK 7mobile 통신요금 자동이체': '통신',
    # 축이 없는 것들. 표시는 하고 절감액 매칭에서만 뺀다.
    '보험': '혜택축없음',              # 생명보험·손해보험과 같은 처리
    '자동차': '혜택축없음',            # 21개 축에 자동차 관리가 없다
    '차량 정비': '혜택축없음',
    '하이패스': '혜택축없음',          # 통행료는 대중교통이 아니다(TOLL 과 같은 판단)
    # **'해외 이용' 은 축으로 옮기지 않는다.** 승인내역에 국내·해외를 가르는 칸이 없어
    # 국내 결제까지 해외 요율로 세게 된다 — 없는 절감액이 생긴다.
    '해외 이용': '혜택축없음',
    '대형할인점': '마트',
    '슈퍼마켓 업종': '마트',
    '병의원 업종': '병원/약국',
    '음식점 업종': '외식',
    '학원 업종': '교육/육아',
    '전 주유 업종': '주유',
    '주유 업종': '주유',
    '아파트관리비': '공과금/렌탈',
    '전기요금': '공과금/렌탈',
    # 현재 21개 축에 자동차 관리가 없다. 혜택은 표시하되 개인화 금액에는 매칭하지 않는다.
    '세차장 업종': '혜택축없음',
    '주차장': '혜택축없음',
    # 하나카드 상품설명서 표현(2026-08-12 원문 대조).
    'EV(전기차)충전': '주유',
    '수소충전': '주유',
    'SKT/KT/LG U+ 통신요금 자동이체': '통신',
    'SKT/KT/LGU+ 자동납부요금': '통신',
    'SKT/SKB/KT/LGU+ 월납요금 자동이체': '통신',
    'U+ 휴대폰요금 자동이체': '통신',
    '가스요금': '공과금/렌탈',
    '도시가스요금': '공과금/렌탈',
    '전기/가스요금 자동납부': '공과금/렌탈',
    '아파트 관리비': '공과금/렌탈',
    '국민연금': '공과금/렌탈',
    '고용보험료': '공과금/렌탈',
    '산재보험료': '공과금/렌탈',
    '건강보험료': '공과금/렌탈',
    '웅진프리드라이프': '공과금/렌탈',
    '버스': '대중교통',
    '마을버스': '대중교통',
    '광역버스': '대중교통',
    '공항버스': '대중교통',
    '공항철도': '대중교통',
    '택시업종': '택시',
    '국내 일반음식점': '외식',
    '커피업종': '카페/디저트',
    '병/의원': '병원/약국',
    '약국': '병원/약국',
    '일반병원': '병원/약국',
    '종합병원': '병원/약국',
    '치과': '병원/약국',
    '한방병원': '병원/약국',
    '한의원': '병원/약국',
    '골프연습장 업종': '스포츠/레저',
    '헬스 업종': '스포츠/레저',
    '헬스클럽 업종 가맹점': '스포츠/레저',   # 위 '헬스 업종'과 같은 말을 우리카드가 길게 적은 것
    '어학시험(TOEIC, OPIC, JPT, HSK)': '교육/육아',
    '외국어학원 업종': '교육/육아',
    '입시/보습/외국어/예체능계 학원': '교육/육아',
    # KB국민·우리카드 상품설명서 표현(2026-08-12 원문 대조).
    'HD현대오일뱅크': '주유',
    '에쓰오일': '주유',
    'LPG': '주유',
    '수소': '주유',
    '전기': '주유',
    '전기차충전': '주유',
    '주유소': '주유',
    '충전소': '주유',
    '충전소LPG': '주유',
    'KT 단말기 장기할부': '통신',
    'KT 통신요금 자동납부': '통신',
    'KT스카이라이프 자동납부': '통신',
    'SK텔링크 휴대폰 통신요금 자동납부': '통신',
    'U+ 유모바일 통신요금 자동납부': '통신',
    '이동통신': '통신',
    '고속버스': '대중교통',
    '시외버스': '대중교통',
    '기술/사무/가정계학원': '교육/육아',
    '기타학원': '교육/육아',
    '문리계학원': '교육/육아',
    '예체능계학원': '교육/육아',
    '외국어학원': '교육/육아',
    '학습지': '교육/육아',
    '학원': '교육/육아',
    '어학시험(TOEIC/OPIc/HSK)': '교육/육아',
    '어학원(해커스/파고다)': '교육/육아',
    '도시가스': '공과금/렌탈',
    '레저용품점': '스포츠/레저',
    '스포츠용품점': '스포츠/레저',
    '종합스포츠센터': '스포츠/레저',
    '골프': '스포츠/레저',
    '골프연습장': '스포츠/레저',
    '미용실': '뷰티',
    '병원': '병원/약국',
    '일반/치과/한방 병(의)원': '병원/약국',
    '택시': '택시',
    '패밀리레스토랑': '외식',
    '패스트푸드': '외식',
    '편의점': '편의점',
    # 현재 추천 축에 보험·세탁 업종이 없다. 혜택은 표시하되 절감액에는 매칭하지 않는다.
    '생명보험': '혜택축없음',
    '손해보험': '혜택축없음',
    '세탁소': '혜택축없음',
    # ── 현대·삼성 추출에서 나온 표현. 축은 지어내지 않고 nts-mid.tsv 가 같은 업종에 붙인 값을
    #    그대로 따랐다(예: 유흥주점 → 외식, 노래연습장 → 영화/문화, 수의업 → 애완).
    '커피': '카페/디저트',
    '커피 업종': '카페/디저트',
    '커피전문점': '카페/디저트',
    '커피/음료전문점': '카페/디저트',
    '제과점': '카페/디저트',
    '음식점': '외식',
    '한식': '외식',
    '중식': '외식',
    '일식': '외식',
    '양식': '외식',
    '뷔페': '외식',
    '간이음식점': '외식',
    '패스트푸드 업종': '외식',
    '일반주점': '외식',
    '유흥주점': '외식',
    '단란주점': '외식',
    '주류판매점': '외식',
    '노래방': '영화/문화',
    '영화(CGV, 롯데시네마)': '영화/문화',
    '온라인서점': '쇼핑',          # 서적 소매업이 '쇼핑'이다
    '동물병원': '애완',            # 수의업 → 애완
    '골프장': '스포츠/레저',
    '스포츠': '스포츠/레저',
    '국내 특급호텔': '여행/항공',
    '백화점': '백화점/면세점',
    '편의점 업종': '편의점',
    '약국 업종': '병원/약국',
    '의원': '병원/약국',
    '병·의원': '병원/약국',
    '올리브영': '뷰티',
    '유치원': '교육/육아',
    '어린이집': '교육/육아',
    '교육사이트 megastudy': '교육/육아',
    'Mbest': '교육/육아',
    '전 주유소': '주유',
    '모든 주유소': '주유',
    'LPG 충전소': '주유',
    'LG U+': '통신',
    '알뜰폰': '통신',
    '알뜰폰(SK텔링크, KT M모바일, 헬로모바일, 미디어로그)': '통신',
    '이동통신요금 자동납부': '통신',
    '이동통신요금 자동납부(SK, KT, LG, LiivM)': '통신',
    'LG U+ 이동통신요금 자동이체': '통신',
    'SKT/KT/LG U+/알뜰폰(SK텔링크, KT스카이라이프, KT M모바일, 헬로모바일, 미디어로그) 이동통신요금': '통신',
    '통신요금 자동이체': '통신',
    '유선전화요금': '통신',
    '인터넷': '통신',
    # 4대보험 납부는 보험 상품이 아니라 공과금 성격이다.
    '건강보험': '공과금/렌탈',
    '고용보험': '공과금/렌탈',
    '산재보험': '공과금/렌탈',
    # ── 2차 추출(6개사)에서 더 나온 표현.
    'LG': '통신',
    'LiivM': '통신',
    'Liiv M': '통신',
    'SKT/KT/LGU+ 자동이체': '통신',
    '영화관': '영화/문화',
    '배달앱': '외식',              # 배달은 축이 따로 없다 — 브랜드 판정이 1순위다
    '일반의원': '병원/약국',
    '건강진단센터': '병원/약국',
    '테니스장': '스포츠/레저',
    '볼링장': '스포츠/레저',
    '스키장': '스포츠/레저',
    '수영장': '스포츠/레저',
    '레포츠클럽': '스포츠/레저',
    '요가': '스포츠/레저',
    '세차장': '혜택축없음',         # 차량관리 축이 없다. 실적에는 들어간다
    '할인점': '마트',
    '주유': '주유',
}

# 초안의 kind 표기 → 표의 코드.
# 실적 제외 코드 → 승인내역에서 뺄 수 있는 카드혜택 축.
#
# **왜 코드가 아니라 데이터인가.** 원칙 4 는 "카테고리 이름을 코드에 박지 않는다"이고, 이 표는
# 축 이름투성이다. 그래서 카탈로그와 함께 나가고 런타임은 읽기만 한다.
#
# **빈 목록은 '제외 대상이 없다'가 아니라 '승인내역으로 판정할 수 없다'** 이다. 현금서비스·
# 카드론·수수료는 애초에 결제 내역이 아니고, 상품권 구매와 무이자할부는 승인내역에 그런 칸이
# 없다. 이것들을 못 빼면 실적이 **과대** 계산되는데(= 나쁜 방향), 대신할 신호가 없다.
# 여기 적어 두는 이유는 "빼먹은 것"과 "못 빼는 것"을 구분해 두기 위해서다.
EXCLUSION_CODE_TO_AXES = {
    'TRANSIT': ['대중교통'],
    'PUBLIC_DUES': ['공과금/렌탈'],
    'UTILITY': ['공과금/렌탈'],
    'SOCIAL_INS': ['공과금/렌탈'],
    'TAX': ['공과금/렌탈'],
    'PENALTY': ['공과금/렌탈'],
    'HOUSING': ['공과금/렌탈'],
    'TUITION': ['교육/육아'],
    'CASH_ADVANCE': [],       # 결제가 아니라 대출이다 — 승인내역에 안 나타난다
    'CARD_LOAN': [],          # 위와 같음
    'FEE': [],                # 연회비·이자는 가맹점 결제가 아니다
    'GIFT_CARD': [],          # 상품권 구매는 업종이 판매처를 따라가 '쇼핑'과 안 갈린다
    'INSTALLMENT_FREE': [],   # 할부 개월 칸이 승인내역에 없다
    'PUBLIC_MERCHANT': [],    # '공공기관이 개설한 가맹점'은 업종이 아니라 개설 주체다
    'DISCOUNTED_TX': [],      # 다른 할인 적용 여부는 승인내역에 없다
    'OVERSEAS': [],           # 현재 승인내역에는 국내/해외 판정 신호가 없다
    'PAY_CHANNEL': [],        # 간편결제 경유 여부는 승인내역에 없다
    # 하나카드 공시의 세분 코드. 빈 목록은 현재 승인내역만으로 안전하게 판정할 수 없다는 뜻이다.
    'ATM': [],
    'BENEFIT_EXCLUDED': [],
    'DISCOUNTED': [],
    'DISCOUNTED_PURCHASE': [],
    'DISCOUNT_EXCLUDED': [],
    'FOREIGN_MONEY': [],
    'GOV_SUPPORT': [],
    'KAKAO_FRIENDS': [],
    'KAKAO_PAY': [],
    'OKCASHBAG_CHARGE': [],
    'PG': [],
    'PG_PAY': [],
    'POINT_CHARGE': [],
    'POINT_EARNED': [],
    'PREPAID_CARD': [],
    'PREPAID_CHARGE': [],
    'PREPAID_PAY': [],
    'RENT': ['공과금/렌탈'],
    'SCHOOL_BANKING': ['교육/육아'],
    # KB국민·우리카드 세분 코드. 승인내역으로 판정 가능한 축만 연결한다.
    'ANNUAL_FEE': [],
    'BENEFIT_APPLIED': [],
    'BUS': ['대중교통'],
    'CANCEL': [],
    'CANCELLED': [],
    'DISCOUNTED_AMOUNT': [],
    'DISCOUNTED_PAYMENT': [],
    'DISCOUNTED_TRANS': [],
    'EVENT': [],
    'EVENT_BENEFIT': [],
    'KREAM_PAY': [],
    'KREAM_PURCHASE': [],
    'LEASE': [],
    'NON_AUTH': [],
    'PUBLIC_RENT': ['공과금/렌탈'],
    'TRANSIT_PURCHASE': ['대중교통'],
    # 현대·삼성 세분 코드. **같은 뜻을 코드명만 바꿔 뱉는 것이 대부분이다** — 아래 여섯 줄이
    # 전부 "다른 할인/행사 혜택이 적용된 건"이고, 승인내역에는 그 칸이 없다. 추출 스키마가
    # 코드를 닫힌 목록으로 강제하지 않아 생긴 것이라, 근본 해결은 여기가 아니라 프롬프트다.
    'DISCOUNT': [],
    'DISCOUNT_APPLIED': [],
    'DISCOUNT_INSTALLMENT': [],
    'DISCOUNTED_TRANSACTION': [],
    'DISCOUNTED_TXN': [],
    'OTHER_DISCOUNT': [],
    'OTHER_BENEFIT': [],
    'OTHER_PROMO': [],
    'PROMOTION': [],
    'EVENT_PROMO': [],
    # 캐시백 계약 가맹점 — 어느 가맹점인지 공시가 밝히지 않아 승인내역으로 못 가른다.
    'CASHBACK_PARTNER': [],
    'CASHBACK_CONTRACT': [],
    'CASHBACK_EXCLUDED': [],
    'CASHBACK_EXCLUDE': [],
    # 수수료·부가서비스 이용료. 가맹점 결제가 아니다.
    'SERVICE_FEE': [],
    'SMS_FEE': [],
    'AUTO_SERVICE': [],
    'SMS_AUTO': [],
    'REFUND_FEE': [],
    'FEE_OVERSEAS': [],
    'INTL_FEE': [],
    # 축으로 못 가르는 것들. 브랜드 단위이거나(롯데백화점) 승인내역에 칸이 없다(충전·바우처).
    'LOTTE_DEPT': [],         # 브랜드다 — KAKAO_FRIENDS·KREAM_PURCHASE 와 같은 처리
    'MOBILE_TMONEY': [],      # 충전이라 교통 이용으로 안 잡힌다
    'VOUCHER': [],
    'VENDING_MACHINE': [],
    'VENDING': [],            # '자판기/터널 이용료/항공기내' — 승인내역에 그 칸이 없다
    'DIET_INSTALLMENT': [],   # 할부 개월 칸이 승인내역에 없다
    'ETC': [],                # 잡다한 열거 — 축으로 못 옮긴다
    'TOLL': [],               # 고속도로 통행료. **대중교통이 아니다**
    'ROAD_TOLL': [],
    # 'GS칼텍스 주유소 이용 금액' — 브랜드 한 곳이지만 업종은 주유가 맞다. 승인내역으로
    # 주유 업종은 가를 수 있으므로 축을 연결한다(브랜드까지는 못 갈라 조금 넓게 뺀다 —
    # 실적을 **적게** 잡는 방향이라 "채운 줄 알았는데 못 채웠다"가 안 난다).
    'GAS_STATION': ['주유'],
    # ── KB 387장 추출에서 나온 코드(2026-08-18). **대부분 빈 목록이다** — '할인 받은 이용건'
    #    ·'백화점 입점 매장'·'무승인전표'·'해외이용'은 승인내역에 그 칸이 없어 가를 수 없다.
    #    빈 목록은 "제외 대상이 없다"가 아니라 **"판정할 수 없다"** 는 뜻이다(머리말 참조).
    "ADDITIONAL_BENEFIT_USAGE": [],   # 추가 적립(쇼핑/여행)받은 이용금액(해당매출 전체)
    "AIRLINE": [],   # 항공기내 이용금액
    "AIR_INFLIGHT": [],   # 항공기내 이용
    "AIR_ONBOARD": [],   # 항공기 내 이용
    "AUTO_PAY_EXCLUDED": [],   # KB ALL 카드 자동납부(쇼핑멤버십/OTT/이동통신) 할인 적용 받은 전체 이용금
    "AUTO_PURCHASE": [],   # 자동차 구매
    "BENEFIT_USED": [],   # Daily, Monthly, Someday 서비스 받은 이용건(단, 고액 결제리워드
    "BIZ_FAVORITE_DISCOUNT": [],   # Biz Favorite 서비스 받은 이용 건
    "BUSINESS_SUPPORT_ADDITIONAL": [],   # 사업지원영역(주유,통신, 전자상거래) 추가 적립 받은 이용건
    "CHARGE": [],   # 포인트 충전금액
    "CONTRACT_PRICE": [],   # 기존 외상 고객과 주유소가 사전에 약정된 단가로 거래하는 경우
    "COUPON": [],   # 쿠폰 서비스 적용 매출
    "DEPARTMENT_STORE": [],   # 백화점/대형할인점 입점 점포
    "DEPT_STORE": [],   # 백화점 등 입점 매장
    "DISCOUNTED_ITEMS": [],   # 병원/약국 및 주유/대형마트 할인받은 이용건(해당매출 전체)
    "DISCOUNTED_SALES": [],   # 상품서비스로 할인 적용 받은 전체 매출
    "DISCOUNTED_TRANSACTIONS": [],   # 롯데마트 KB국민카드로 할인받은 이용건(해당 매출 전체)
    "DISCOUNT_AMOUNT": [],   # 건강보험료 자동납부 및 병원/약국업종 할인금액(해당 이용금액 전체)
    "DISCOUNT_SALES": [],   # AK KB국민카드 할인 매출 건(현장할인 제외)
    "DISCOUNT_STORE": [],   # 대형할인점 입점 점포
    "EDU_DISCOUNT_USAGE": [],   # 에듀 할인서비스 받은 이용건(해당 이용금액 전체)
    "FOREIGN": [],   # 해외이용금액
    "FOREIGN_PAYMENT": [],   # 해외이용금액
    "FUEL": [],   # 주유 서비스 받은 이용건(해당 매출 전체)
    "FUEL_DISCOUNT": [],   # SK주유할인 받은 이용금액
    "GOV_GRANT": [],   # 정부지원금
    "HOTEL": [],   # 호텔 입점 가맹점
    "HOTEL_DEPT": [],   # 호텔, 백화점, 대형마트, 철도/역사 등에 입점한 가맹점
    "INSURANCE": [],   # KB손해보험 이용금액
    "INTEREST": [],   # 이자
    "IN_STORE": [],   # 백화점, 마트, 역사 등 입점 매장
    "LF_MALL": [],   # LFmall 이용금액
    "LIFESTYLE_DISCOUNT": [],   # 생활할인 서비스로 할인 받은 이용건
    "LPG": ['주유'],   # LPG 충전소 결제 건
    "MART": [],   # 대형마트 입점 가맹점
    "MIN_AMOUNT": [],   # 이용금액 건당 1천원 미만
    "MIN_PAY": [],   # 이용금액 건당 1천원 미만
    "NEW_CAR": [],   # 신차구매 청구(환급) 할인 전표
    "NON_APPROVAL": [],   # 무승인전표(RF후불교통요금/자판기/터널이용료/항공기내 이용 등)
    "NON_KBPAY": [],   # KB Pay 외 다른 결제수단 이용건
    "NO_APPROVAL": [],   # 무승인 이용금액
    "OFFLINE_EXCLUDE": [],   # 백화점, 할인점 등 입점 매장
    "OFFLINE_STORE": [],   # 백화점/마트/역사/철도 등 입점 매장
    "ONLINE": [],   # 온라인 결제
    "ONLINE_PAY": [],   # 인터넷/모바일 앱을 통한 온라인 거래
    "OTHER_TELCO": ['통신'],   # 타 통신사 자동납부 건
    "POINT": [],   # 포인트리 결제금액
    "POP_CARD": [],   # POP카드 결제
    "PRE_CONTRACT": [],   # 기존 외상 고객과 주유소가 사전에 약정된 단가로 거래하는 경우
    "PURCHASE_PROXY": [],   # 구매중계를 통한 타 사이트 구매
    "REFUND": [],   # 취소금액
    "RENTAL_DISCOUNT": [],   # 교원 웰스 렌탈료 할인 서비스 받은 이용건
    "RESTAURANT_DISCOUNT": [],   # 패밀리레스토랑 할인 서비스 받은 이용건
    "SCHOOL_FEE": ['교육/육아'],   # 초/중/고등학교(국·공립) 학교납입금
    "SKT_AUTO": ['통신'],   # SKT 이동통신요금 자동납부금액
    "SPECIAL_DISCOUNT": [],   # 특별 서비스 할인 받은 이용건(해당 이용금액 전체)
    "SPECIAL_MILEAGE_TARGET": [],   # 특화마일 적립 대상 가맹점 이용금액
    "SSM": [],   # SSM(이마트에브리데이, 홈플러스 익스프레스, 롯데슈퍼 등)
    "STATION": [],   # 철도역사 입점 가맹점
    "STORE_IN_STORE": [],   # 백화점 및 대형할인점 입점 점포
    "TMONEY": [],   # 티머니 충전 및 이용금액
    "TRAVEL": [],   # 여행/항공권/티켓/도서
    "TUITION_SCHOOL": ['교육/육아'],   # 초·중·고등학교 학교 납입금 전체(수업료/교육비/현장학습비/급식비)
    "TUITION_UNI": ['교육/육아'],   # 대학(원) 등록금
    "UNAUTHORIZED": [],   # 무승인전표(자판기, 터널 통행료, 항공기내 이용 등)
    "UNIVERSITY_TUITION": ['교육/육아'],   # 대학(원)등록금
    'GS_CALTEX': ['주유'],       # 'GS칼텍스 이용금액' — 위와 같다
    # 브랜드 한 곳이라 승인내역에서 업종으로 못 가른다(이마트는 마트지만 '7대 가맹점'은
    # 이마트24·트레이더스 등이 섞여 있고, 넥슨은 우리 축에 게임이 없다).
    'E_MART_7': [],
    'NEXON_GAME': [],
    'AUTO_PAY_FEE': [],          # 수수료는 가맹점 결제가 아니다(FEE 와 같은 처리)
    'FEE_AUTO': [],              # 위와 같은 말을 코드명만 바꿔 뱉은 것
    'COMMUNICATION': ['통신'],    # '청구 할인 통신요금' — 승인내역으로 통신은 가를 수 있다
    # 자사 서비스·할부는 승인내역에 그 칸이 없다. 브랜드 단위이거나 할부 개월이 필요하다.
    'PRIVIA': [],                # 현대카드 자사 여행몰 — 가맹점명으로만 알 수 있다
    'PLATINUM': [],              # 등급 서비스라 결제가 아니다
    'LDF_INSTALLMENT': [],       # 장기할부 — 할부 개월 칸이 승인내역에 없다
    # '대형시설물 입점점포 및 임대매장(백화점/대형마트/면세점/공항 내 매장)'. 백화점 자체는
    # 축이 있지만 이건 **그 안에 든 매장**이라, 승인내역에 '어디 안에 있는지' 칸이 없다.
    # 백화점에 있는 스타벅스와 길가의 스타벅스가 똑같이 찍힌다.
    'LARGE_FACILITY': [],
    # '간편결제·키오스크로 결제해 가맹점명이 PG업체명으로 찍힌 건'. 위의 PAY_CHANNEL 과 같은
    # 뜻이고, 여기서는 **판정 불가가 아니라 판정할 필요가 없다** — 가맹점명이 PG명이면 업종을
    # 모르므로 그 결제는 애초에 혜택 매칭에 들어가지 못한다. 결과가 같으니 빈 목록이 정확하다.
    'PAY_CHANNEL_EXCLUSION': [],
    # 승인내역으로 판정 가능한 것 셋.
    'TAXI': ['택시'],
    'EXPRESS_BUS': ['대중교통'],
    'TRANSPORT': ['대중교통'],   # '기차/고속버스'
    # '주유업종 외'는 부정 조건이다. 현재 축에서 주유만 남기고 나머지를 실적에서 뺀다.
    'NON_GAS': [
        '온라인쇼핑', '통신', '여행/항공', '대중교통', '카페/디저트', '공과금/렌탈',
        '쇼핑', '편의점', '마트', '외식', '병원/약국', '디지털구독', '영화/문화',
        '뷰티', '택시', '백화점/면세점', '교육/육아', '스포츠/레저', '애완', '혜택축없음',
    ],
}

BENEFIT_KIND = {
    '할인': 'DISCOUNT',
    '적립': 'POINT',
    '무이자할부': 'INSTALLMENT_FREE',
}

ANNUAL_FEE_SCOPE = {
    '국내전용': 'DOMESTIC',
    '해외겸용': 'GLOBAL',
    '국내외겸용': 'GLOBAL',
    '해외겸용(VISA)': 'GLOBAL',
    '모바일 단독카드': 'GLOBAL',
    '모바일단독': 'GLOBAL',
    '모바일 단독': 'GLOBAL',
    # 모바일 단독카드도 국내전용/해외겸용이 갈린다. 뒤에 붙은 쪽을 따른다.
    '모바일 단독 국내전용': 'DOMESTIC',
    '모바일 단독 해외겸용': 'GLOBAL',
    # 괄호 안 국제브랜드는 구분에 영향이 없다 — 국내전용이냐 해외겸용이냐만 본다.
    # B군 가이드북에서 브랜드를 병기한 표기가 여럿 나왔다(2026-08-14).
    '국내외겸용(VISA)': 'GLOBAL',
    '국내외겸용(AMEX/UnionPay)': 'GLOBAL',
    '국내외겸용(VISA/AMEX)': 'GLOBAL',
    '국내전용(모바일단독)': 'DOMESTIC',
    '해외겸용(모바일단독)': 'GLOBAL',
    '국내전용(모바일단독)': 'DOMESTIC',
    '해외겸용(모바일단독)': 'GLOBAL',
    '가족카드': 'GLOBAL',
}

# 원천의 복합 국제브랜드 표기를 DB VARCHAR(20)에 맞는 검토된 표시명으로 줄인다.
# 전체 원문은 cards/*.json에 그대로 남는다.
# 구분을 다르게 적은 것들. 뜻이 같아 앞머리 규칙 전에 한 번 접는다.
ANNUAL_FEE_SCOPE_ALIAS = {
    '모바일단독카드': '모바일 단독카드',
    'K-WORLD(JCB타입)': '해외겸용',
    '국내외전용': '국내외겸용',
}

ANNUAL_FEE_BRAND = {
    'Mastercard/K-World(JCB)': 'Mastercard/K-World',
    # 표의 브랜드 칸이 20자다. 한 줄에 브랜드를 둘 적는 공시가 있어 등급어(World·Infinite)를
    # 뗀다 — 위 (JCB) 를 뗀 것과 같은 처리다. **연회비 금액은 그대로다.**
    # 20자 자체를 넓히려면 마이그레이션이 하나 더 필요하다(규칙 3: 적용된 것은 못 고친다).
    'MasterCard World/VISA Infinite': 'MasterCard/VISA',
}


# 혜택 방식은 표의 enum 이라 **아는 값 셋뿐**이다(DISCOUNT_POINT·MILEAGE·PREMIUM).
# 모델이 그 밖의 말을 뱉으면(실제로 `POINT` 가 왔다, 2026-08-14 스타벅스 현대카드) 적재가
# 기동에서 통째로 터진다 — 카탈로그는 만들어졌는데 앱이 안 뜬다. 여기서 접어 준다.
BENEFIT_STYLE = {
    'DISCOUNT_POINT': 'DISCOUNT_POINT',
    'MILEAGE': 'MILEAGE',
    'PREMIUM': 'PREMIUM',
    'POINT': 'DISCOUNT_POINT',      # 적립형은 할인·적립 묶음에 든다
    'POINT_POINT': 'DISCOUNT_POINT',  # 같은 말을 두 번 붙여 뱉은 것
    'DISCOUNT': 'DISCOUNT_POINT',
}


def benefit_style(card):
    raw = card.get('benefit_style') or 'DISCOUNT_POINT'
    style = BENEFIT_STYLE.get(raw)
    if style is None:
        # 조용히 기본값으로 떨어뜨리지 않는다 — 새 말이 생긴 것을 사람이 봐야 한다.
        sys.exit(f"[{card.get('name')}] 모르는 혜택 방식: '{raw}'\n"
                 f"  → build_catalog.py 의 BENEFIT_STYLE 에 사람이 검토해서 더한다.")
    return style


def iso_date(value, field, problems):
    """`YYYY-MM-DD` 가 아니면 버리고 사유를 남긴다.

    표의 칸이 DATE 라 형식이 어긋난 값은 **적재가 기동에서 터진다** — 카탈로그는
    만들어졌는데 앱이 안 뜬다. 여기서 걸러 그 카드만 참고 모드로 둔다.
    """
    if value in (None, ''):
        return None
    text = str(value)
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', text):
        problems.append(f'{field} 날짜 형식이 아니다({text})')
        return None
    return text


def load_axes():
    """카드혜택 축의 정본 — industry-mid.json 이 이미 갖고 있다. 여기서 새로 짓지 않는다."""
    with open(INDUSTRY, encoding='utf-8') as f:
        return set(json.load(f)['cardAxisByIndustry'].values())


def scope_of(raw):
    """연회비 구분 → DOMESTIC/GLOBAL. 못 정하면 None.

    괄호 안은 국제브랜드라 구분에 영향이 없다('국내외겸용(AMEX)' = '국내외겸용').
    카드사마다 표기가 갈려 사전에 한 벌씩 적기보다 **앞머리로 읽는 편**이 오래 간다.
    """
    text = ANNUAL_FEE_SCOPE_ALIAS.get(raw, raw)
    if text in ANNUAL_FEE_SCOPE:
        return ANNUAL_FEE_SCOPE[text]
    head = text.split('(')[0].strip()
    if head in ANNUAL_FEE_SCOPE:
        return ANNUAL_FEE_SCOPE[head]
    if head.startswith('국내전용'):
        return 'DOMESTIC'
    if head.startswith(('국내외', '해외')):
        return 'GLOBAL'
    return None


def annual_fees(card, problems):
    out = []
    for fee in card.get('annual_fee', []):
        if fee['scope'] == '가족카드':
            problems.append('가족카드 연회비를 본인카드 연회비와 분리할 수 없음')
        scope = scope_of(fee['scope'])
        if scope is None:
            # **지어내지 않는다.** 공시가 국내전용인지 해외겸용인지 안 밝힌 표기가 있다
            # ('일반카드'·'본인회원'·'제휴연회비'). 빌드를 세우는 대신 참고 모드로 두어
            # 그 카드의 숫자만 감춘다 — 한 장 때문에 전체 적재가 막히면 안 된다.
            problems.append(f"연회비 구분을 모른다({fee['scope']})")
            continue
        base, affiliate, total = fee.get('base'), fee.get('affiliate'), fee['total']
        # 검산은 여기서 한다. DB 에 CHECK 로 걸면 총액만 적는 공시가 아예 못 들어온다.
        if base is not None and affiliate is not None and base + affiliate != total:
            problems.append(f'연회비 합 불일치({fee["scope"]}: {base}+{affiliate}≠{total})')
        # 브랜드는 **표시용 이름**이라 칸에 맞춰 자른다. 자른 것은 요약에 찍는다.
        brand = fit_column(ANNUAL_FEE_BRAND.get(fee['brand'], fee['brand']), 20, TRUNCATED)
        out.append({'scope': scope, 'brand': brand,
                    'total': total, 'base': base, 'affiliate': affiliate})
    return out


def tiers_of(card):
    """실적 구간. 개수가 카드마다 다르므로(2~4단) 번호를 붙여 행으로 편다."""
    perf = card.get('performance') or {}
    return [{'tierNo': i + 1, 'thresholdKrw': krw}
            for i, krw in enumerate(perf.get('tiers', []))]


def exclusions_of(card, problems):
    """실적 제외와 혜택 제외를 한 목록으로 — 축(axis)만 다르다."""
    perf = card.get('performance') or {}
    out = []
    seen = {}
    for axis, rows in (('PERFORMANCE', perf.get('excluded', [])),
                       ('BENEFIT', card.get('benefit_excluded', []))):
        for row in rows:
            key = (axis, row['code'])
            if key in seen:
                current = out[seen[key]]
                if row['label'] not in current['label']:
                    current['label'] += f" / {row['label']}"
                continue
            if row['code'] not in EXCLUSION_CODE_TO_AXES:
                sys.exit(f"[{card['name']}] 축을 모르는 제외 코드: '{row['code']}'\n"
                         f"  → build_catalog.py 의 EXCLUSION_CODE_TO_AXES 에 사람이 검토해서 더한다.\n"
                         f"     승인내역으로 판정할 수 없으면 빈 목록으로 적는다 — 빠뜨린 것과 구분된다.")
            seen[key] = len(out)
            out.append({'axis': axis, 'code': row['code'], 'label': row['label']})

    count = len(perf.get('excluded', []))
    if count < MIN_PERFORMANCE_EXCLUSIONS:
        problems.append(f'실적 제외 {count}개(<{MIN_PERFORMANCE_EXCLUSIONS}) — 지면 일부만 읽었을 수 있다')
    return out


def targets_of(benefit, card_name, axes):
    """혜택 대상 — 브랜드·축·서술 셋으로 편다.

    채널·제외장소는 묶음 단위에 붙어 있는데 **행마다 되풀이해서** 넣는다. 이 표를 읽는 쪽은
    브랜드 하나를 찾아 그 한 행에서 채널까지 받아야 하기 때문이다(V36 머리말).
    """
    out = []
    # (묶음, 갈래, 값) 이 표의 UNIQUE 다. **축으로 접으면 실제로 겹친다** —
    # '시내버스'와 '지하철'이 둘 다 '대중교통' 한 축이 된다(KaPick 생픽 실측).
    # 여기서 안 접으면 적재가 중복키로 죽는다.
    seen = set()
    for group in benefit.get('targets', []):
        common = {
            # 표의 칸이 40자다(V36 `target_group`, UNIQUE KEY 의 일부). 넘치면 적재가
            # 기동에서 터진다 — 카탈로그는 만들어졌는데 앱이 안 뜬다(2026-08-14 실측:
            # 'Travel, Shopping, Gourmet, Lifestyle, Leisure' 45자).
            # **여기는 표시용 이름이라** 잘라도 판정에 영향이 없다. 칸을 넓히려면
            # 마이그레이션이 하나 더 필요하다(규칙 3: 적용된 것은 못 고친다).
            'targetGroup': fit_column(group.get('category', ''), 40, TRUNCATED),
            'channel': ', '.join(group['channel']) if group.get('channel') else None,
            'excludePlace': ', '.join(group['exclude_place']) if group.get('exclude_place') else None,
            'note': group.get('note'),
        }
        def add(kind, value):
            key = (common['targetGroup'], kind, value)
            if key in seen:
                return
            seen.add(key)
            out.append({**common, 'kind': kind, 'value': value})

        for brand in group.get('brands', []):
            add('BRAND', brand)
        for industry in group.get('industries', []):
            axis = INDUSTRY_TO_AXIS.get(industry)
            if axis is None:
                sys.exit(f"[{card_name}] 축을 모르는 업종 표현: '{industry}'\n"
                         f"  → build_catalog.py 의 INDUSTRY_TO_AXIS 에 사람이 검토해서 더한다.")
            if axis not in axes:
                sys.exit(f"[{card_name}] '{axis}' 는 카드혜택 축이 아니다(industry-mid.json 기준)")
            add('AXIS', axis)
        # 브랜드도 축도 아닌 서술('해외 가맹점 및 해외 직접 구매'). 매칭에 못 쓰고 표시만 한다.
        if group.get('scope'):
            add('SCOPE', group['scope'])
        elif not group.get('brands') and not group.get('industries'):
            # 브랜드도 업종도 없을 때만 category 를 본다. 브랜드가 적힌 자리에서 category 를
            # 접으면 안 된다 — 공시의 'OTT' 는 업종이 아니라 **그 카드가 제휴한 목록**이고
            # (BC New KT family 는 디즈니+ 가 없고 BC 바로 K-패스 는 있다) 축으로 접는 순간
            # 제휴 안 된 결제까지 할인된 것으로 세게 된다.
            category = (group.get('category') or '').strip()
            axis = category if category in axes else CATEGORY_TO_AXIS.get(category)
            if category in ALL_MERCHANT:
                add('ALL', category)
            elif axis and category not in NOT_AXIS:
                if axis not in axes:
                    sys.exit(f"[{card_name}] '{axis}' 는 카드혜택 축이 아니다"
                             f"(axis-aliases.json 의 '{category}')")
                add('AXIS', axis)
            else:
                # 접을 말이 없거나(notAxis) 표에 아직 없는 말. 표시만 하고 계산에서 뺀다.
                # 업종과 달리 여기서 빌드를 세우지 않는다 — category 는 모델이 매번 지어내는
                # 자유 문자열이라(153장에서 289종) 세우면 카드가 늘 때마다 멈춘다.
                # 안 접으면 그 혜택이 계산에서 빠질 뿐 하한 방향으로만 틀린다.
                UNMAPPED_CATEGORIES[category] += 1
                add('SCOPE', category)
    return out


def caps_of(benefit, thresholds, card_name, problems):
    """구간별 월한도. 키가 tiers 에 없으면 DB 가 거부하므로 여기서 먼저 잡는다."""
    by_tier = benefit.get('monthly_cap_by_tier')
    if not by_tier:
        return []                      # 한도 없음. 0원 한도와 다르다.
    out = []
    previous = None
    for key in sorted(by_tier, key=int):
        krw = int(key)
        if krw not in thresholds:
            problems.append(f'한도의 구간 {krw:,}원이 실적 구간에 없다({benefit["group"]})')
            continue
        cap = by_tier[key]
        if previous is not None and cap < previous:
            problems.append(f'구간이 오르는데 한도가 내려간다({benefit["group"]}: {previous}→{cap})')
        previous = cap
        out.append({'thresholdKrw': krw, 'capKrw': cap})
    return out


# 적립 단위가 원이 아닌 것들. 조건문에 이 말이 있고 요율·정액이 없으면 그 단위로 본다.
#
# **왜 조건문을 뒤지나.** 추출 스키마에 `benefit_unit` 칸이 있는데 실제로는 한 장도
# 채워지지 않는다(153장 전부 빈 값, 2026-08-14 실측). 그래서 기본값 '원'이 그대로 적혀,
# **마일리지 적립에도 카탈로그가 "단위: 원"이라고 말하고 있었다.** 사실이 아니다.
# 근본 해결은 추출 프롬프트가 이 칸을 채우게 하는 것이고, 여기는 그때까지의 자리다.
NON_KRW_UNITS = ('마일리지', '마일')


def benefit_unit(raw, card_unit):
    """이 혜택의 적립 단위. 카드 단위가 적혀 있으면 그것이 우선이다."""
    named = card_unit.get('name')
    if named:
        return named
    if raw.get('rate_percent') is not None or raw.get('amount_krw') is not None:
        return '원'          # 요율·정액이 있으면 원으로 셀 수 있다
    text = ' '.join(str(part) for part in (raw.get('conditions') or []))
    for word in NON_KRW_UNITS:
        if word in text:
            return '마일리지'
    return '원'


def benefits_of(card, thresholds, axes, problems):
    out = []
    unit = card.get('benefit_unit') or {}
    for i, raw in enumerate(card.get('benefits', [])):
        kind = BENEFIT_KIND.get(raw['kind'])
        if kind is None:
            sys.exit(f"[{card['name']}] 모르는 혜택 갈래: {raw['kind']}")

        targets = targets_of(raw, card['name'], axes)
        matchable = [t for t in targets if t['kind'] in ('BRAND', 'AXIS', 'ALL')]
        rate = raw.get('rate_percent')

        # ── 셈할 수 있는가. 못 세는 이유를 남긴다(화면에는 혜택이 그대로 보인다).
        #
        # 사유를 **정확히** 적는 것이 이 자리의 일이다. '요율·정액이 없다'는 추출이 숫자를
        # 놓쳤다는 뜻으로 읽히는데, 마일리지 적립은 놓친 게 아니라 **원으로 옮길 수가 없다**.
        # 둘을 한 사유로 묶으면 "추출을 고치면 되겠네"라고 잘못 읽게 된다(2026-08-14).
        row_unit = benefit_unit(raw, unit)
        reason = None
        if kind in ('INSTALLMENT_FREE', 'NON_MONETARY'):
            reason = '금액 환산 불가'
        elif rate is None and raw.get('amount_krw') is None:
            reason = f'{row_unit}는 원으로 환산할 수 없다' if row_unit != '원' else '요율·정액이 없다'
        elif not matchable:
            reason = '승인내역에서 고를 대상이 없다'

        scope = 'BRAND' if any(t['kind'] == 'BRAND' for t in matchable) else \
                'AXIS' if any(t['kind'] == 'AXIS' for t in matchable) else 'ALL'

        out.append({
            'groupName': raw['group'],
            'kind': kind,
            'settle': raw.get('settle'),
            'scope': scope,
            'ratePercent': rate,
            'rateConditional': raw.get('rate_conditional'),
            'amountKrw': raw.get('amount_krw'),
            'minAmountPerTxn': raw.get('min_amount'),
            'requiresTierKrw': raw.get('requires_tier'),
            'combinedCapGroup': raw.get('combined_cap_group'),
            'unit': row_unit,
            'unitThirdParty': unit.get('third_party'),
            # 초안이 '등'으로 끝나는 목록을 표시하기 전까지는 닫힌 것으로 본다.
            'targetsComplete': raw.get('targets_complete', True),
            'payChannel': raw.get('pay_channel'),
            'countable': reason is None,
            'countableNote': reason,
            'isHeadline': raw.get('is_headline', False),
            'conditionsText': '\n'.join(raw['conditions']) if raw.get('conditions') else None,
            'exclusiveWith': ', '.join(raw['exclusive_with']) if raw.get('exclusive_with') else None,
            'sortNo': i,
            'caps': caps_of(raw, thresholds, card['name'], problems),
            'targets': targets,
        })

    # 비금전 혜택(라운지·발렛·등급 서비스)도 혜택 행이다. 금액 환산이 안 되니 계산에서 빼되,
    # 표시는 한다 — 이것 때문에 카드를 고르는 사람이 있는데 목록에서 통째로 사라지면 안 된다.
    for i, text in enumerate(card.get('non_monetary', [])):
        out.append({
            'groupName': f'비금전 혜택 {i + 1}' if len(card['non_monetary']) > 1 else '비금전 혜택',
            'kind': 'NON_MONETARY', 'settle': None, 'scope': 'ALL',
            'ratePercent': None, 'rateConditional': None, 'amountKrw': None,
            'minAmountPerTxn': None, 'requiresTierKrw': None, 'combinedCapGroup': None,
            'unit': '원', 'unitThirdParty': None, 'targetsComplete': True, 'payChannel': None,
            'countable': False, 'countableNote': '금액 환산 불가',
            'isHeadline': False, 'conditionsText': text, 'exclusiveWith': None,
            'sortNo': len(out), 'caps': [], 'targets': [],
        })

    if not any(b['countable'] for b in out):
        problems.append('셈할 수 있는 혜택이 하나도 없다')
    return out


def combined_caps_of(card, thresholds, problems):
    out = []
    for raw in card.get('combined_caps', []):
        caps = []
        for key in sorted(raw.get('cap_by_tier', {}), key=int):
            krw = int(key)
            if krw not in thresholds:
                problems.append(f'통합한도의 구간 {krw:,}원이 실적 구간에 없다({raw["group"]})')
                continue
            caps.append({'thresholdKrw': krw, 'capKrw': raw['cap_by_tier'][key]})
        out.append({'groupName': raw['group'], 'caps': caps})
    return out


def performance_of(card):
    perf = card.get('performance') or {}
    if not perf:
        return None
    exception = perf.get('basis_exceptions') or {}
    grace = perf.get('new_member_grace') or {}
    return {
        'periodLabel': perf.get('period', ''),
        'basis': 'APPROVAL' if perf.get('basis') == '승인일' else 'PURCHASE',
        'basisException': 'PURCHASE' if exception.get('basis') == '매입일' else None,
        'basisExceptionTargets': ', '.join(exception.get('applies_to', [])) or None,
        'includes': ', '.join(perf.get('includes', [])) or None,
        # 공시가 말하지 않은 것은 null 로 둔다. false 로 적으면 그 자리가 사실이 돼 버린다.
        'includesFamilyCard': perf.get('includes_family_card'),
        'newMemberGraceUntil': grace.get('until'),
        'newMemberAppliedTierKrw': grace.get('applied_tier'),
        'newMemberNote': grace.get('note'),
    }


def build_card(card, axes, extraction):
    problems = []
    tiers = tiers_of(card)
    thresholds = {t['thresholdKrw'] for t in tiers}

    out = {
        'issuer': card['issuer'],
        'name': card['name'],
        'productId': card['product_id'],
        'extraction': extraction,
        # 초안 3장은 전부 신용카드다 — 카드론·현금서비스가 실적 제외에 있는 것이 근거다.
        'cardType': card.get('card_type') or 'CREDIT',
        'status': 'ACTIVE' if card.get('status') == 'active' else 'STOPPED',
        'benefitStyle': benefit_style(card),
        'policyCard': card.get('policy_card', False),
        # 공시가 후불교통을 늘 적지는 않는다. 모르면 null 이다.
        'hasTransit': card.get('has_transit'),
        'asOf': iso_date(card.get('as_of'), 'as_of', problems),
        'reviewNo': card.get('review_no'),
        'postedAt': iso_date(card.get('posted_at'), 'posted_at', problems),
        'sourceUrl': card.get('source_url'),
        'annualFeeNote': '\n'.join(card['annual_fee_notes']) if card.get('annual_fee_notes') else None,
        # 혜택 전체에 걸리는 단서 둘을 한 칸에 모은다 — 계산에는 못 넣지만 숨기지도 않는다.
        'benefitNote': '\n'.join(card.get('benefit_notes', []) + card.get('benefit_conditions', [])) or None,
        'annualFees': annual_fees(card, problems),
        'performance': performance_of(card),
        'tiers': tiers,
        'exclusions': exclusions_of(card, problems),
        'benefits': benefits_of(card, thresholds, axes, problems),
        'combinedCaps': combined_caps_of(card, thresholds, problems),
    }

    # 기준일이 없으면 화면에 "이 시점 공시 기준"을 못 쓴다 — 그것만으로 참고 모드다.
    if not out['asOf']:
        problems.append('as_of(심의필 날짜) 없음')

    if extraction == 'LLM':
        check = card.get('_extraction_check') or {}
        if check.get('numeric_consensus') is not True:
            mismatches = check.get('mismatches') or []
            problems.append(f'LLM 이중 추출 숫자 불일치({len(mismatches)}곳)')
        external = check.get('external_max_benefit') or {}
        if external.get('status') == 'MISMATCH':
            internal = external.get('internal_krw')
            outside = external.get('external_krw')
            problems.append(f'외부 월 최대 혜택 불일치({internal}≠{outside})')

    out['grade'] = 'REFERENCE' if problems else 'PRECISE'
    out['gradeReason'] = ' · '.join(problems)[:200] or None
    return out, problems


def not_for_individuals(name):
    """개인이 가입할 수 없거나 특정 결제처에서만 쓰는 카드면 사유를, 아니면 None.

    후보 선정과 **같은 규칙**을 쓴다(`select_youth_cards.EXCLUSION_RULES`). 규칙을 여기에
    다시 적으면 두 곳이 갈라지고, 갈라지면 어느 쪽이 정본인지 알 수 없다.

    왜 여기서도 거르나: 후보 선정을 거치지 않고 카드사 공시를 통째로 훑어 추출한 묶음이
    섞여 있었다(BC 29장). 그래서 1992년 주유전용카드·법인카드가 카탈로그 후보에 들어왔고,
    셈할 수 있는 혜택이 있을 리 없어 전부 참고 모드로 떨어졌다. 통과율의 분모만 부풀린다.
    """
    for reason, pattern in select_youth_cards.EXCLUSION_RULES:
        if pattern.search(name or ''):
            return reason
    return None


def load_sources():
    """LLM 원천을 먼저 읽고 사람이 검수한 3장으로 같은 product_id를 덮는다."""
    merged = {}
    for path in sorted(glob.glob(os.path.join(CARDS, '*.json'))):
        with open(path, encoding='utf-8') as source:
            raw = json.load(source)
        product_id = raw.get('product_id')
        if not product_id:
            sys.exit(f'{os.path.relpath(path, ROOT)}: product_id가 없다')
        if product_id in merged:
            sys.exit(f'LLM 카드 product_id가 겹친다: {product_id}')
        reason = not_for_individuals(raw.get('name'))
        if reason:
            # 조용히 줄이지 않는다 — 무엇을 왜 뺐는지 남겨야 "다 담았다"로 읽히지 않는다.
            EXCLUDED.append((raw.get('name'), reason))
            continue
        merged[product_id] = (raw, 'LLM')

    with open(DRAFT, encoding='utf-8') as source:
        draft = json.load(source)
    for raw in draft['cards']:
        merged[raw['product_id']] = (raw, 'HUMAN_VERIFIED')

    return [merged[product_id] for product_id in sorted(merged)]


def split_reasons(problems):
    for problem in problems:
        # 금액·그룹 같은 상세를 떼고 원인 유형으로 묶는다.
        yield problem.split('(')[0].strip()


def write_report(built_rows, problems_by_card):
    precise = sum(card['grade'] == 'PRECISE' for card in built_rows)
    total = len(built_rows)
    rate = precise / total * 100 if total else 0.0
    reasons = Counter(
        reason
        for card_problems in problems_by_card.values()
        for reason in split_reasons(card_problems)
    )
    reason_cards = defaultdict(list)
    for card_name, card_problems in problems_by_card.items():
        for reason in set(split_reasons(card_problems)):
            reason_cards[reason].append(card_name)

    schema_gaps = []
    external = Counter()
    external_details = []
    numeric_mismatches = []
    # 보고서는 병합에서 사람이 이긴 카드의 LLM 결과도 포함한다. 그래야 전체 추출의
    # 이중 대조·외부 대조·스키마 빈틈을 빠짐없이 평가할 수 있다.
    llm_report_rows = []
    for path in sorted(glob.glob(os.path.join(CARDS, '*.json'))):
        with open(path, encoding='utf-8') as source:
            llm_report_rows.append(json.load(source))
    for raw in llm_report_rows:
        for gap in raw.get('schema_gaps', []):
            schema_gaps.append((raw['name'], gap))
        check = raw.get('_extraction_check') or {}
        if check.get('numeric_consensus') is not True:
            numeric_mismatches.append((raw['name'], len(check.get('mismatches') or [])))
        maximum = check.get('external_max_benefit') or {}
        status = maximum.get('status', 'UNAVAILABLE')
        external[status] += 1
        external_details.append((raw['name'], status, maximum.get('internal_krw'),
                                 maximum.get('external_krw'), maximum.get('source_url')))

    added_industries = sorted(set(INDUSTRY_TO_AXIS) - BASELINE_INDUSTRIES)
    added_exclusions = sorted(set(EXCLUSION_CODE_TO_AXES) - BASELINE_EXCLUSION_CODES)
    lines = [
        '# 카드사 LLM 추출 보고서', '',
        '## 게이트 3 결과', '',
        f'- 전체: {total}장',
        f'- PRECISE: {precise}장',
        f'- REFERENCE: {total - precise}장',
        f'- 통과율: **{precise}/{total} ({rate:.1f}%)**', '',
        '### REFERENCE 원인', '',
    ]
    if reasons:
        for reason, count in reasons.most_common():
            names = ', '.join(sorted(reason_cards[reason]))
            lines.append(f'- {reason}: {count}장 — {names}')
    else:
        lines.append('- 없음')

    lines.extend(['', '## 이중 추출 숫자 대조', ''])
    if numeric_mismatches:
        lines.extend(f'- {name}: {count}곳 불일치' for name, count in numeric_mismatches)
    else:
        lines.append('- LLM 카드 전부 일치')

    lines.extend(['', '## 외부 월 최대 혜택 대조', ''])
    for status in ('MATCH', 'MISMATCH', 'UNAVAILABLE', 'NON_COMPARABLE'):
        lines.append(f'- {status}: {external[status]}장')
    lines.append('')
    for name, status, internal, outside, source_url in external_details:
        source = f' · {source_url}' if source_url else ''
        lines.append(f'- {name}: {status} · 내부 {internal}원 · 외부 {outside}원{source}')

    lines.extend([
        '', '## 어휘 표 변경', '',
        f'- `INDUSTRY_TO_AXIS` 추가: {len(added_industries)}개'
        + (f' — {", ".join(added_industries)}' if added_industries else ''),
        f'- `EXCLUSION_CODE_TO_AXES` 추가: {len(added_exclusions)}개'
        + (f' — {", ".join(added_exclusions)}' if added_exclusions else ''),
        '', '## 현재 스키마로 표현하지 못한 조건', '',
    ])
    if schema_gaps:
        lines.extend(f'- {name}: {gap}' for name, gap in schema_gaps)
    else:
        lines.append('- 없음')

    with open(REPORT, 'w', encoding='utf-8') as report:
        report.write('\n'.join(lines) + '\n')


def main():
    axes = load_axes()
    source_rows = load_sources()
    cards, flagged = [], 0
    problems_by_card = {}
    for raw, extraction in source_rows:
        card, problems = build_card(raw, axes, extraction)
        cards.append(card)
        if problems:
            flagged += 1
            problems_by_card[card['name']] = problems
            print(f"  ⚠ {card['name']} → 참고 모드: {card['gradeReason']}")
        skipped = [b for b in card['benefits'] if not b['countable']]
        if skipped:
            names = ', '.join(f"{b['groupName']}({b['countableNote']})" for b in skipped)
            print(f"    · 계산에서 빼는 혜택: {names}")

    out = {
        '_note': ('카드 상품 카탈로그 — scripts/collect-cards/build_catalog.py 가 '
                  'schema-draft.json 과 cards/*.json 에서 만든다. productId가 겹치면 '
                  '사람이 검수한 schema-draft.json이 이긴다. 표 아홉(V36)에 그대로 대응한다. '
                  '**실제 카드다**(마스터 원칙 5 재개정 2026-08-10) — 수집 시점 스냅샷이고 '
                  'asOf(심의필 날짜)를 화면에 반드시 병기한다.'),
        '_gradeNote': ('PRECISE 는 게이트 3(규칙 검산)을 통과한 것, REFERENCE 는 걸린 것이다. '
                       'REFERENCE 는 화면에 숫자를 보여주지 않는다 — 걸린 이유는 gradeReason 에 있다.'),
        '_countableNote': ('countable=false 는 혜택이 없다는 뜻이 아니라 **금액으로 셀 수 없다**는 '
                           '뜻이다(무이자할부·라운지·해외·간편결제 조건). 표시는 하고 절감액에서만 뺀다. '
                           '빼면 하한 방향으로 틀리므로 "채운 줄 알았는데 못 채웠다"가 안 난다.'),
        '_sourceNote': ('출처는 각 카드사 자사 상품공시정보(1차 원천)이고 원문 PDF 는 저장소 밖이다. '
                        '신청 링크·CTA 는 두지 않는다 — 넘으면 금소법상 중개업 등록 대상이 된다.'),
        '_exclusionAxesNote': ('실적 제외 코드 → 승인내역에서 뺄 수 있는 카드혜택 축. '
                              '**빈 목록은 "제외 대상이 없다"가 아니라 "승인내역으로 판정할 수 없다"** 이다 '
                              '— 현금서비스·카드론·수수료는 결제가 아니고, 상품권·무이자할부는 승인내역에 '
                              '그 칸이 없다. 못 빼면 실적이 과대 계산되지만 대신할 신호가 없다. '
                              '축 이름을 코드에 박지 않으려고 데이터로 둔다(원칙 4).'),
        'exclusionAxes': EXCLUSION_CODE_TO_AXES,
        'cards': cards,
    }
    path = os.path.join(BACKEND, 'card-catalog.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')

    write_report(cards, problems_by_card)

    benefits = sum(len(c['benefits']) for c in cards)
    countable = sum(1 for c in cards for b in c['benefits'] if b['countable'])
    targets = sum(len(b['targets']) for c in cards for b in c['benefits'])
    print(f"\n  {os.path.relpath(path, ROOT)}")
    print(f"  {os.path.relpath(REPORT, ROOT)}")
    print(f"  카드 {len(cards)}장 (정밀 {len(cards) - flagged} · 참고 {flagged}) · "
          f"혜택 {benefits}개(셈 가능 {countable}) · 대상 {targets}행")
    if TRUNCATED:
        print(f"  칸 길이(40자)에 맞춰 자른 대상 이름 {len(TRUNCATED)}종")
        for text, count in TRUNCATED.most_common(5):
            print(f"    · {text} ({len(text)}자, {count}회)")
    if EXCLUDED:
        by_reason = Counter(reason for _, reason in EXCLUDED)
        detail = ' · '.join(f"{reason} {count}장" for reason, count in by_reason.most_common())
        print(f"  개인 추천 대상이 아니라 뺀 카드 {len(EXCLUDED)}장 — {detail}")
        for card_name, reason in EXCLUDED:
            print(f"    · {card_name} ({reason})")
    if UNMAPPED_CATEGORIES:
        total = sum(UNMAPPED_CATEGORIES.values())
        print(f"\n  축으로 못 접은 대상 {total}행 ({len(UNMAPPED_CATEGORIES)}종) — 계산에서 빠졌다."
              f" 업종이면 axis-aliases.json 의 axisAliases 에, 아니면 notAxis 에 더한다.")
        for category, count in UNMAPPED_CATEGORIES.most_common(15):
            print(f"    {count:>3}행  {category}")


if __name__ == '__main__':
    main()
