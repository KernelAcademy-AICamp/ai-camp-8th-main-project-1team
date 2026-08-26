-- **분류를 바꿨으면 판정도 다시 해야 한다** — V46 이 빠뜨린 한 칸.
--
-- V46 이 결제대행사 179건을 `간편결제` 로 옮기면서 소비 원장의 `category2` 도 함께 고쳤다.
-- 그런데 `facts_updated_at` 을 안 올렸다. **분류는 그 줄의 사실인데도.**
--
-- 판정 갱신은 이렇게 대상을 고른다(`findUsersWithStaleJudgments`).
--
--   waste_recorded_at IS NULL  OR  waste_recorded_at < facts_updated_at  OR  모델이 갈렸나
--
-- 사실이 안 움직였으니 **아무도 낡지 않았다.** 그래서 `NICE_통신판매` 5건 92,850원이
-- <b>간편결제인데 낭비</b>인 채로 남았다(2026-08-26 운영 실측). 모델은 이미 그것을 판정하지
-- 않는다(`WasteScoringService` 가 `isUnknown` 이면 건너뛴다) — 다시 돌기만 하면 지워지는데,
-- 다시 돌 이유가 없었던 것이다.
--
-- 여기서 사실 시각만 올려 준다. 다음 갱신(10분 주기)이 집어 `unjudged` 로 덮는다 —
-- **판정을 SQL 로 직접 지우지 않는다.** 그 값이 어떤 모습이어야 하는지는 코드가 안다.

UPDATE spending_ledger
   SET facts_updated_at = NOW(6)
 WHERE category2 = '간편결제';
