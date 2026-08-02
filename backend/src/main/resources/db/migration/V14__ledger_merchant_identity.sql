-- 원장에 가맹점 신원을 준다 (2026-08-02)
--
-- 무엇이 문제였나.
--   지킴이 원장(guardian_transaction)은 **어느 가맹점인지 몰랐다.** 소비를 원장에 넣을 때
--   가맹점명 자리에 카테고리 이름("식비")을 넣고 있었고, 사업자번호는 아예 없었다.
--
--   그래서 사용자가 "이 결제는 챌린지랑 상관없어요"(undo NOT_MINE)를 눌러도, 또 알림에
--   "도움 안 됐어요 — 내 소비 아님"을 남겨도, **그 신호가 갈 곳이 없었다.** 온보딩에서 뺀
--   결제는 가맹점 판정 성향(V12)으로 쌓이는데, 같은 뜻의 신호가 원장에서는 버려졌다.
--
-- 왜 상호명으로 역산하지 않나.
--   V12를 만들 때 이미 기각한 방법이다 — 상호에서 브랜드를 역산하면 75.8%만 복원되고,
--   나머지는 브랜드가 없는 게 맞는 독립 상호다. **놓치는 비용보다 잘못 묶는 비용이 크다.**
--   그래서 결제 키를 직접 들고 다닌다.
--
--   consumption.source_payment_id → user_payment.payment_id → business_number
--
-- 둘 다 NULL 허용이다. 더미 시드·직접 입력·카드 업로드로 만든 소비는 결제 키가 없고,
-- 해외 결제처럼 사업자번호가 없는 건도 있다. 그때는 성향에 묶지 않고 넘어가면 된다.

ALTER TABLE consumption ADD COLUMN source_payment_id VARCHAR(80);
ALTER TABLE guardian_transaction ADD COLUMN business_number VARCHAR(10);

-- 이미 쌓인 소비의 결제 키를 되찾는다. 마이데이터 투영은 결제와 (사용자·시각·금액)이
-- 정확히 같으므로 이 세 값으로 이을 수 있다. 한 사람이 같은 시각에 같은 금액을 두 번 쓴
-- 경우만 애매한데, 그때는 아무것도 잇지 않는다 — **틀리게 잇느니 비워 두는 편이 낫다.**
UPDATE consumption c
SET c.source_payment_id = (
    SELECT MIN(p.payment_id) FROM user_payment p
    WHERE p.user_id = c.user_id
      AND p.payment_date = c.occurred_at
      AND p.amount = c.amount
    HAVING COUNT(*) = 1
)
WHERE c.source = 'MYDATA' AND c.source_payment_id IS NULL;
