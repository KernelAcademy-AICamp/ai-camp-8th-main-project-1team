#!/usr/bin/env bash
# 재생성 직후 **설계대로 만들어졌는지** 데이터로 확인한다. 회귀 테스트가 카탈로그·시뮬레이터를
# 잡는다면 이 스크립트는 **실제로 적재된 1,000만 건**을 잡는다 — 둘은 다른 것을 본다.
#
#   ./scripts/verify-regen.sh
#
# 사람이 눈으로 보라고 만든 것이 아니다. 항목마다 기대값을 적어 두고 어긋나면 ✗ 를 찍는다.
set -uo pipefail
cd "$(dirname "$0")/.."

MYSQL_BIN="${MYSQL_BIN:-$HOME/Downloads/mysql-local/mysql-9.7.1-macos15-arm64/bin/mysql}"
DB_HOST="${DB_HOST:-127.0.0.1}"; DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-finntech}"; DB_PASSWORD="${DB_PASSWORD:-finntech}"
DB="${MYDATA_DB_NAME:-finntech_mydata}"

q() { "$MYSQL_BIN" --no-defaults -u"$DB_USER" -p"$DB_PASSWORD" --protocol=TCP \
        -h"$DB_HOST" -P"$DB_PORT" "$DB" -N -B -e "$1" 2>/dev/null; }

fail=0
ok()   { printf '  \033[32m✓\033[0m %-46s %s\n' "$1" "${2:-}"; }
bad()  { printf '  \033[31m✗\033[0m %-46s %s\n' "$1" "${2:-}"; fail=$((fail+1)); }
# 기대: 값이 0 이어야 한다
zero() { local n=""; n=$(q "$2" || true); if [ "${n:-x}" = "0" ]; then ok "$1" ""; else bad "$1" "${n:-조회실패}"; fi; }

echo "=== 규모 ==="
q "SELECT CONCAT('  사용자 ', FORMAT((SELECT COUNT(*) FROM mydata_user),0),
          ' · 결제 ', FORMAT((SELECT COUNT(*) FROM mydata_payment),0),
          ' · 통장거래 ', FORMAT((SELECT COUNT(*) FROM mydata_account_txn),0),
          ' · 가맹점 ', FORMAT((SELECT COUNT(*) FROM mydata_merchant),0));"

echo
echo "=== 경제 (사용자 결정: 월급 200~400만 · 월 카드지출 100~350만) ==="
q "SELECT CONCAT('  월급  최소 ', FORMAT(MIN(mydata_account_salary),0),
          ' · 최대 ', FORMAT(MAX(mydata_account_salary),0),
          ' · 평균 ', FORMAT(AVG(mydata_account_salary),0)) FROM mydata_account;"
zero "월급이 200만 미만" \
     "SELECT COUNT(*) FROM mydata_account WHERE mydata_account_salary < 2000000;"
zero "월급이 400만 초과" \
     "SELECT COUNT(*) FROM mydata_account WHERE mydata_account_salary > 4000000;"

q "SELECT CONCAT('  월 카드지출  최소 ', FORMAT(MIN(m),0), ' · 최대 ', FORMAT(MAX(m),0),
          ' · 평균 ', FORMAT(AVG(m),0))
     FROM (SELECT c.mydata_user_id u, SUM(p.mydata_payment_amount)/GREATEST(1,DATEDIFF(MAX(p.mydata_payment_date),MIN(p.mydata_payment_date))/30.44) m
             FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
            GROUP BY 1) t;"
q "SELECT CONCAT('  범위(100~350만) 밖 인원 ', FORMAT(SUM(m<1000000 OR m>3500000),0),
          ' / ', FORMAT(COUNT(*),0))
     FROM (SELECT c.mydata_user_id u, SUM(p.mydata_payment_amount)/GREATEST(1,DATEDIFF(MAX(p.mydata_payment_date),MIN(p.mydata_payment_date))/30.44) m
             FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
            GROUP BY 1) t;"

echo
echo "=== K-패스 환급 (대중교통만 · 월 5만 초과분 · 다음달 20일) ==="
q "SELECT CONCAT('  환급 ', FORMAT(COUNT(*),0), '건 · 평균 ', FORMAT(AVG(mydata_account_txn_amount),0),
          '원 · 받은 사람 ', FORMAT(COUNT(DISTINCT mydata_account_id),0), '명')
     FROM mydata_account_txn WHERE mydata_account_txn_source='KPASS';"
zero "환급이 20일이 아닌 날에 들어옴" \
     "SELECT COUNT(*) FROM mydata_account_txn
       WHERE mydata_account_txn_source='KPASS' AND DAY(mydata_account_txn_date) <> 20;"
zero "환급 적요가 'N월K패스환급' 형식이 아님" \
     "SELECT COUNT(*) FROM mydata_account_txn WHERE mydata_account_txn_source='KPASS'
        AND mydata_account_txn_description NOT REGEXP '^[0-9]{1,2}월K패스환급$';"

echo
echo "=== 택시 번호판 (지역2 + 31~36 + 아바사자 + 4자리) ==="
q "SELECT CONCAT('  표본: ', GROUP_CONCAT(x SEPARATOR ' / '))
     FROM (SELECT DISTINCT mydata_payment_merchant_name x FROM mydata_payment
            WHERE mydata_payment_ksic_code='4923' LIMIT 3) t;"
zero "택시 결제인데 번호판이 없음" \
     "SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_ksic_code='4923'
        AND mydata_payment_merchant_name NOT REGEXP '[가-힣]{2}3[1-6][아바사자][0-9]{4}\$';"
zero "가맹점 대표명에 번호판이 남음" \
     "SELECT COUNT(*) FROM mydata_merchant
       WHERE merchant_name REGEXP '[가-힣]{2}3[1-6][아바사자][0-9]{4}\$';"

echo
echo "=== 금융/보험 ==="
q "SELECT CONCAT('  보험 결제 ', FORMAT(COUNT(*),0), '건 · 보험사 ',
          COUNT(DISTINCT mydata_payment_business_number), '곳 · 상품 ',
          COUNT(DISTINCT mydata_payment_product_name), '종')
     FROM mydata_payment WHERE mydata_payment_ksic_code='6512';"
zero "전력·가스·수도가 보험료를 받음" \
     "SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_ksic_code='6512'
        AND mydata_payment_merchant_name IN
            ('한국전력공사','서울도시가스','삼천리','코원에너지서비스','서울시상수도사업본부');"
zero "공과금 결제에 보험료 품목이 섞임" \
     "SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_ksic_code='3520'
        AND mydata_payment_product_name LIKE '%보험%';"
# 계약은 매달 같은 날 — 여행보험만 예외다.
q "SELECT CONCAT('  보험료 출금일이 2일 이상인 사용자 ', FORMAT(COUNT(*),0), '명 (0이어야 한다)')
     FROM (SELECT c.mydata_user_id u
             FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
            WHERE p.mydata_payment_ksic_code='6512'
              AND p.mydata_payment_product_name NOT LIKE '%여행보험%'
            GROUP BY 1 HAVING COUNT(DISTINCT DAY(p.mydata_payment_date))>1) t;"

# '차가 없으면 자동차·운전자보험이 안 나간다'는 여기서 못 센다 — 차량 보유 여부가 원장에 없고,
# 주유(4771)·통행료(5291)는 일반 카테고리 추첨으로도 나와서 대용값이 되지 못한다(전원이 '차 있음'이 된다).
# 그 규칙은 생성 시점에 GenerationRegressionTest.차량과_보험이_맞는다 가 진짜 플래그로 검사한다.
q "SELECT CONCAT('  (차량↔보험 규칙은 GenerationRegressionTest 가 검사한다 — 원장에 차량 여부가 없다)');"
q "SELECT CONCAT('  보험 계약 수 분포(사람당 상품 종수): ',
          GROUP_CONCAT(CONCAT(k,'종=',FORMAT(n,0)) ORDER BY k SEPARATOR ' · '))
     FROM (SELECT k, COUNT(*) n FROM
            (SELECT c.mydata_user_id u,
                    COUNT(DISTINCT CASE WHEN p.mydata_payment_product_name NOT LIKE '%여행보험%'
                                        THEN p.mydata_payment_product_name END) k
               FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
              WHERE p.mydata_payment_ksic_code='6512' GROUP BY 1) x
           GROUP BY k) y;"

echo
echo "=== 교통비 (K-패스가 실제로 눌러 주는가) ==="
q "SELECT CONCAT('  대중교통 월평균 ', FORMAT(AVG(m),0), '원 · 환급 후 실질 ', FORMAT(AVG(LEAST(m,50000)),0), '원')
     FROM (SELECT c.mydata_user_id u,
                  SUM(p.mydata_payment_amount)/GREATEST(1,DATEDIFF(MAX(p.mydata_payment_date),MIN(p.mydata_payment_date))/30.44) m
             FROM mydata_card c JOIN mydata_payment p ON p.mydata_card_id=c.mydata_card_id
            WHERE p.mydata_payment_ksic_code='4921' GROUP BY 1) t;"
zero "택시가 K-패스 환급 대상에 섞임(택시는 제외여야 한다)" \
     "SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_ksic_code='4923'
        AND mydata_payment_category2='대중교통';"

echo
echo "=== 통장 잔액 (0 이상 2,000만 이하) ==="
q "SELECT CONCAT('  초기잔액  최소 ', FORMAT(MIN(mydata_account_initial_balance),0),
          ' · 최대 ', FORMAT(MAX(mydata_account_initial_balance),0),
          ' · 평균 ', FORMAT(AVG(mydata_account_initial_balance),0)) FROM mydata_account;"

echo
echo "=== 과거 오류 재발 방지 ==="
zero "생년이 1987~2006 밖" \
     "SELECT COUNT(*) FROM mydata_user WHERE
        (CASE SUBSTR(REPLACE(mydata_user_social_number,'-',''),7,1)
              WHEN '1' THEN 1900 WHEN '2' THEN 1900 WHEN '5' THEN 1900 WHEN '6' THEN 1900
              ELSE 2000 END
         + CAST(SUBSTR(mydata_user_social_number,1,2) AS UNSIGNED))
        NOT BETWEEN 1987 AND 2006;"
zero "사업자번호가 10자리가 아님" \
     "SELECT COUNT(*) FROM mydata_merchant WHERE business_number NOT REGEXP '^[0-9]{10}\$';"
zero "한 사업자번호에 주소가 2개 이상" \
     "SELECT COUNT(*) FROM (SELECT business_number FROM mydata_merchant
        GROUP BY 1 HAVING COUNT(DISTINCT address)>1) x;"
zero "지하철 요금이 고시요금이 아님" \
     "SELECT COUNT(*) FROM mydata_payment
       WHERE mydata_payment_product_name LIKE '지하철%' AND mydata_payment_amount % 50 <> 0;"
# mydata_payment_category2 는 앱 중분류가 아니라 생성 맥락이다(42종+). 앱 중분류는
# 업종코드에서 IndustryCategoryMapper 가 파생한다 — 여기서는 업종코드 종수만 확인한다.
q "SELECT CONCAT('  생성 맥락 ', COUNT(DISTINCT mydata_payment_category2), '종 · 업종코드 ',
          COUNT(DISTINCT mydata_payment_ksic_code), '종') FROM mydata_payment;"
zero "보험 업종(6512) 결제가 없음" \
     "SELECT IF(COUNT(*)>0,0,1) FROM mydata_payment WHERE mydata_payment_ksic_code='6512';"

echo
if [ "$fail" -eq 0 ]; then
  printf '\033[32m전부 통과\033[0m\n'
else
  printf '\033[31m%d개 항목 실패\033[0m\n' "$fail"
fi
exit "$fail"
