"""재생성 뒤 `frontend/src/lib/demoUsers.ts` 를 다시 만든다.

  실행:  python3 scripts/build-demo-users.py

## 왜 스크립트인가

선정 기준("SERVICE 분리 사용자에서 페르소나별 2명씩 결정론 선정")이 **파일 주석에만** 있고
실행 가능한 형태가 없었다. 그래서 재생성할 때마다 사람이 SQL 을 짜서 손으로 옮겨 적어야 했고,
실제로 목록이 낡아 **demoUsers.ts 의 CI 10개가 제공자 DB 에 하나도 없는** 상태로 남아 있었다
(2026-08-04 실측). 그러면 데모 사용자 전환이 통째로 죽는데 화면에는 "결제 0건"으로만 보인다.

## 선정 규칙

- `data_split = 'SERVICE'` — 학습에 안 쓰는 칸. 실서비스 사용자 몫이다.
- 페르소나마다 **CI 사전순 앞에서 2명** — 정렬을 고정하므로 같은 데이터면 같은 10명이 나온다(§3 재현성).
- `visible` 은 **커트오프 이하** 결제 건수다. 화면이 "결제 N건"으로 보여 주는 그 수라,
  커트오프를 무시하고 세면 사용자가 보는 것과 다른 숫자가 박힌다.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
OUT = os.path.join(ROOT, 'frontend', 'src', 'lib', 'demoUsers.ts')

MYSQL = os.environ.get('MYSQL_BIN', os.path.expanduser(
    '~/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql'))
DB = os.environ.get('MYDATA_DB_NAME', 'finntech_mydata')
USER = os.environ.get('DB_USER', 'finntech')
PW = os.environ.get('DB_PASSWORD', 'finntech')
# 제공자의 조회 커트오프(mydata.now = reference-date). 화면에 보이는 결제는 이 날까지다.
CUTOFF = os.environ.get('MYDATA_NOW', '2026-07-23')
PER_PERSONA = 2

SQL = f"""
SELECT u.mydata_user_persona, u.mydata_user_id, COUNT(p.mydata_payment_id),
       u.mydata_user_name, SUBSTR(u.mydata_user_social_number, 1, 7), u.mydata_user_phone_number
FROM mydata_user u
JOIN mydata_card c ON c.mydata_user_id = u.mydata_user_id
JOIN mydata_payment p ON p.mydata_card_id = c.mydata_card_id
                     AND p.mydata_payment_date <= '{CUTOFF} 23:59:59'
WHERE u.mydata_user_data_split = 'SERVICE' AND u.mydata_user_persona IS NOT NULL
GROUP BY 1, 2, 4, 5, 6
ORDER BY 1, 2;
"""


def query(sql):
    out = subprocess.run([MYSQL, '--no-defaults', f'-u{USER}', f'-p{PW}', '--protocol=TCP',
                          '-h127.0.0.1', DB, '-N', '-B', '-e', sql],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print(out.stderr.strip(), file=sys.stderr)
        sys.exit(1)
    return [ln.split('\t') for ln in out.stdout.strip().split('\n') if ln.strip()]


def main():
    rows = query(SQL)
    if not rows:
        print('SERVICE 분리 사용자가 없다 — 재생성이 끝났는지 확인하라', file=sys.stderr)
        sys.exit(1)

    picked, seen = [], {}
    for persona, ci, cnt, name, social7, phone in rows:   # 이미 (페르소나, CI) 순 정렬이다
        if seen.get(persona, 0) >= PER_PERSONA:
            continue
        seen[persona] = seen.get(persona, 0) + 1
        picked.append((persona, ci, int(cnt), name, social7, phone))

    order = list(dict.fromkeys(p for p, _, _, _, _, _ in picked))
    body = []
    for persona in order:
        for p, ci, cnt, name, social7, phone in picked:
            if p != persona:
                continue
            body.append(f"  {{ persona: '{p}', ci: '{ci}', name: '{name}', "
                        f"social7: '{social7}', phone: '{phone}', visible: {cnt} }},")
        body.append('')

    ts = f"""/**
 * 데모/개발용 테스트 사용자 표본 (§13-11). `finntech_mydata` 의 SERVICE 분리 사용자에서
 * 페르소나별 {PER_PERSONA}명씩 CI 사전순으로 뽑은 고정 목록이다. 사람마다 소비 성향이 달라
 * (절약형 vs 과소비형 …) 리포트·ML 판정이 어떻게 달라지는지 교체 연결로 확인한다.
 * 랜덤 전환 버튼은 이 목록에서 무작위 선택(App.tsx).
 *
 * **손으로 고치지 않는다** — `python3 scripts/build-demo-users.py` 가 만든다.
 * 재생성하면 CI 가 통째로 바뀌므로 반드시 다시 돌린다. 안 돌리면 목록의 CI 가 제공자에 없어
 * 데모 전환이 죽는데, 화면에는 "결제 0건"으로만 보여 원인을 찾기 어렵다.
 */
export interface DemoUser {{
  persona: string;
  ci: string;
  /** 사람 이름 — 성씨 1글자 + 이름 2글자. `scripts/identity/` 의 표에서 나온다. */
  name: string;
  /** 주민등록번호 앞 7자리. 7번째 자리가 성별이다(1·3 남, 2·4 여). */
  social7: string;
  /** 휴대폰 번호. **CI 는 이 셋의 해시**라 본인인증 화면에 그대로 입력해도 같은 사람에 닿는다. */
  phone: string;
  /** 커트오프({CUTOFF}) 이하 가시 결제 건수 */
  visible: number;
}}

export const DEMO_USERS: DemoUser[] = [
{chr(10).join(body).rstrip()}
];
"""
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(ts)
    print(f'  demoUsers.ts — 페르소나 {len(order)}종 · {len(picked)}명 (커트오프 {CUTOFF})')
    for p, ci, cnt, name, social7, phone in picked:
        print(f'     {p:<8} {name}  {social7}  {phone}  {cnt:,}건')


if __name__ == '__main__':
    main()
