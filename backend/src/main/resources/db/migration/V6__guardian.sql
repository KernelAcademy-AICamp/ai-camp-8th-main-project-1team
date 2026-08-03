-- V6 — 지킴이 Agent 스키마 (설계서 06_지킴이_Agent_설계.md · 구현 backend/src/main/java/com/finntech/guardian/)
--
-- 지킴이 커밋(cb24cfd)이 엔티티 9종만 추가하고 이 마이그레이션을 빠뜨려, mysql 프로파일
-- (ddl-auto=validate + Flyway가 스키마 소유자, §3-B)에서 기동이 막혔다:
--   Schema validation: missing table [guardian_challenge]
--
-- 문장은 Hibernate가 엔티티 메타데이터에서 낸 DDL을 **그대로** 고정한 것이다. 손으로 옮겨 적으면
-- 타입 하나가 어긋나는 순간 validate가 다시 막히므로, 생성물을 그대로 두는 편이 안전하다.
-- enum 컬럼을 네이티브 enum으로 두는 것은 V1__baseline.sql의 관행과 같다.

create table guardian_challenge (buffer_ratio float(53) not null, end_date date not null, grass_protected_days integer not null, grass_streak integer not null, no_spend_streak integer not null, no_spend_streak_best integer not null, round_no integer not null, start_date date not null, baseline_amount bigint not null, challenge_cap bigint not null, closed_at datetime(6), created_at datetime(6) not null, id bigint not null auto_increment, reward_price bigint, settled_at datetime(6), spent_amount bigint not null, target_saving bigint not null, user_id bigint not null, reward_name varchar(60), categories varchar(400) not null, sanctuary_categories varchar(400), state enum ('ABANDONED','ACTIVE','AT_RISK','CLOSED','EXCEEDED','FAILED','PARTIAL','RESTART_OFFER','REWARD_PENDING','SETTLING','SETUP','SHORTFALL','SUCCESS') not null, primary key (id)) engine=InnoDB;
create table guardian_daily_verdict (allowed_ratio float(53) not null, grant_object bit not null, no_spend_streak integer not null, pace_ratio float(53) not null, rerolled bit not null, spent_ratio float(53) not null, verdict_date date not null, weight_common float(53), weight_epic float(53), weight_rare float(53), ceremony_seen_at datetime(6), challenge_id bigint not null, created_at datetime(6) not null, id bigint not null auto_increment, spent_at_date bigint not null, user_id bigint not null, reason_code varchar(40), granted_object_id varchar(60), ceremony_message varchar(200), granted_grade enum ('COMMON','EPIC','RARE'), result enum ('NO_GRANT','NO_SPEND_DAY','OFF_PACE_DAY','ON_PACE_DAY') not null, primary key (id)) engine=InnoDB;
create table guardian_demo_clock (demo_mode bit not null, id bigint not null auto_increment, updated_at datetime(6) not null, user_id bigint not null, virtual_offset_seconds bigint not null, primary key (id)) engine=InnoDB;
create table guardian_items (auto_use_grass_guard bit not null, exemption integer not null, grass_guard integer not null, mission_change integer not null, point_balance integer not null, id bigint not null auto_increment, updated_at datetime(6) not null, user_id bigint not null, primary key (id)) engine=InnoDB;
create table guardian_notification (is_fallback bit not null, challenge_id bigint, feedback_at datetime(6), id bigint not null auto_increment, read_at datetime(6), sent_at datetime(6) not null, transaction_id bigint, user_id bigint not null, case_id varchar(10) not null, prompt_version varchar(20), title varchar(40), body varchar(200), key_phrases varchar(400), delivery enum ('INAPP','MODAL','PUSH','SILENT') not null, feedback enum ('NOT_USEFUL','USEFUL'), feedback_reason enum ('ALREADY_KNEW','NOT_MINE','TIMING','TONE','TOO_OFTEN'), phrasing_mode enum ('DEFINITIVE','TENTATIVE'), suppressed_reason enum ('BUDGET','CASE_SILENT','COOLDOWN','NIGHT'), tone enum ('FACT_RESET','MORNING_CEREMONY','NEUTRAL_ASK','NUDGE_AHEAD','PATTERN_HINT','PRAISE','REWARD_WARNING','SOFT_REMINDER','WEEKLY_RECAP'), primary key (id)) engine=InnoDB;
create table guardian_point_event (amount integer not null, capped_amount integer not null, week_start date, challenge_id bigint, confirmed_at datetime(6) not null, id bigint not null auto_increment, source_ref bigint, user_id bigint not null, type enum ('LABELING','MONTHLY_COMPLETE','RISK_DEFENSE','WEEKLY_MISSION') not null, primary key (id)) engine=InnoDB;
create table guardian_room_object (acquired_date date not null, slot_index integer, created_at datetime(6) not null, id bigint not null auto_increment, user_id bigint not null, reason_code varchar(40), object_id varchar(60) not null, grade enum ('COMMON','EPIC','RARE') not null, source enum ('DAILY','GIFT','SHOP') not null, primary key (id)) engine=InnoDB;
create table guardian_transaction (category_confidence float(53), counted_date date, is_demo bit not null, is_micro bit not null, amount bigint not null, challenge_id bigint, id bigint not null auto_increment, mcc varchar(8), occurred_at datetime(6) not null, original_tx_id bigint, received_at datetime(6) not null, source_consumption_id bigint, undo_deadline datetime(6), undone_at datetime(6), user_id bigint not null, category varchar(40), merchant_display_name varchar(120), merchant_name varchar(120) not null, state enum ('COUNTED','EXCLUDED','EXEMPTED','PENDING_CATEGORY') not null, tx_type enum ('EXPENSE','INCOME','REFUND') not null, undo_reason enum ('EXEMPTION','NOT_MINE'), primary key (id)) engine=InnoDB;
create table guardian_weekly_mission (achieved bit, period_end date not null, period_start date not null, threshold integer not null, challenge_id bigint, created_at datetime(6) not null, evaluated_at datetime(6), id bigint not null auto_increment, user_id bigint not null, category varchar(40), condition_type enum ('CATEGORY_COUNT_MAX','LABELING_COUNT_MIN','NO_SPEND_STREAK_MIN') not null, primary key (id)) engine=InnoDB;

create index idx_gch_user_state on guardian_challenge (user_id, state);
create index idx_gverdict_user_date on guardian_daily_verdict (user_id, verdict_date);
alter table guardian_daily_verdict add constraint uk_gverdict_challenge_date unique (challenge_id, verdict_date);
alter table guardian_demo_clock add constraint idx_gclock_user unique (user_id);
alter table guardian_items add constraint idx_gitems_user unique (user_id);
create index idx_gnoti_user_sent on guardian_notification (user_id, sent_at);
create index idx_gnoti_case on guardian_notification (challenge_id, case_id, sent_at);
create index idx_gnoti_budget on guardian_notification (user_id, delivery, sent_at);
create index idx_gpoint_week on guardian_point_event (user_id, week_start);
create index idx_groom_user_slot on guardian_room_object (user_id, slot_index);
alter table guardian_room_object add constraint uk_groom_user_object unique (user_id, object_id);
create index idx_gtx_challenge_date on guardian_transaction (challenge_id, counted_date);
create index idx_gtx_user_received on guardian_transaction (user_id, received_at);
create index idx_gtx_undo on guardian_transaction (state, undo_deadline);
create index idx_gmission_period on guardian_weekly_mission (user_id, period_start);
