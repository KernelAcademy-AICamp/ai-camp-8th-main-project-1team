"""SQL 한 덩어리를 운영 MySQL 에 먹이는 SSM 파라미터(JSON)를 만든다.

  python3 scripts/_ssm_sql.py "<SQL>"

**파일로 떨어뜨린 뒤 먹인다.** `mysql -e "<쿼리>"` 로 넘기면 셸 인용을 여러 겹 거치며
한글 리터럴이 깨진다 — `WHERE category2='스트리밍'` 이 아무것도 못 맞춰 **0을 돌려주고
조용히 통과**했다(2026-08-04 실측: 운영에 28종이 멀쩡히 있는데 검사만 0으로 봤다).
문자셋(`--default-character-set=utf8mb4`)도 반드시 준다.

**비밀번호는 컨테이너 안에서 꺼낸다(2026-08-19 수정).** 예전에는 호스트에서
`. /opt/finntech/.env` 로 읽었는데, **배포가 그 파일을 지운다**
(`deploy-server.sh`: `shred -u /opt/finntech/.env`) — 비밀을 디스크에 남기지 않으려는
의도된 동작이라 되살릴 것이 아니다. 그래서 이 스크립트는 배포가 한 번이라도 돈 뒤에는
`MYSQL_ROOT_PASSWORD` 가 빈 값이 되어 **인증 실패로 조용히 죽어 있었다.**

DB 컨테이너는 자기 환경변수로 그 값을 이미 갖고 있다. 호스트를 거치지 않고 컨테이너
안에서 꺼내 쓰면 파일이 없어도 되고, 값이 **호스트의 명령줄이나 프로세스 목록에도 안 남는다**
(`$MYSQL_ROOT_PASSWORD` 를 작은따옴표로 넘겨 컨테이너 셸이 펼치게 한다).
"""
import json
import os
import sys

# 기본은 본체 DB. 제공자를 볼 때는 `FINNTECH_AUDIT_DB=finntech_mydata` 로 바꾼다.
DB = os.environ.get("FINNTECH_AUDIT_DB", "finntech")

sql = " ".join(l.strip() for l in sys.argv[1].splitlines() if l.strip())
print(json.dumps({"commands": [
    "cat > /tmp/audit.sql <<'SQLEOF'\n" + sql + "\nSQLEOF",
    "docker cp /tmp/audit.sql app-mysql-1:/tmp/audit.sql",
    # **DB 이름을 준다.** 예전에는 빠져 있어 `ERROR 1046 No database selected` 로 죽었다.
    # stderr 는 버리지 않고 남긴다 — 버렸더니 이 오류가 "출력 없음"으로만 보였다.
    "docker exec app-mysql-1 sh -c "
    "'mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 "
    "-N -B " + DB + " < /tmp/audit.sql' 2>&1 | grep -v \"^mysql: \\[Warning\\]\"",
    "rm -f /tmp/audit.sql; docker exec app-mysql-1 rm -f /tmp/audit.sql",
]}, ensure_ascii=False))
