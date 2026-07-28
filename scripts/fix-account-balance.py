#!/usr/bin/env python3
"""
9월 말 기준으로 잔액이 마이너스가 되는 계좌의 초기잔액을 올린다.

왜 필요한가
-----------
과소비형·외식형은 생성된 카드 지출이 급여를 크게 넘는다(과소비형 평균 6,580만 vs 급여 485만/월).
그 자체는 페르소나의 사실이지만, 초기잔액이 그 적자를 감당하지 못하면 통장 잔액이 음수로 간다.
포트폴리오 시연에서 마이너스 통장이 보이는 것은 의도가 아니다.

**카드 지출·분석 데이터는 건드리지 않는다.** 통장의 초기잔액만 올려 "쓸 돈이 있었다"로 만든다.

잔액 계산은 제공자(MyDataService)와 같은 식이어야 한다
------------------------------------------------------
    잔액 = 초기잔액 + 월급누적 + 이자 − 세금 + 이체순증 − 카드결제누적

이자와 이체는 조회 시 합성되는 값이라 SQL만으로는 알 수 없다. 그래서 **제공자 API를 호출해
9월 말 시점의 실제 잔액을 받아** 부족분을 구한다 — 계산식을 두 곳에 두면 반드시 어긋난다.

사용
----
    MYDATA_URL=http://localhost:8083 MYDATA_SHARED_SECRET=... \\
    python3 scripts/fix-account-balance.py --dry-run
    ... --apply
"""
import argparse
import json
import os
import subprocess
import sys

TARGET_MONTHS = 9      # 조회 구간(9월 말까지 덮도록 충분히 넓게)
UNIT = 100_000         # 초기잔액은 10만원 단위로 올린다(어중간한 값은 생성 데이터답지 않다)

# 여유분은 계좌마다 다르게 준다.
#
# 부족분만 딱 채우면(-잔액 + 고정여유) 마이너스였던 계좌들의 결과가 좁은 구간에 몰린다 —
# 실제로 표본에서 14.0~17.4M로 뭉쳤다. 원래 초기잔액이 제각각이었는데 보정 후 다 비슷해지면
# 그것대로 생성 데이터답지 않다.
#
# 그래서 여유를 **본인의 월급 배수**로 준다. 월급이 큰 사람은 여유도 크게 잡히므로 결과가
# 자연히 흩어지고, "몇 달치 생활비를 쥐고 있었다"는 해석도 붙는다. 배수는 계좌번호를 시드로
# 뽑아 재현 가능하다.
MARGIN_MONTHS_MIN, MARGIN_MONTHS_MAX = 2, 6


def mysql(sql: str) -> str:
    binpath = os.environ.get("MYSQL_BIN", "")
    exe = os.path.join(binpath, "mysql") if binpath else "mysql"
    cmd = [exe, "-u", os.environ.get("DB_ROOT_USER", "root")]
    sock = os.environ.get("MYSQL_SOCKET", "")
    if sock:
        cmd.append(f"--socket={sock}")
    pw = os.environ.get("MYSQL_ROOT_PASSWORD", "")
    if pw:
        cmd.append(f"-p{pw}")
    cmd += ["--default-character-set=utf8mb4", "-N", "-B", "-e", sql]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        sys.stderr.write((r.stderr or "")[-400:] + "\n")
        raise SystemExit("mysql 실패")
    return r.stdout


def account_of(base: str, token: str, ci: str):
    r = subprocess.run(
        ["curl", "-s", "--max-time", "120", "-H", f"X-MyData-Token: {token}",
         f"{base}/bank/mydata/account?userId={ci}&months={TARGET_MONTHS}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    try:
        return json.loads(r.stdout).get("data")
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="검사할 계좌 수 제한(표본 확인용)")
    args = ap.parse_args()

    base = os.environ.get("MYDATA_URL", "http://localhost:8083")
    token = os.environ.get("MYDATA_SHARED_SECRET", "")

    rows = [l.split("\t") for l in mysql(
        "select a.mydata_user_id, a.mydata_account_id, a.mydata_account_initial_balance, "
        "       a.mydata_account_salary, u.mydata_user_persona "
        "from finntech_mydata.mydata_account a "
        "join finntech_mydata.mydata_user u on u.mydata_user_id = a.mydata_user_id "
        "order by a.mydata_account_id"
    ).splitlines() if l.strip()]
    if args.limit:
        rows = rows[: args.limit]
    print(f"대상 계좌 {len(rows):,}개 (조회 구간 {TARGET_MONTHS}개월)")

    fixes, checked, neg = [], 0, 0
    import random
    for ci, acc_id, init, salary, persona in rows:
        d = account_of(base, token, ci)
        checked += 1
        if not d:
            continue
        bal = d["balance"]
        if bal >= 0:
            continue
        neg += 1
        rng = random.Random(acc_id.__hash__() & 0xffffffff)
        months = rng.randint(MARGIN_MONTHS_MIN, MARGIN_MONTHS_MAX)
        need = -bal + int(salary) * months
        bump = ((need + UNIT - 1) // UNIT) * UNIT      # 10만원 단위 올림
        fixes.append((acc_id, int(init), int(init) + bump, bal, persona))
        if checked % 200 == 0:
            print(f"  ...{checked:,}/{len(rows):,} 확인 · 마이너스 {neg}개")

    print(f"확인 {checked:,}개 · 마이너스 {len(fixes):,}개")
    for acc, old, new, bal, persona in fixes[:10]:
        print(f"  {persona:<8s} {acc:<24s} 잔액 {bal:>13,} → 초기잔액 {old:>12,} → {new:>12,}")
    if len(fixes) > 10:
        print(f"  ... 외 {len(fixes)-10:,}개")

    if not fixes or args.dry_run or not args.apply:
        print("\n(--apply 를 주면 실제로 반영한다)")
        return

    # 100건씩 나눠 보낸다 — 한 문장에 다 담으면 인자 한도를 넘는다.
    CHUNK = 100
    for i in range(0, len(fixes), CHUNK):
        cases = " ".join(f"when '{a}' then {n}" for a, _, n, _, _ in fixes[i:i + CHUNK])
        ids = ",".join(f"'{a}'" for a, _, _, _, _ in fixes[i:i + CHUNK])
        mysql(f"update finntech_mydata.mydata_account "
              f"set mydata_account_initial_balance = case mydata_account_id {cases} end "
              f"where mydata_account_id in ({ids});")
    print(f"  {len(fixes):,}개 계좌 초기잔액 상향 완료")


if __name__ == "__main__":
    main()
