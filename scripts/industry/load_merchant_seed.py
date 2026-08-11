"""확정 분류 사전을 DB 에 넣는다. 멱등 — 몇 번 돌려도 결과가 같다.

  실행:  python3 scripts/industry/load_merchant_seed.py [씨앗파일]
         (기본 입력 `_archive/merchant-seed.tsv`, `scripts/industry/build_merchant_seed.py` 산출물)

  환경:  DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD  (기본은 로컬 개발 DB)
         DRY_RUN=1 이면 세어만 보고 쓰지 않는다.

## 데이터는 저장소에 넣지 않는다

씨앗은 **실제 사람의 결제처 목록**이다. 값(사업자번호 → 업종)은 국세청 공개 정보라 그 자체가
비밀은 아니지만, **"하필 이 목록이 뽑혔다"** 는 사실이 그 사람의 소비 습관을 드러낸다.

git 히스토리는 지워지지 않는다 — 한 번 커밋하면 파일을 나중에 지워도 모든 클론과 포크에 남는다.
게다가 이 저장소는 **적용된 마이그레이션을 한 글자도 못 고친다**(CLAUDE.md 규칙 3, Flyway 체크섬).
시드를 `V15.sql` 에 박으면 "이 항목 빼 주세요" 요청이 와도 되돌릴 방법이 없다.

그래서 **재현성은 로직으로, 데이터는 손으로** 나른다. 이 스크립트와 만드는 스크립트는 저장소에
있고, 씨앗 파일은 `_archive/`(gitignore) 에 둔다.

## 가맹점명이 없는 씨앗

`realdatas.csv` 는 (사업자번호, 업종) 만 주고 **가맹점 풀네임이 없다.** 사전의 키는
(사업자번호, 풀네임) 이므로 풀네임 자리를 비워 둔다 — 빈 이름으로 넣으면 정확 일치는 절대
안 맞고 **같은 번호의 다른 행 완화**(`MerchantCategoryService.lookup` ③)만 타게 되는데,
그게 바로 이 씨앗에 맞는 동작이다. PG 는 애초에 씨앗에서 빠져 있어 완화가 안전하다.
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..')
DEFAULT_SRC = os.path.join(ROOT, '_archive', 'merchant-seed.tsv')

SOURCE = 'USER_CSV'          # 국세청 등록 정보다 — 추정이 아니라 사실이라 확정으로 넣는다
DRY = os.environ.get('DRY_RUN') == '1'


def rows(path):
    out = []
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            c = line.rstrip('\n').split('\t')
            if len(c) < 2:
                continue
            biz = ''.join(ch for ch in c[0] if ch.isdigit())
            # 4번째 칸(가맹점명)이 있으면 **번호 없이 이름으로만** 붙는 행이다 — PG 경유 결제.
            name = c[3].strip() if len(c) > 3 else ''
            # 5번째 칸은 이 중분류를 낳은 국세청 코드다(V29). **옛 4칸 씨앗도 읽힌다** —
            # 없으면 근거 없이 들어가고, 그건 지금까지와 같은 상태다.
            codes = c[4].strip() if len(c) > 4 else ''
            if not c[1].strip():
                continue
            if name:
                out.append(('', c[1].strip(), name, codes))
                continue
            if len(biz) != 10:
                continue
            out.append((biz, c[1].strip(), '', codes))
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    if not os.path.exists(src):
        print(f'씨앗이 없다: {src}\n  먼저 build_merchant_seed.py 를 돌린다.', file=sys.stderr)
        sys.exit(1)

    data = rows(src)
    if not data:
        print(f'씨앗에 쓸 수 있는 줄이 없다: {src}', file=sys.stderr)
        sys.exit(1)

    dist = {}
    for _, m, _, _ in data:
        dist[m] = dist.get(m, 0) + 1
    byname = sum(1 for b, _, _, _ in data if not b)
    withcodes = sum(1 for _, _, _, c in data if c)
    print(f'  {os.path.relpath(src, ROOT)} — {len(data)}곳'
          + (f' (그중 이름으로만 붙는 것 {byname}곳 — PG 경유)' if byname else ''))
    print('   ' + ' · '.join(f'{k} {v}' for k, v in sorted(dist.items(), key=lambda x: -x[1])))
    print(f'   업종코드(근거)를 든 행 {withcodes}곳 / {len(data)}'
          + ('  ⚠ 옛 4칸 씨앗이다 — build_merchant_seed.py 를 다시 돌려라' if not withcodes else ''))

    if DRY:
        print('   DRY_RUN=1 — 쓰지 않고 끝낸다.')
        return

    try:
        import pymysql
    except ImportError:
        print('pymysql 이 필요하다:  .venv/bin/pip install pymysql', file=sys.stderr)
        sys.exit(1)

    conn = pymysql.connect(
        host=os.environ.get('DB_HOST', '127.0.0.1'),
        port=int(os.environ.get('DB_PORT', '3306')),
        user=os.environ.get('DB_USER', 'finntech'),
        password=os.environ.get('DB_PASSWORD', 'finntech'),
        database=os.environ.get('DB_NAME', 'finntech'),
        charset='utf8mb4', autocommit=False)
    # pymysql 은 charset 을 주면 연결 인코딩까지 맞춘다. **CLI 로 넣을 때는 반드시**
    # `mysql --default-character-set=utf8mb4` 를 붙인다 — 빠뜨리면 latin1 로 읽어
    # 한글이 이중 인코딩되고, 그 값이 사전 조회를 거쳐 Category 생성까지 번진다
    # (2026-08-04 운영에서 실제로 1,523건이 깨진 카테고리로 들어갔다).

    # 멱등: 같은 (번호, 이름) 이 이미 있으면 분류만 맞춘다. 사람이 확인한 것(USER_CONFIRMED)은
    # **덮지 않는다** — 사람의 판단이 CSV 일괄 적재보다 뒤에 있으면 안 된다.
    sql = """
        INSERT INTO merchant_category
            (business_number, merchant_name, category2, source, nts_codes, created_at, updated_at)
        VALUES (%s, %s, %s, %s, NULLIF(%s, ''), NOW(6), NOW(6))
        ON DUPLICATE KEY UPDATE
            category2  = IF(source = 'USER_CONFIRMED', category2, VALUES(category2)),
            -- **source 도 함께 올린다.** 같은 가맹점에 LLM 추정(LLM_GUESS)이 먼저 쌓여 있을 수
            -- 있는데, 분류만 사실로 바꾸고 출처를 그대로 두면 그 행은 여전히 '추정'이라
            -- 조회에서 걸러진다 — 값은 맞는데 안 붙는, 오류 없는 실패가 된다.
            source     = IF(source = 'USER_CONFIRMED', source, VALUES(source)),
            -- **근거는 분류와 함께 움직인다**(V29). 분류만 갈아 끼우고 옛 코드를 두면 그 행은
            -- 새 분류에 옛 근거를 붙인 채로 굳고, 재계산이 그 옛 코드를 읽어 도로 뒤집는다.
            nts_codes  = IF(source = 'USER_CONFIRMED', nts_codes, VALUES(nts_codes)),
            updated_at = IF(source = 'USER_CONFIRMED', updated_at, NOW(6))
    """
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM merchant_category")
            before = cur.fetchone()[0]
            cur.executemany(sql, [(b, n, m, SOURCE, c) for b, m, n, c in data])
            cur.execute("SELECT COUNT(*) FROM merchant_category")
            after = cur.fetchone()[0]
        conn.commit()
        print(f'   merchant_category: {before} → {after}곳 (신규 {after - before})')
    finally:
        conn.close()


if __name__ == '__main__':
    main()
