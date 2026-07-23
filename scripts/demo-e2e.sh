#!/usr/bin/env bash
# 엔드투엔드 시연 (§13-11 · W8) — 생성 마이데이터(11M)의 SERVICE 사용자 하나를
# 백엔드에 링크해 [마이데이터 서빙(8083) → 엔진(리포트·점수·규칙 FDS) → ML 낭비/필수 판정]이
# 실제로 흐르는지 보인다. 학습셋이 아닌 SERVICE 분리 사용자로만 시연한다(요구11).
#
#   전제(두 서버가 떠 있어야 함):
#     · MySQL(finntech_mydata, 11M)  · 데모 mydata(mysql, 서빙전용, 8083)  · 데모 백엔드(인메모리 h2, 8090)
#   기동 방법은 이 스크립트가 서버 미기동 시 안내한다.
#
#   사용: ./scripts/demo-e2e.sh            (SQL로 데모 사용자 자동 선정)
#         DEMO_CI=<ci> ./scripts/demo-e2e.sh   (특정 사용자 지정)
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG="${LANG:-en_US.UTF-8}" LC_ALL="${LC_ALL:-en_US.UTF-8}"  # $VAR 뒤 한글이 변수명에 붙지 않게

MYDATA="${MYDATA:-http://localhost:8083}"
BACKEND="${BACKEND:-http://localhost:8090}"
COMPANY_ID="${COMPANY_ID:-9001}"

# 로컬 MySQL(도커/브루 없이 설치한 tarball) — 사용자 선정 쿼리에만 쓴다.
MYSQL_BIN="${MYSQL_BIN:-$HOME/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql}"
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-finntech}"; DB_PASSWORD="${DB_PASSWORD:-finntech}"
DB_NAME="${MYDATA_DB_NAME:-finntech_mydata}"
mysql_q() { "$MYSQL_BIN" --no-defaults -u"$DB_USER" -p"$DB_PASSWORD" --protocol=TCP \
              -h"$DB_HOST" -P"$DB_PORT" "$DB_NAME" -N -B -e "$1" 2>/dev/null; }

hr() { printf '%.0s─' {1..72}; echo; }

# ── 0) 서버 확인 ──────────────────────────────────────────────────────────
if ! curl -s -m 3 "$MYDATA/bank/mydata/card-company" >/dev/null 2>&1; then
  cat >&2 <<EOF
[중단] 데모 mydata 서버($MYDATA)가 응답하지 않습니다. 먼저 기동하세요:
  (cd backend-mydata && DB_HOST=127.0.0.1 DB_PORT=3306 MYDATA_DB_NAME=finntech_mydata \\
     DB_USER=finntech DB_PASSWORD=finntech \\
     MYDATA_SHARED_SECRET=demo-mydata-shared-2026 \\
     java -jar target/backend-mydata-0.0.1-SNAPSHOT.jar \\
       --spring.profiles.active=mysql --server.port=8083 \\
       --mydata.generation.enabled=false --mydata.seed.enabled=false \\
       '--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/finntech_mydata?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false' &)
     # Phase 6 이후: mysql 프로파일이 Flyway(V5 baseline 채택 완료)+validate로 정상 기동한다.
     # W7-2 이후: mysql은 MYDATA_SHARED_SECRET 필수(미설정 시 fail-fast). 본체와 같은 값이어야 /bank 호출이 통과.
EOF
  exit 1
fi
if ! curl -s -m 3 "$BACKEND/api/ml/status" >/dev/null 2>&1; then
  cat >&2 <<EOF
[중단] 데모 백엔드($BACKEND)가 응답하지 않습니다. 먼저 기동하세요(실행 중 8080/데이터와 충돌 회피):
  (cd backend && MYDATA_BASE_URL=$MYDATA MYDATA_SHARED_SECRET=demo-mydata-shared-2026 \\
     DB_USER=finntech DB_PASSWORD=finntech \\
     java -jar target/backend-0.0.1-SNAPSHOT.jar \\
       --spring.profiles.active=mysql --server.port=8090 --finntech.dev.seed-enabled=true \\
       '--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/finntech?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false' &)
     # Phase 6 이후: 본체도 MySQL(finntech, Flyway V1+validate). 인메모리 h2 아님 → 재시작에도 데이터 유지.
     # W7-2 이후: MYDATA_SHARED_SECRET을 mydata와 동일 값으로 — 본체가 X-MyData-Token으로 실어 보낸다.
EOF
  exit 1
fi
echo "모델 로드: $(curl -s "$BACKEND/api/ml/status")"

# ── 1) 데모 사용자 선정 (SQL, 읽기전용·결정론) ──────────────────────────────
hr; echo "[1] 데모 사용자 선정 — 과소비형 · data_split=SERVICE · 가시결제 150~320(≈6개월) · 낭비/필수 혼재"
if [[ -n "${DEMO_CI:-}" ]]; then
  CI="$DEMO_CI"
else
  CI=$(mysql_q "
    SELECT u.mydata_user_id
    FROM mydata_user u
    JOIN mydata_card c    ON c.mydata_user_id = u.mydata_user_id
    JOIN mydata_payment p ON p.mydata_card_id = c.mydata_card_id
    WHERE u.mydata_user_persona='과소비형' AND u.mydata_user_data_split='SERVICE'
      AND p.mydata_payment_date <= '2026-07-21 23:59:59'
    GROUP BY u.mydata_user_id
    HAVING COUNT(*) BETWEEN 150 AND 320
       AND SUM(p.mydata_payment_waste_label='WASTE')  >= 40
       AND SUM(p.mydata_payment_waste_label<>'WASTE') >= 40
    ORDER BY u.mydata_user_id LIMIT 1;")
fi
[[ -z "$CI" ]] && { echo "선정된 사용자가 없습니다(DEMO_CI로 지정하세요)." >&2; exit 1; }

# 생성 시 정답 라벨(비교 기준 — ML은 이걸 못 보고 특징만으로 추론한다)
GT=$(mysql_q "
  SELECT CONCAT_WS(' ', COUNT(*), SUM(p.mydata_payment_waste_label='WASTE'), SUM(p.mydata_payment_waste_label<>'WASTE'))
  FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
  WHERE c.mydata_user_id='$CI' AND p.mydata_payment_date <= '2026-07-21 23:59:59';")
GT_TOTAL=$(echo "$GT" | awk '{print $1}')
GT_WASTE=$(echo "$GT" | awk '{print $2}')
GT_ESS=$(echo "$GT" | awk '{print $3}')
echo "  CI = $CI"
echo "  가시결제(생성 정답): 총 ${GT_TOTAL}건 = 낭비 ${GT_WASTE} · 필수 ${GT_ESS}  ← ML은 이 라벨을 못 봄"

# ── 2) 링크 (dev 전용 신원 주입) ────────────────────────────────────────────
hr; echo "[2] 카드사 연결 — POST /api/dev/link-synthetic (생성 CI 직접 주입 → 마이데이터 서빙 적재)"
LINK=$(curl -s -XPOST "$BACKEND/api/dev/link-synthetic" -H 'Content-Type: application/json' \
        -d "{\"ci\":\"$CI\",\"companyIds\":[$COMPANY_ID]}")
USERID=$(printf '%s' "$LINK" | python3 -c "import sys,json;print(json.load(sys.stdin)['userId'])")
echo "$LINK" | python3 -c "import sys,json;d=json.load(sys.stdin);print(f\"  userId={d['userId']}  카드 {d['cardCount']}장 · 결제 {d['paymentCount']}건 적재\")"

# ── 3) ML 낭비/필수 판정 (거래별 + '왜') ────────────────────────────────────
hr; echo "[3] ML 판정 — GET /api/ml/waste/$USERID  (해석가능 EBM, 임계 0.233)"
# 정답 라벨 맵(대조용) — ML은 이 라벨을 보지 않고 특징만으로 추론한다.
GTMAP=$(mysql_q "
  SELECT p.mydata_payment_id, p.mydata_payment_waste_label
  FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
  WHERE c.mydata_user_id='$CI' AND p.mydata_payment_date<='2026-07-21 23:59:59';")
curl -s "$BACKEND/api/ml/waste/$USERID" | GTMAP="$GTMAP" python3 -c "
import sys,json,os,statistics as st
js=json.load(sys.stdin)
gt=dict(l.split('\t') for l in os.environ['GTMAP'].splitlines() if l.strip())
waste=[j for j in js if j.get('waste')]
print(f'  판정 {len(js)}건 → 낭비 {len(waste)} · 필수 {len(js)-len(waste)}')
gw=[j['wasteProbability'] for j in js if gt.get(j['paymentId'])=='WASTE']
ge=[j['wasteProbability'] for j in js if gt.get(j['paymentId'])!='WASTE']
if gw and ge:
    print(f'  랭킹 검증 — 정답 낭비 평균 p={st.mean(gw):.3f} vs 정답 필수 평균 p={st.mean(ge):.3f} '
          f'(약 {st.mean(gw)/st.mean(ge):.1f}배 분리) → ML은 라벨 없이도 낭비를 위로 올린다')
print('  ── 낭비 확률 상위 5 (정답 라벨 대조) ──')
for j in sorted(js,key=lambda x:-x['wasteProbability'])[:5]:
    mark='낭비' if j['waste'] else '필수'
    print(f\"    [ML:{mark}|정답:{gt.get(j['paymentId'],'?')}] p={j['wasteProbability']:.3f}  {j['category2']:<6} {j['amount']:>7,}원  {j['date']}  :: {j['explanation']}\")
print('  ── 가장 필수적인 3건 ──')
for j in sorted(js,key=lambda x:x['wasteProbability'])[:3]:
    print(f\"    [필수] p={j['wasteProbability']:.3f}  {j['category2']:<6} {j['amount']:>7,}원  {j['date']}\")
print('  ※ 임계를 넘는 건 소수 — 심야·고액처럼 관측신호가 뚜렷한 낭비만 확신한다.')
print('    관측 불가한 충동성(주간 소액 재량)은 원리상 상한(베르누이 draw, PR-AUC 0.438).')
"

# ── 4) 엔진 — 리포트 · 소비건강점수 · 규칙 FDS(baseline) ─────────────────────
hr; echo "[4] 엔진 재사용 — 같은 마이데이터가 리포트·점수·FDS로도 흐른다"
curl -s -XPOST "$BACKEND/api/alert/rescan?userId=$USERID" >/dev/null
curl -s "$BACKEND/api/report/monthly?userId=$USERID" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print(f\"  [리포트] 총지출 {int(d['totalSpend']):,}원 · 상위 카테고리 {len(d['positive'])}종\")
print(f\"           내러티브: {(d.get('narrative') or '')[:90]}\")
"
curl -s "$BACKEND/api/score/$USERID" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print(f\"  [점수]   소비건강점수 {d['score']} ({d['grade']})  breakdown={json.dumps(d['breakdown'],ensure_ascii=False)}\")
"
curl -s "$BACKEND/api/alert/list?userId=$USERID" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print(f\"  [FDS]    규칙 baseline 평가 {d['evaluatedCount']}건 · 경고 {len(d['items'])}건  (통계적 이상치)\")
"

hr
echo "요약: 마이데이터 서빙(8083) → 백엔드 링크 → 하나의 소비 스트림이"
echo "      ① 규칙 FDS(통계적 이상치) · ② 리포트/점수 · ③ 거래별 ML 낭비/필수 판정으로 동시에 흐른다."
echo "      규칙 FDS는 3주 창에서 이상치를 못 잡아도, ML은 심야·과다 재량 결제를 '왜'와 함께 낭비로 짚는다."
