-- 지킴이 말수 — 사용자가 정하는 하루 알림 상한 (마이 > 설정 > 지킴이 말수 설정).
--
-- 지금까지는 `finntech.guardian.notification.daily-push-limit`(전역 2건)만 있었다. 그 값은
-- 운영이 정하는 기본값이고, "나한테는 많다/적다"는 사람마다 다르다. 사람마다 다른 값을
-- 설정 파일에 둘 수는 없으므로 사용자 행에 둔다.
--
-- 0 은 '설정 안 함'이며 전역 기본값을 따른다 — 새 열이 생겼다고 기존 사용자의 알림이
-- 갑자기 늘거나 줄면 안 된다.
ALTER TABLE app_user
    ADD COLUMN notify_daily_limit INT NOT NULL DEFAULT 0;
