-- 컨테이너 MySQL이 처음 뜰 때 한 번만 실행된다(/docker-entrypoint-initdb.d).
-- 두 개의 DB가 필요하다: 앱 원장(finntech)과 마이데이터 제공자 원장(finntech_mydata).
-- 스키마 자체는 두 모듈의 Flyway가 각자 만든다 — 여기서는 '그릇'과 권한만 만든다.
-- RDS를 쓸 때는 이 파일이 실행되지 않으므로 같은 내용을 관리자 계정으로 한 번 수동 실행한다.
CREATE DATABASE IF NOT EXISTS finntech
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS finntech_mydata
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- MYSQL_USER로 만들어진 계정은 MYSQL_DATABASE 하나에만 권한이 붙는다. 나머지 하나를 열어준다.
GRANT ALL PRIVILEGES ON finntech.* TO 'finntech'@'%';
GRANT ALL PRIVILEGES ON finntech_mydata.* TO 'finntech'@'%';
FLUSH PRIVILEGES;
