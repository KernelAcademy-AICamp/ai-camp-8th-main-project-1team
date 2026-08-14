-- 실 신원 컬럼 암호화 — 이름·주민앞7·전화를 KMS envelope 로 감싼다.
--
-- ## 왜 뒤늦게 오는가
--
-- 본체(backend)는 신청 대기열을 처음부터 암호화했다(`realuser_intake` 의 `*_enc`). 그런데
-- **승인 뒤 실제로 저장되는 이쪽이 평문이었다.** 2026-08-13 운영 실측:
--
--     mydata_user_name           varchar(40)   ← 실제 이름
--     mydata_user_social_number  varchar(20)   ← 주민번호 앞자리
--     mydata_user_phone_number   varchar(20)   ← 전화번호
--     4,513행
--
-- 대기열은 승인되면 지워지므로 **오래 남는 쪽이 오히려 평문**이었다.
--
-- ## 왜 한 번에 안 바꾸고 칸을 더하는가
--
-- 본인인증 경로 전체가 이 표를 지나간다. 한 번에 갈아치우면 백필이 어긋나는 순간
-- **아무도 로그인하지 못한다.** 그래서 두 걸음으로 간다:
--
--     V13 (여기)  암호문 칸과 지문 칸을 **더한다**. 옛 평문 칸은 그대로 둔다.
--                 기동 시 백필이 채우고, 조회는 지문으로 바뀐다.
--     V14 (다음)  백필과 로그인을 실측으로 확인한 뒤 **평문 칸을 비운다**.
--
-- 중간 상태에서 두 벌이 공존하는 동안에는 평문이 남아 있다 — 그 사실을 알고 넘어가는 것이지
-- 안전해서가 아니다. V14 까지 가야 끝난다.
--
-- ## 왜 지문(blind index)이 필요한가
--
-- 본인인증은 `findByPhoneNumber` 와 `findByNameAndSocial7` 로 **정확일치** 조회를 한다.
-- 암호문은 IV 가 매번 달라 같은 값도 다르게 저장되므로 그대로는 못 찾는다. 그래서
-- `HMAC-SHA256(pepper, 정규화값)` 을 따로 둔다 — 같은 입력이면 항상 같아 인덱스가 걸리고,
-- pepper 없이는 되돌릴 수 없어 **이 칸만 훔쳐도 원문을 얻지 못한다**.
--
-- **결정론 암호화(고정 IV)로 대신하지 않는다.** 그렇게 하면 같은 값이 같은 암호문이 되어
-- "이 둘은 같은 사람"이 복호화 없이 드러난다. 지문은 조회에만 쓰고 표시에는 안 쓴다.
--
-- ## 칸 크기
--
-- 암호문은 `[버전 1][IV 12][본문+GCM태그]` 라 원문보다 커진다. 40자 이름이 UTF-8 로 최대
-- 120바이트, 거기에 29바이트가 붙어도 512 로 넉넉하다. 지문은 SHA-256 hex 라 정확히 64자다.

ALTER TABLE mydata_user
    ADD COLUMN mydata_user_name_enc   VARBINARY(512) NULL,
    ADD COLUMN mydata_user_social_enc VARBINARY(512) NULL,
    ADD COLUMN mydata_user_phone_enc  VARBINARY(512) NULL,
    -- 조회 전용 지문. NULL 은 "아직 백필 안 됨"이라는 뜻이라, 백필 러너가 이 값으로 대상을 찾는다.
    ADD COLUMN mydata_user_phone_bi   CHAR(64) NULL,
    ADD COLUMN mydata_user_person_bi  CHAR(64) NULL;

-- 전화번호는 한 사람당 하나라 유일해야 맞지만 **UNIQUE 를 걸지 않는다.**
-- 생성 데이터에 같은 번호가 섞여 있으면 백필이 통째로 실패하고, 그러면 로그인이 막힌다.
-- 유일성은 도메인 규칙이지 이 마이그레이션이 강제할 일이 아니다.
CREATE INDEX idx_mydata_user_phone_bi  ON mydata_user (mydata_user_phone_bi);
CREATE INDEX idx_mydata_user_person_bi ON mydata_user (mydata_user_person_bi);
