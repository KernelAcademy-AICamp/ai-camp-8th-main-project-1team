-- **결제대행사 자신을 `간편결제` 로 옮긴다** — 무엇을 샀는지 원리적으로 알 수 없는 결제.
--
-- 지금까지 이런 결제는 세 곳에 흩어져 있었다(2026-08-26 운영 실측, 실사용자 기준).
--
--   쇼핑          82건   182,450원   ← `NICE_통신판매` 79건이 여기 있었다
--   기타          38건 1,306,823원
--   카테고리없음   37건   276,438원
--   금융/보험     21건   119,260원   ← PG 의 업종이 그대로 실렸다
--   취미/여가      1건     8,890원
--
-- **무엇을 샀는지 모르는 돈이 '쇼핑 지출'로 집계되고 낭비 판정까지 받았다** — 실사용자
-- 낭비 5건 92,850원이 그렇게 잡혀 있었다. 분류 오류가 판정 오류로 번진 자리다.
--
-- 판정 신호는 이미 결제 행에 적혀 있다 — `display_name_source = 'AGENCY_ONLY'`(V44).
-- 걷어내니 아무것도 안 남았다는 뜻이고, 그것이 곧 "결제대행사 자신"이다.
--
-- **사람이 정한 것은 안 덮는다**(`category2_source = 'USER'`). 우리 PG 목록이 틀려 진짜
-- 가맹점이 잘못 걸릴 수 있고, 그때 사용자가 고쳐 둔 답을 지우면 되돌릴 방법이 없어진다.
--
-- **총액에서는 안 뺀다.** 실제로 나간 돈이라 `AnalysisEngine.total` 에 남는다 — 빼면
-- "월소득 − 월평균지출" 로 구하는 가용 여유자금이 부풀려져 없는 여유를 있다고 권하게 된다.
-- 빠지는 것은 **카테고리 비중의 분모**뿐이다.

INSERT IGNORE INTO category (code, display_name) VALUES ('간편결제', '간편결제');

UPDATE user_payment
   SET category2 = '간편결제',
       category2_llm = NULL,
       category2_source = 'AGENCY'
 WHERE display_name_source = 'AGENCY_ONLY'
   AND (category2_source IS NULL OR category2_source <> 'USER');

-- 분석·리포트·점수가 읽는 것은 `consumption` 이다. 결제만 고치면 화면과 계산이 갈린다.
-- `consumption` 은 코드가 아니라 `category_id`(FK)를 든다.
UPDATE consumption c
  JOIN user_payment p ON p.payment_id = c.source_payment_id
  JOIN category k ON k.code = '간편결제'
   SET c.category_id = k.id
 WHERE p.category2 = '간편결제';

-- 소비 원장도 결제에서 다시 만들어지지만, 다음 갱신 전까지 화면이 옛 값을 본다.
UPDATE spending_ledger l
  JOIN user_payment p ON p.payment_id = l.payment_id
   SET l.category2 = '간편결제', l.category2_llm = NULL
 WHERE p.category2 = '간편결제';
