-- **"모름"은 추정이 아니다** — 옛 데이터를 규칙에 맞춘다.
--
-- 2026-08-21 에 `UserPayment.suggestCategory2` 가 '카테고리없음' 을 안 적도록 고쳤고
-- (`UnknownIsNotAGuessTest` 가 잠근다), 그 뒤로는 이 값이 생기지 않는다. 쓰는 자리도
-- 그 하나뿐이고 벌크 UPDATE 도 없다. 그런데 **고치기 전에 저장된 것이 남아 있다** —
-- 운영 실사용자 결제 95건(2026-08-26 실측).
--
--   category2        = 카테고리없음
--   category2_llm    = 카테고리없음   ← "모름"을 값으로 적었다
--   category2_source = LLM            ← 미분류인데 분류된 것으로 센다
--
-- **두 가지가 망가진다**(시험 주석이 예고한 그대로).
--
--   집계 — 미분류를 `category2_source='NONE'` 으로 세면 이것이 분류된 것으로 잡힌다.
--          운영에서 `NONE` 은 25건인데 실제 미분류는 116건이었다.
--   화면 — "확정이 비었는데 추정이 있다" 를 <AI 추정> 으로 보여준다. 값이 '카테고리없음'
--          인데 AI 가 추정했다고 표시된다.
--
-- 확정이 있는 4건은 **추정 칸만 비운다** — 그 줄의 분류는 다른 근거로 정해진 것이라
-- 출처를 건드리면 멀쩡한 판정을 뒤집는다.

UPDATE user_payment
   SET category2_llm = NULL,
       category2_source = 'NONE'
 WHERE category2_llm = '카테고리없음'
   AND (category2 IS NULL OR category2 = '카테고리없음');

UPDATE user_payment
   SET category2_llm = NULL
 WHERE category2_llm = '카테고리없음';

-- 소비 원장에도 같은 값이 복사돼 있다. 원장은 결제에서 다시 만들어지지만,
-- 다음 갱신 전까지 화면이 옛 값을 보므로 여기서 함께 맞춘다.
UPDATE spending_ledger
   SET category2_llm = NULL
 WHERE category2_llm = '카테고리없음';
