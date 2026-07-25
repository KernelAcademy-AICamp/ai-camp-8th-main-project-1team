-- V5: 절약 후보 선택 추적(⑤). 사용자가 "이 소비를 줄이겠다"고 고른 후보와 월말 재검증 결과를 보관한다.
-- 개인 소비 결정 정보이므로 삭제권(방침6) 대상 — PrivacyService.eraseUserData에서 함께 파기한다.
-- 타입은 CutCandidateSelection 엔티티와 ddl-auto=validate(mysql)로 정합해야 하므로 baseline 규약(bigint/datetime(6)/bit(1)/varchar+collate)을 따른다.
CREATE TABLE cut_candidate_selection (
    id             bigint       NOT NULL AUTO_INCREMENT,
    user_id        bigint       NOT NULL,
    category2      varchar(30)  COLLATE utf8mb4_unicode_ci NOT NULL,
    type           varchar(16)  COLLATE utf8mb4_unicode_ci NOT NULL,
    target_saving  bigint       NOT NULL,
    baseline_spend bigint       NOT NULL,
    selected_at    datetime(6)  NOT NULL,
    status         varchar(16)  COLLATE utf8mb4_unicode_ci NOT NULL,
    verified_at    datetime(6),
    actual_spend   bigint,
    improved       bit(1),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_cut_selection_user ON cut_candidate_selection (user_id, status);
