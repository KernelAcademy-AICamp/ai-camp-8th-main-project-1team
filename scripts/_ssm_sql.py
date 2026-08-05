"""SQL 한 덩어리를 운영 MySQL 에 먹이는 SSM 파라미터(JSON)를 만든다.

  python3 scripts/_ssm_sql.py "<SQL>"

**파일로 떨어뜨린 뒤 먹인다.** `mysql -e "<쿼리>"` 로 넘기면 셸 인용을 여러 겹 거치며
한글 리터럴이 깨진다 — `WHERE category2='스트리밍'` 이 아무것도 못 맞춰 **0을 돌려주고
조용히 통과**했다(2026-08-04 실측: 운영에 28종이 멀쩡히 있는데 검사만 0으로 봤다).
문자셋(`--default-character-set=utf8mb4`)도 반드시 준다.
"""
import json
import sys

sql = " ".join(l.strip() for l in sys.argv[1].splitlines() if l.strip())
print(json.dumps({"commands": [
    "set -a; . /opt/finntech/.env; set +a",
    "cat > /tmp/audit.sql <<'SQLEOF'\n" + sql + "\nSQLEOF",
    'docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" app-mysql-1 '
    'mysql -uroot --default-character-set=utf8mb4 -N -B < /tmp/audit.sql',
    "rm -f /tmp/audit.sql",
]}, ensure_ascii=False))
