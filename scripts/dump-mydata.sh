#!/usr/bin/env bash
# 운영 DB로 옮길 덤프를 만든다 — 로컬 MySQL → 압축 SQL 두 개(앱 원장 / 마이데이터 원장).
#
#   ./scripts/dump-mydata.sh                 # 기본: deploy/dump/ 에 생성
#   OUT=/path ./scripts/dump-mydata.sh       # 출력 위치 지정
#
# 왜 스크립트로 두는가: 13.6M행 덤프는 옵션 하나를 빠뜨리면 복원이 몇 시간씩 걸리거나
# 타임존이 어긋난 채 들어간다. 한 번 맞춘 옵션을 파일로 남겨 두 번째부터는 생각하지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${OUT:-deploy/dump}"
MYSQL_BIN="${MYSQL_BIN:-$HOME/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin}"
SOCK="${SOCK:-$HOME/Downloads/mysql-local/mysql.sock}"
DB_APP="${DB_NAME:-finntech}"
DB_MYDATA="${MYDATA_DB_NAME:-finntech_mydata}"

mkdir -p "$OUT"

# --single-transaction : InnoDB를 잠그지 않고 일관된 스냅샷을 뜬다(서비스 중단 없이).
# --quick              : 결과를 통째로 메모리에 올리지 않고 행 단위로 흘린다(13.6M행에 필수).
# --no-tablespaces     : PROCESS 권한이 없는 계정에서도 덤프가 되게 한다(RDS 복원 계정 대비).
# --set-gtid-purged=OFF: 소스의 GTID 상태가 목적지에 박히지 않게 한다(RDS 복원 시 거부 사유).
# --hex-blob           : 이진 값이 문자셋 변환으로 깨지지 않게.
# 타임존은 서버가 Asia/Seoul로 저장하고 있으므로 --tz-utc 를 쓰지 않는다 —
# 켜면 UTC로 변환돼 들어가 결제 시각이 9시간 밀린다(커트오프·챌린지 판정이 통째로 어긋난다).
DUMP_OPTS=(--single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4)

# 덤프 암호화 (설계서 Phase 4).
#
# **gzip 은 압축이지 암호화가 아니다.** 이 파일에는 실제 사람의 이름·주민번호 앞자리·전화번호와
# 카드 사용내역 전부가 들어 있다. 노트북에, USB 에, 메일 첨부에 그대로 남는다.
#
# `DUMP_PASSPHRASE` 가 있으면 `.sql.gz.enc` 로 떨어진다. 없으면 예전처럼 `.sql.gz` 로 두되
# **경고를 찍는다** — 조용히 평문으로 두는 것이 가장 나쁘다.
#
# 복호화:  openssl enc -d -aes-256-cbc -pbkdf2 -in <파일>.enc -out <파일>
encrypt_if_possible() {
  local plain="$1"
  if [ -z "${DUMP_PASSPHRASE:-}" ]; then
    echo "      ⚠ DUMP_PASSPHRASE 가 없어 **평문**으로 남긴다. 실 개인정보가 들어 있다면 반드시 넣어라." >&2
    return 0
  fi
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:DUMP_PASSPHRASE -in "$plain" -out "$plain.enc"
  rm -f "$plain"
  echo "      암호화됨 → $plain.enc"
}

dump_one() {
  local db="$1" dest="$OUT/$1.sql.gz"
  echo "[$db] 덤프 시작"
  "$MYSQL_BIN/mysqldump" -u root --socket="$SOCK" "${DUMP_OPTS[@]}" "$db" | gzip -1 > "$dest"
  echo "[$db] 완료 — $(du -h "$dest" | cut -f1)  →  $dest"
  encrypt_if_possible "$dest"
}

# 앱 원장은 기본으로 뜨지 않는다.
#
# `user_payment` 는 마이데이터의 **파생물**이라, 연동할 때마다 값이 바뀐다. 덤프를 뜬 시각과
# 재연동 시각이 어긋나면 옛 분류가 담긴 채로 운영에 들어가고, 그러면 기존 사용자 전원이
# '카테고리없음'이 된다 — 2026-07-30 운영에서 실제로 그렇게 됐다(RUNBOOK 참조).
# 운영의 앱 원장은 사용자가 연동하면 새 코드가 올바르게 다시 투영한다. 그게 정상 경로다.
#
# 그래도 필요하면(백업·조사) WITH_APP=1 로 켠다.
if [[ "${WITH_APP:-0}" == "1" ]]; then
  dump_one "$DB_APP"
else
  echo "[$DB_APP] 건너뜀 — 앱 원장은 파생물이라 복원하지 않는다(WITH_APP=1 로 강제)"
fi
dump_one "$DB_MYDATA"

cat <<EOF

복원(운영 서버에서):
  gunzip -c $DB_APP.sql.gz    | mysql -h <호스트> -u <계정> -p $DB_APP
  gunzip -c $DB_MYDATA.sql.gz | mysql -h <호스트> -u <계정> -p $DB_MYDATA

주의
  · DB 두 개는 미리 만들어져 있어야 한다(deploy/mysql-init/01-create-databases.sql 참고).
  · 스키마는 Flyway가 소유하지만 덤프에 CREATE TABLE이 들어 있다. 빈 DB에 복원한 뒤
    앱을 띄우면 Flyway가 이력을 보고 이미 최신임을 확인한다(flyway_schema_history도 덤프에 포함).
  · 복원 중에는 앱을 띄우지 않는다 — 반쯤 찬 DB로 기동하면 validate가 실패한다.
EOF
