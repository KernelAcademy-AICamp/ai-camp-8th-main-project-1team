-- 카테고리별 한도 (2026-07-31)
--
-- 그동안 한도는 `guardian_challenge.challenge_cap` 숫자 **하나**였다. 그래서 화면이 카테고리별로
-- 나눠 보여줄 때는 전체 캡을 카테고리 수로 균등분할했다 — 정산 코드에 그 사실이 적혀 있었다
-- ("한도는 챌린지 전체 캡을 카테고리 수로 나눈 값이다"). 사용자는 배달에 10만, 카페에 3만을
-- 정했는데 화면은 6.5만씩으로 보여준 셈이다.
--
-- 이제 온보딩에서 카테고리마다 정한 강도가 그대로 한도가 된다.
--
-- **판정은 바뀌지 않는다.** 챌린지의 성공/실패(ACTIVE·AT_RISK·EXCEEDED)와 잔디는 여전히
-- 합계 기준이다(사용자 결정 2026-07-31). 카테고리로 실패까지 가르면 카테고리 수만큼 실패
-- 확률이 올라가는데, 이 앱은 낙인을 피하는 것을 설계 원칙으로 삼는다.
-- 여기 있는 한도는 **어디서 새는지 보여주고 알리는** 용도다.
CREATE TABLE guardian_challenge_category (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    challenge_id BIGINT      NOT NULL,
    category     VARCHAR(40) NOT NULL,
    -- 그 카테고리의 기준 지출(최근 30일 실측)
    baseline     BIGINT      NOT NULL,
    -- 그 카테고리에서 지킬 돈
    target       BIGINT      NOT NULL,
    -- 한도 = 기준 − 지킬 돈. 빌려 쓰기는 없다(사용자 결정) — 남아도 다른 카테고리로 넘기지 않는다.
    cap          BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_challenge_category UNIQUE (challenge_id, category),
    CONSTRAINT fk_challenge_category FOREIGN KEY (challenge_id)
        REFERENCES guardian_challenge (id) ON DELETE CASCADE
);

CREATE INDEX idx_challenge_category ON guardian_challenge_category (challenge_id);

-- 이미 진행 중인 챌린지는 균등분할로 채운다 — 지금 화면이 그렇게 보여주고 있었으므로
-- 사용자가 보는 값이 바뀌지 않는다. 다음 챌린지부터 실제 강도가 반영된다.
INSERT INTO guardian_challenge_category (challenge_id, category, baseline, target, cap, created_at)
SELECT c.id,
       TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(c.categories, ',', n.k), ',', -1)) AS category,
       c.baseline_amount / (LENGTH(c.categories) - LENGTH(REPLACE(c.categories, ',', '')) + 1),
       c.target_saving   / (LENGTH(c.categories) - LENGTH(REPLACE(c.categories, ',', '')) + 1),
       c.challenge_cap   / (LENGTH(c.categories) - LENGTH(REPLACE(c.categories, ',', '')) + 1),
       c.created_at
  FROM guardian_challenge c
  JOIN (SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8) n
    ON n.k <= LENGTH(c.categories) - LENGTH(REPLACE(c.categories, ',', '')) + 1
 WHERE c.categories IS NOT NULL AND c.categories <> '';
