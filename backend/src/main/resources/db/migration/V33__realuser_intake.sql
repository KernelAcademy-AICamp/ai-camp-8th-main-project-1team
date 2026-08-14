-- 실사용자 신청 대기열 (설계서 Phase 3).
--
-- 실제 사람이 신원 3값과 카드별 명세서를 내면 여기 쌓이고, admin 이 승인하면 제공자로 넘어간다.
--
-- **여기가 실 개인정보를 보관하는 자리다.** 그래서 셋을 못박는다:
--   ① 신원과 명세서를 **암호화해서** 넣는다(KMS envelope). 컬럼이 VARBINARY 인 이유다.
--   ② 승인·반려 직후 **행을 지운다**. 대기열은 창고가 아니라 통로다.
--   ③ 아무도 손대지 않은 신청은 N일 뒤 자동 만료된다.
--
-- 요약 지표(건수·기간·합계 등)는 평문이다 — admin 이 승인을 판단하는 데 필요한 값이고,
-- 그 자체로는 누가 무엇을 샀는지 말하지 않는다. **원문을 보여주는 경로는 만들지 않는다.**

CREATE TABLE realuser_intake (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    -- 신청자에게 준 접수증. 신원을 담지 않는 무작위 값이라, 이것을 아는 사람만 자기 상태를 본다.
    ticket               VARCHAR(20)  NOT NULL,
    status               VARCHAR(12)  NOT NULL,      -- RECEIVED | IMPORTED | REJECTED
    -- 신원 3값(암호화). CI 는 승인 시점에 이 셋으로 계산하며 여기에 저장하지 않는다.
    name_enc             VARBINARY(512) NOT NULL,
    social7_enc          VARBINARY(512) NOT NULL,
    phone_enc            VARBINARY(512) NOT NULL,
    -- 카드별 명세서 묶음(JSON, 암호화). 카드사·상품·표시명·5칸 CSV 가 들어 있다.
    payload_enc          LONGBLOB     NOT NULL,
    -- ── 요약 지표 (admin 이 보는 것 전부) ────────────────────────────────
    -- 이름은 마스킹해서 둔다(홍○동). 승인에 필요한 것은 "누구인지"가 아니라 "어떤 배치인지"다.
    masked_name          VARCHAR(40)  NOT NULL,
    card_count           INT          NOT NULL,
    row_count            INT          NOT NULL,
    rejected_row_count   INT          NOT NULL,
    total_amount         BIGINT       NOT NULL,
    refund_count         INT          NOT NULL,
    refund_amount        BIGINT       NOT NULL,
    with_business_number INT          NOT NULL,
    distinct_merchants   INT          NOT NULL,
    period_from          DATE         NULL,
    period_to            DATE         NULL,
    -- ── 처리 기록 ───────────────────────────────────────────────────────
    submitted_at         DATETIME(6)  NOT NULL,
    submitted_ip         VARCHAR(45)  NULL,
    expires_at           DATETIME(6)  NOT NULL,
    decided_at           DATETIME(6)  NULL,
    -- 누가 승인했는가. **계정명이 사실로 남는다** — 공용 계정을 두지 않는 이유가 이것이다.
    decided_by           VARCHAR(40)  NULL,
    -- 반려 사유는 코드로만 고른다. 자유 입력을 두면 내용을 봤다는 뜻이 된다.
    reject_reason        VARCHAR(40)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_intake_ticket (ticket),
    KEY idx_intake_status (status, submitted_at),
    KEY idx_intake_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
