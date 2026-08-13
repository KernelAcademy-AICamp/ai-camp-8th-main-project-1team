-- 옛 평문 신원을 비운다 — V13 이 더한 암호문·지문으로 갈아탄 뒤의 마무리.
--
-- ## 여기까지 온 과정
--
--     V13   암호문 3칸 + 지문 2칸을 **더한다**. 평문은 그대로 둔다.
--     백필  기동 러너가 4,513행을 채운다.
--     확인  운영에서 본인인증 20명 중 20명 통과(2026-08-13).
--     V14   이제 평문을 비운다.
--
-- 확인을 먼저 하고 지우는 순서다. 반대로 했으면 되돌릴 것이 없었다.
--
-- ## 왜 DROP COLUMN 이 아닌가
--
-- 칸을 지우는 마이그레이션은 되돌릴 수 없다 — 롤백해도 스키마는 안 돌아온다(`guard-main` 이
-- 그래서 막는다). 값만 비우면 **칸은 남고 내용만 사라진다.** 목적은 "평문을 없애는 것"이지
-- "칸을 없애는 것"이 아니었다. 칸 정리는 한참 뒤 별도로 한다.
--
-- ## 조건이 이 파일의 전부다
--
-- `WHERE` 절이 없으면 백필이 안 끝난 행의 평문까지 지워 **암호문도 평문도 없는 행**이 된다.
-- 그 사람은 영영 로그인하지 못하고 복구할 원본도 없다. 실제로 백필이 커밋되지 않아 한 행도
-- 안 써진 채 "10만 행 채웠다"고 로그를 남긴 적이 있다(2026-08-13, 배포 전 실측에서 잡음).
-- **로그를 믿지 않고 칸을 믿는다.**
--
-- 세 칸 모두 NOT NULL 이라 NULL 대신 빈 문자열을 넣는다.

UPDATE mydata_user
   SET mydata_user_name          = '',
       mydata_user_social_number = '',
       mydata_user_phone_number  = ''
 WHERE mydata_user_name_enc   IS NOT NULL
   AND mydata_user_social_enc IS NOT NULL
   AND mydata_user_phone_enc  IS NOT NULL
   AND mydata_user_phone_bi   IS NOT NULL
   AND mydata_user_person_bi  IS NOT NULL;
