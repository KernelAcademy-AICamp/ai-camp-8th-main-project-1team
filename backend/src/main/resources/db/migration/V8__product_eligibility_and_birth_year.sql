-- V8 — 취향·추천 에이전트(③) 스키마 보정
--
-- PR #36(24ec08b)이 엔티티 두 곳을 바꾸면서 마이그레이션을 빠뜨려, mysql 프로파일
-- (ddl-auto=validate + Flyway가 스키마 소유자, §3-B)에서 운영 기동이 막혔다:
--   Schema validation: missing column [birth_year] in table [app_user]
-- 그 컬럼을 채우면 다음은 product_eligibility 테이블이 없어 같은 자리에서 다시 막힌다.
-- V6(지킴이)과 같은 유형의 누락이며, 같은 방식으로 보정한다.
--
-- 문장은 엔티티 메타데이터가 내는 타입을 그대로 옮긴 것이다. 타입이 하나라도 어긋나면
-- validate가 다시 막히므로 length·nullable·정밀도(datetime(6))를 엔티티와 1:1로 맞췄다.

-- 출생연도 — 상품 자격(만 나이) 판정에만 쓰고, 미연동이면 null이다.
alter table app_user add column birth_year integer;

-- 금융상품 가입자격 라벨. 사용자에 매이지 않는 공개 공시 정보라 삭제권 파기 대상이 아니다.
create table product_eligibility (
    id bigint not null auto_increment,
    prdt_key varchar(80) not null,
    join_member_hash varchar(64) not null,
    min_age integer,
    max_age integer,
    special_status varchar(120),
    source varchar(10) not null,
    labeled_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

-- 상품 식별키는 하나만 존재한다(금리가 바뀌어도 유지되는 `금융회사코드:상품코드`).
alter table product_eligibility add constraint uk_product_eligibility_prdt_key unique (prdt_key);
