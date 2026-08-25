-- V41 이 옮겨 놓은 자리표가 **되살아난 것**을 다시 옮긴다.
--
-- V41 은 이 DB(finntech)만 고쳤고 제공자 DB(finntech_mydata)를 안 고쳤다. 백엔드는 연동할
-- 때마다 제공자에서 결제를 다시 가져오므로, 원본이 그대로인 채 한 사용자를 재연동하자
-- **137건이 642004 로 돌아왔다**(2026-08-25 운영 실측).
--
-- 원본은 제공자 쪽 V15 가 고친다. 여기서는 그 사이에 이미 들어온 것을 정리한다.
-- 그리고 **다시는 안 들어오게** MyDataLinkService 가 들어오는 값을 정규화한다 —
-- 두 모듈의 마이그레이션 실행 순서는 보장되지 않으므로 그 방어가 필요하다.

UPDATE user_payment    SET ksic_code         = '000000' WHERE ksic_code         = '642004';
UPDATE spending_ledger SET nts_industry_code = '000000' WHERE nts_industry_code = '642004';
