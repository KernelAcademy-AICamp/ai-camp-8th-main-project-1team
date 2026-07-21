#!/usr/bin/env bash
# 감사로그 무결성 시연 (문서 §5-4 "가장 시각적으로 강한 데모")
#
#   ./scripts/demo-tamper.sh verify      현재 검증 상태
#   ./scripts/demo-tamper.sh break       엔트리 1건 변조 → 계층 1(해시체인)이 잡는다
#   ./scripts/demo-tamper.sh restore     break 원상복구
#   ./scripts/demo-tamper.sh regenerate  엔트리 삭제 + 루트 재계산 → 계층 1·2를 통과시킨다.
#                                        오직 계층 3(TSA 앵커)만이 잡는다.  ★ 발표 하이라이트
#
# 발표 대사:
#   "해시체인만으로는 내부자의 전체 재작성을 막을 수 없어서,
#    배치 루트를 외부 TSA에 앵커링했습니다."
set -euo pipefail

cd "$(dirname "$0")/.."
API="${API:-http://localhost:8080}"
DB_URL="jdbc:h2:file:./backend/data/finntech;MODE=MySQL;AUTO_SERVER=TRUE"
SEQ="${SEQ:-1}"

H2JAR=$(find ~/.m2/repository/com/h2database -name "h2-*.jar" 2>/dev/null | head -1)
if [[ -z "$H2JAR" ]]; then
  echo "h2 jar를 찾을 수 없습니다. 먼저 빌드하세요: (cd backend && ./mvnw -q package -DskipTests)" >&2
  exit 1
fi

sql() { java -cp "$H2JAR" org.h2.tools.Shell -url "$DB_URL" -user sa -password "" -sql "$1"; }
sql_value() { sql "$1" 2>/dev/null | grep -oE '^[0-9a-f]{64}' | head -1; }

verify() {
  echo "--- /api/audit/verify ---"
  curl -s "$API/api/audit/verify" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(f\"  valid={d['valid']}  엔트리={d['entryCount']}  배치={d['batchCount']}  앵커={d['anchoredBatchCount']}\")
for p in d.get('problems',[]): print('   ✗', p)
for b in d.get('batches',[]):
    t = b.get('tsaGenTime') or '미앵커'
    print(f\"   batch {b['batchId']} [{b['anchorStatus']}] genTime={t}\")
"
}

case "${1:-verify}" in
  break)
    echo "[계층 1 시연] seq=$SEQ 페이로드를 직접 UPDATE"
    sql "update audit_log set payload_json = replace(payload_json, '\"userId\"', '\"userID\"') where seq = $SEQ;"
    verify
    ;;
  restore)
    echo "[복구]"
    sql "update audit_log set payload_json = replace(payload_json, '\"userID\"', '\"userId\"') where seq = $SEQ;"
    verify
    ;;
  regenerate)
    echo "[계층 3 시연] 운영자가 엔트리를 지우고 체인을 '정직하게' 재생성한다"
    LAST=$(sql "select max(seq) from audit_log;" 2>/dev/null | grep -oE '^[0-9]+' | head -1)
    if [[ -z "$LAST" || "$LAST" -lt 2 ]]; then
      echo "  엔트리가 2건 이상 필요합니다. 먼저 ./scripts/dev-up.sh 를 돌리세요." >&2; exit 1
    fi
    echo "  1) seq=$LAST 삭제"
    sql "delete from audit_log where seq = $LAST;" >/dev/null
    sql "update audit_batch set to_seq = $((LAST-1)) where to_seq >= $LAST;" >/dev/null

    echo "  2) 남은 엔트리로 Merkle 루트를 정직하게 재계산 (계층 2를 통과시킨다)"
    HASHES=$(sql "select entry_hash from audit_log order by seq;" 2>/dev/null | grep -oE '^[0-9a-f]{64}')
    NEWROOT=$(python3 -c "
import hashlib,sys
hs=[bytes.fromhex(h) for h in sys.argv[1:]]
lvl=[hashlib.sha256(b'\x00'+h).digest() for h in hs]
while len(lvl)>1:
    nxt=[]
    for i in range(0,len(lvl),2):
        l=lvl[i]; r=lvl[i+1] if i+1<len(lvl) else l
        nxt.append(hashlib.sha256(b'\x01'+l+r).digest())
    lvl=nxt
print(lvl[0].hex())" $HASHES)
    sql "update audit_batch set batch_root = '$NEWROOT' where id = (select min(id) from audit_batch);" >/dev/null
    echo "     새 루트: ${NEWROOT:0:16}…"

    echo "  3) 검증 — 계층 1·2는 통과한다. 앵커가 있다면 그것만이 잡는다."
    verify
    echo
    echo "  ※ 앵커가 0이면 이 공격은 탐지되지 않습니다. 그것이 계층 3이 필요한 이유입니다."
    echo "    TSA_ENABLED=true 로 기동 후 POST /api/audit/anchor 를 먼저 돌려보세요."
    ;;
  verify) verify ;;
  *) echo "usage: $0 {verify|break|restore|regenerate}" >&2; exit 1 ;;
esac
