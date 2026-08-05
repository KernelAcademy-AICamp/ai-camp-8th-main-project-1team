"""적재된 계약 결제의 **상호와 요금제 짝**을 카탈로그 규칙으로 검사한다.

  실행:  python3 scripts/verify-contract-pairs.py     (verify-regen.sh 가 부른다)
  종료:  0 정상 · 1 어긋난 조합 발견

## 왜 SQL 로는 못 하나

명세서에 찍히는 상호는 **표기 변형**이다 — 넷플릭스가 `NETFLIX.COM`·`넷플릭스서비시스코리아`
로도 찍히고, 멜론은 운영사인 `카카오` 로 찍힌다(`BrandEntry.forms`). 그래서
`요금제가 상호로 시작하는가` 같은 문자열 비교는 **정상 데이터를 무더기로 오탐한다** —
실제로 11,163건이 그렇게 잡혔다(2026-08-04).

판정 규칙은 `BrandEntry.canSell` 하나뿐이다(`serves` 접두사 일치). 그러니 검사도 그 규칙을
써야 한다. 카탈로그를 읽어 `표기 → 그 사업자가 파는 품목 접두사` 를 만든 뒤 대조한다.

## 무엇을 잡나

  애플티비플러스        │ 넷플릭스 광고형     ← 상호와 요금제가 남남
  서울시상수도사업본부  │ 정수기렌탈          ← 파는 사업자가 아니다

`serves` 가 비어 있으면 `canSell` 이 무조건 참이라 이 검사가 통째로 무력해진다.
그래서 **비어 있는 것 자체를 먼저 실패로 본다.**
"""
import io
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
CAT = os.path.join(ROOT, 'backend-mydata', 'src', 'main', 'resources', 'generation', 'catalog')
CATEGORIES = ['스트리밍', '통신비', '공과금']

MYSQL = os.environ.get('MYSQL_BIN', os.path.expanduser(
    '~/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql'))
DB = os.environ.get('MYDATA_DB_NAME', 'finntech_mydata')
USER = os.environ.get('DB_USER', 'finntech')
PW = os.environ.get('DB_PASSWORD', 'finntech')
HOST = os.environ.get('DB_HOST', '127.0.0.1')
PORT = os.environ.get('DB_PORT', '3306')

GREEN, RED, OFF = '\033[32m', '\033[31m', '\033[0m'


def query(sql):
    out = subprocess.run([MYSQL, '--no-defaults', f'-u{USER}', f'-p{PW}', '--protocol=TCP',
                          '-h', HOST, '-P', PORT, DB, '-N', '-B', '-e', sql],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print(out.stderr.strip(), file=sys.stderr)
        sys.exit(1)
    return [ln.split('\t') for ln in out.stdout.strip().split('\n') if ln.strip()]


def main():
    brands = json.load(io.open(os.path.join(CAT, 'merchants_brand.json'), encoding='utf-8'))['byCategory2']
    fail = 0

    for cat in CATEGORIES:
        entries = brands.get(cat, [])
        if not entries:
            print(f'  {RED}✗{OFF} {cat} — 카탈로그에 상호가 없다')
            fail += 1
            continue

        # serves 가 비면 canSell 이 무조건 참이라 짝맞춤이 통째로 죽는다. 그것부터 본다.
        empty = [b['name'] for b in entries if not b.get('serves')]
        if empty:
            print(f'  {RED}✗{OFF} {cat} — serves 가 빈 상호 {len(empty)}곳: {", ".join(empty[:5])}')
            print('       (비어 있으면 아무 요금제나 받는다 — 짝맞춤 검사가 무력해진다)')
            fail += 1
            continue

        # 표기(이름 + forms) → 그 사업자가 파는 품목 접두사
        sells = {}
        for b in entries:
            for name in [b['name']] + list(b.get('forms') or []):
                sells.setdefault(name, set()).update(b['serves'])

        rows = query(f"""
            SELECT mydata_payment_merchant_name, mydata_payment_product_name, COUNT(*)
              FROM mydata_payment
             WHERE mydata_payment_category2 = '{cat}'
             GROUP BY 1, 2""")
        if not rows:
            print(f'  {RED}✗{OFF} {cat} — 결제가 하나도 없다 (검사가 무의미하다)')
            fail += 1
            continue

        bad, total = [], 0
        for merchant, product, n in rows:
            total += int(n)
            prefixes = sells.get(merchant)
            if prefixes is None:
                bad.append((merchant, product, int(n), '카탈로그에 없는 상호'))
            elif not any(product.startswith(p) for p in prefixes):
                bad.append((merchant, product, int(n), '이 사업자가 팔지 않는 품목'))

        if bad:
            hurt = sum(b[2] for b in bad)
            print(f'  {RED}✗{OFF} {cat} — 어긋난 조합 {len(bad)}종 · {hurt:,}건 / {total:,}건')
            for m, p, n, why in sorted(bad, key=lambda x: -x[2])[:8]:
                print(f'       {m:<24} │ {p:<24} {n:>7,}건  ({why})')
            fail += 1
        else:
            print(f'  {GREEN}✓{OFF} {cat} 상호↔요금제 짝  '
                  f'{total:,}건 · 표기 {len(sells)}종 전부 일치')

    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
