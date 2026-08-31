"""김하영의 신한은행 입출금 통장과 그 거래를 만든다.

  python3 scripts/build-hayoung-account.py > _archive/persona/통장-insert.sql

## 생성기와 같은 모양으로 만든다

`AccountTxnGenerator` 가 만드는 다섯 갈래를 그대로 따른다.

    SALARY    월급. 펌뱅킹으로 들어온다
    CARD      **결제 한 건마다** 통장에 출금으로 찍힌다 — 카드값을 월 단위로 몰지 않는다
    TRANSFER  사람 이름으로 오가는 일회성 송금
    INTEREST  월 이자
    TAX       그 이자에 붙는 소득세(14%)와 지방소득세(1.4%)

**카드를 건별로 옮기는 이유**는 그것이 이 저장소의 방식이기 때문이다(실측 확인).
월말에 한 번 몰면 통장만 보고는 무엇에 썼는지 알 수 없다.

## 급여를 얼마로 두나

넉 달 소비가 월 250만이다. 급여가 그보다 낮으면 통장이 마이너스로 흘러 **데이터 자체가
말이 안 된다.** 마케팅 2년차 세후 300만으로 둔다 — 월 50만이 남고, 그 남는 돈이
9월 절약분과 합쳐져 목표 저축이 된다.
"""
import csv
import io
import os
import random
from collections import defaultdict
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
CSV = os.path.join(ROOT, '_archive', 'persona', '김하영-명세서.csv')

CI = '217f73e3d73b3db93eff1c3bb40e6da06f0618073175f859b9c35d3b3fd2976b'
CARD_NAME = '신한카드'
BANK, PRODUCT = '신한은행', '신한 주거래 미래설계통장'
ACCOUNT = '110-482-771903'
PAYER = '주식회사 크로스포인트'          # 근무처
OPENED = date(2026, 4, 20)              # 첫 결제(5/1)보다 앞서야 한다
SALARY, PAYDAY = 3_000_000, 25
INITIAL = 4_800_000
END = date(2026, 9, 30)
SEED = 20260501

CHANNELS = ['당행CD', '타행CD', '전자금융이체', '계좌대체', '타행MB', '타행IB', '제휴CD', 'FB이체', 'FBS']
SURNAMES = ['김', '이', '박', '최', '정', '강', '조', '윤', '장', '임', '한', '오', '서', '신', '권', '황']
GIVEN = ['서준', '지우', '하윤', '도윤', '서연', '지호', '수아', '예준', '하은', '지훈',
         '민서', '주원', '채원', '건우', '다은', '현우', '유진', '준서', '소율', '시우']

INCOME_TAX, LOCAL_TAX = 0.14, 0.014
RATE = 0.011                             # 입출금 통장 연이율


def rows():
    r = random.Random(SEED + 31)
    out = []

    # ── 카드 결제를 건별로 옮긴다 ──
    with io.open(CSV, encoding='utf-8') as f:
        for d, nm, amt, biz, ind in list(csv.reader(f))[1:]:
            y, m, dd = (int(x) for x in d.split('-'))
            t = datetime(y, m, dd, r.randint(8, 23), r.randint(0, 59))
            out.append((t, 'WITHDRAWAL', int(amt), nm, CARD_NAME, 'CARD'))

    # ── 월급 ──
    cur = date(OPENED.year, OPENED.month, 1)
    while cur <= END:
        pay = date(cur.year, cur.month, PAYDAY)
        if OPENED <= pay <= END:
            out.append((datetime(pay.year, pay.month, pay.day, 9, 0),
                        'DEPOSIT', SALARY, PAYER, '펌뱅킹', 'SALARY'))
        cur = (cur.replace(day=28) + timedelta(days=8)).replace(day=1)

    # ── 월세 ──
    #
    # **이게 빠지면 사람이 아니다.** 처음에 안 넣었더니 잔액이 1,093만원까지 불어, 매달
    # 50만원씩 저축하는 사람이 됐다 — 절약이 안 되어 고민인 사람의 통장이 그럴 수는 없다.
    # 봉천동 원룸 시세로 보증금 1,000 / 월 55만이다. 집주인에게 보내는 송금이라 TRANSFER 다.
    cur = date(OPENED.year, OPENED.month, 1)
    while cur <= END:
        pay = date(cur.year, cur.month, 5)
        if OPENED <= pay <= END:
            out.append((datetime(pay.year, pay.month, pay.day, 9, 30),
                        'WITHDRAWAL', 550000, '박정숙', '전자금융이체', 'TRANSFER'))
        cur = (cur.replace(day=28) + timedelta(days=8)).replace(day=1)

    # ── 사람 사이 송금 ──
    d = OPENED
    while d <= END:
        if r.random() < 0.16:
            who = r.choice(SURNAMES) + r.choice(GIVEN)
            amt = r.choice([5000, 8000, 10000, 12000, 15000, 20000, 25000, 30000, 50000])
            kind = 'WITHDRAWAL' if r.random() < 0.62 else 'DEPOSIT'
            out.append((datetime(d.year, d.month, d.day, r.randint(11, 22), r.randint(0, 59)),
                        kind, amt, who, r.choice(CHANNELS), 'TRANSFER'))
        d += timedelta(days=1)

    # ── 이자와 그에 붙는 세금 ──
    #
    # 잔액을 따라 붙는다. 많이 쓴 달은 잔액이 낮아 이자도 적다 — 생성기와 같은 성질이다.
    bal = INITIAL
    by_month = defaultdict(int)
    for t, kind, amt, *_ in out:
        by_month[(t.year, t.month)] += amt if kind == 'DEPOSIT' else -amt
    cur = date(OPENED.year, OPENED.month, 1)
    while cur <= END:
        bal += by_month[(cur.year, cur.month)]
        day = min(25, 28)
        pay = date(cur.year, cur.month, day)
        if OPENED <= pay <= END and bal > 0:
            gross = int(bal * RATE / 12)
            if gross > 0:
                out.append((datetime(pay.year, pay.month, pay.day, 0, 5),
                            'DEPOSIT', gross, '이자입금', f'{BANK}본부', 'INTEREST'))
                it, lt = int(gross * INCOME_TAX), int(gross * LOCAL_TAX)
                if it: out.append((datetime(pay.year, pay.month, pay.day, 0, 6),
                                   'WITHDRAWAL', it, '결산소득세', f'{BANK}본부', 'TAX'))
                if lt: out.append((datetime(pay.year, pay.month, pay.day, 0, 7),
                                   'WITHDRAWAL', lt, '결산지방세', f'{BANK}본부', 'TAX'))
        cur = (cur.replace(day=28) + timedelta(days=8)).replace(day=1)

    out.sort(key=lambda x: (x[0], x[3]))
    return out


def main():
    esc = lambda s: s.replace('\\', '\\\\').replace("'", "''")
    tx = rows()
    print('-- 김하영 신한은행 통장과 거래.')
    print(f'-- 개설 {OPENED} · 급여 {SALARY:,}원({PAYDAY}일) · 초기잔액 {INITIAL:,}원')
    print(f'-- 거래 {len(tx):,}건 ({OPENED} ~ {END})')
    print(f"""INSERT INTO mydata_account
  (mydata_account_id, mydata_user_id, mydata_account_bank, mydata_account_product,
   mydata_account_salary_payer, mydata_account_opened_date, mydata_account_salary,
   mydata_account_payday, mydata_account_initial_balance)
VALUES ('{ACCOUNT}','{CI}','{BANK}','{PRODUCT}','{esc(PAYER)}','{OPENED}',{SALARY},{PAYDAY},{INITIAL})
ON DUPLICATE KEY UPDATE mydata_account_bank=VALUES(mydata_account_bank);""")
    print("""
INSERT INTO mydata_account_txn
  (mydata_account_id, mydata_account_txn_date, mydata_account_txn_type,
   mydata_account_txn_amount, mydata_account_txn_description,
   mydata_account_txn_note, mydata_account_txn_source)
VALUES""")
    vals = [f"('{ACCOUNT}','{t:%Y-%m-%d %H:%M:%S}','{k}',{a},'{esc(dsc)}','{esc(note)}','{src}')"
            for t, k, a, dsc, note, src in tx]
    print(',\n'.join(vals) + ';')

    # 요약은 표준오류로 — 표준출력은 SQL 만 나가야 한다
    import sys
    from collections import Counter
    c = Counter(x[5] for x in tx)
    print(f'  거래 {len(tx):,}건  ' + ' · '.join(f'{k} {v}' for k, v in c.most_common()), file=sys.stderr)
    bal = INITIAL
    for t, k, a, *_ in tx:
        bal += a if k == 'DEPOSIT' else -a
    print(f'  최종 잔액 {bal:,}원', file=sys.stderr)


if __name__ == '__main__':
    main()
