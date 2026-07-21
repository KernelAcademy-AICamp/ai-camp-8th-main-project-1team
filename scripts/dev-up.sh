#!/usr/bin/env bash
# 로컬 개발 기동 — Docker/MySQL 없이 H2로 전부 띄운다.
# 8/7 중간 데모는 이 경로로 한다 (D-03: 배포 실패가 시연 실패가 되면 안 된다).
set -euo pipefail
cd "$(dirname "$0")/.."

API="${API:-http://localhost:8080}"

echo "[1/4] 백엔드 빌드"
(cd backend && ./mvnw -B -q -DskipTests package)

echo "[2/4] 백엔드 기동 (H2, 8080)"
# TSA_ENABLED=true 로 켜면 외부 FreeTSA에 실제 앵커링한다 (계층 3).
# 기본은 꺼져 있다 — 네트워크가 막힌 곳에서 기동이 느려지지 않게 하기 위함.
# stdin까지 /dev/null로 끊는다. 안 그러면 백그라운드 java가 상속한 fd를 물고 있어
# `./scripts/dev-up.sh | tee log` 처럼 파이프로 실행할 때 스크립트가 끝나도 파이프가 닫히지 않는다.
(cd backend && TSA_ENABLED="${TSA_ENABLED:-false}" \
  nohup java -jar target/backend-0.0.1-SNAPSHOT.jar > /tmp/finntech-boot.log 2>&1 < /dev/null &)
until curl -s -m 2 "$API/actuator/health" 2>/dev/null | grep -q UP; do sleep 1; done
echo "      기동 완료"

echo "[3/4] 시드 삽입 (개발 전용 경로 — 일반 API로는 DUMMY_SEED가 생성되지 않는다)"
# 이상거래 강도를 1.5~3.0배(경계 근처)로 둔다. 6~10배로 두면 분포에서 너무 멀어
# z 임계를 2.0~6.0 어디에 둬도 결과가 같아져 캘리브레이션이 무의미해진다.
curl -s -X POST "$API/api/dev/seed" -H 'Content-Type: application/json' -d '{
  "nickname":"demo","monthlyIncome":3200000,"goalAmount":4000000,"goalMonths":12,
  "months":6,"txPerMonth":60,
  "categoryMix":{"FOOD":0.35,"CAFE":0.20,"SHOPPING":0.25,"TRANSPORT":0.20},
  "plannedRatio":0.65,"volatility":0.18,
  "anomalyCount":6,"anomalyMagnitudeMin":1.5,"anomalyMagnitudeMax":3.0,"seed":7}' >/dev/null
curl -s -X POST "$API/api/alert/rescan?userId=1" >/dev/null
# 데모 사용자에 개인정보 동의를 부여한다 — 게임화 저축 루프의 '소비 기록'(§5-5)이 실제 Consumption을
# 만들어 동의를 요구하기 때문. 데모가 바로 돌게 하려는 편의이며, 미동의 시엔 소비 기록이 403이 된다.
curl -s -X POST "$API/api/users/1/consent" -H 'Content-Type: application/json' -d '{"consent":true}' >/dev/null
echo "      완료 (userId=1, 동의 부여)"

echo "[4/4] 프론트 기동: cd frontend && npm run dev  → http://localhost:5173"
echo
echo "확인용:"
echo "  curl -s '$API/api/alert/list?userId=1' | python3 -m json.tool"
echo "  curl -s '$API/api/dev/calibrate?userId=1&from=1.0&to=5.0&step=0.5'   # FDS 임계치 스윕"
echo "  ./scripts/demo-tamper.sh break        # 변조 탐지 (계층 1)"
echo "  ./scripts/demo-tamper.sh regenerate   # DB 전체 재생성 공격 (계층 3만 잡는다)"
echo
echo "TSA 앵커링(계층 3)을 쓰려면: TSA_ENABLED=true ./scripts/dev-up.sh"
echo "  그다음: curl -s -X POST $API/api/audit/anchor | python3 -m json.tool"
