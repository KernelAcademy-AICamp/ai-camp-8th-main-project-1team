-- V1 baseline: 본체(backend) 운영 MySQL 스키마 (H2→MySQL 전환, Phase 6).
-- 엔티티에서 ddl-auto=create로 생성한 스키마를 mysqldump한 것 — Hibernate가 만드는 것과 1:1이라
-- 운영은 이 Flyway가 스키마 소유자, JPA는 validate(검증만). 신규 배포는 빈 DB에 이 V1을 처음부터 적용.
-- enum 컬럼은 Hibernate 7 + MySQLDialect가 네이티브 ENUM으로 생성(create/validate 대칭). 값 추가는 ddl-auto가
-- 아니라 후속 Flyway 마이그레이션(ALTER ... MODIFY)으로 명시 반영한다(§13 우려는 그 방식으로 해소).

SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE `alert` (
  `amount` decimal(15,2) NOT NULL,
  `deviation_score` double NOT NULL,
  `consumption_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `category_code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `matched_rules` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `app_user` (
  `consent_given` bit(1) NOT NULL,
  `goal_amount` decimal(15,2) NOT NULL,
  `goal_months` int NOT NULL,
  `monthly_income` decimal(15,2) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nickname` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `ci` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKk5fs4mf0q8h7mgh1pbnpws2f7` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `audit_batch` (
  `created_at` datetime(6) NOT NULL,
  `from_seq` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `to_seq` bigint NOT NULL,
  `tsa_gen_time` datetime(6) DEFAULT NULL,
  `batch_root` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `prev_batch_root` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `tsa_name` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `anchor_error` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tsa_query` mediumtext COLLATE utf8mb4_unicode_ci,
  `tsa_response` mediumtext COLLATE utf8mb4_unicode_ci,
  `anchor_status` enum('ANCHORED','FAILED','PENDING') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `audit_log` (
  `batch_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `seq` bigint NOT NULL,
  `event_type` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entry_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `prev_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKluea4jirtta5d80vfjadpjghh` (`seq`),
  KEY `idx_audit_seq` (`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKacatplu22q5d1andql2jbvjy7` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `consumption` (
  `amount` decimal(15,2) NOT NULL,
  `is_planned` bit(1) NOT NULL,
  `category_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `source` enum('CARD_UPLOAD','DUMMY_SEED','MYDATA','USER_INPUT') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_consumption_user_time` (`user_id`,`occurred_at`),
  KEY `FKi1su2wdr9w9mbyxx6m65aqikg` (`category_id`),
  CONSTRAINT `FKi1su2wdr9w9mbyxx6m65aqikg` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `coupon` (
  `benefit_amount` decimal(15,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `category_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` enum('DECLINED','OFFERED','USED') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_coupon_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `financial_product` (
  `expected_rate` decimal(5,2) NOT NULL,
  `min_join_amount` decimal(15,2) NOT NULL,
  `min_period_months` int NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_category_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `product_type` enum('CASHBACK_CARD','DEPOSIT','FUND','SAVINGS') COLLATE utf8mb4_unicode_ci NOT NULL,
  `risk_grade` enum('AGGRESSIVE','NEUTRAL','STABLE') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `goal_milestone` (
  `cost` decimal(15,2) NOT NULL,
  `sort_order` int NOT NULL,
  `goal_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `emoji` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_milestone_goal` (`goal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `impulse_saver_state` (
  `gift_balance` decimal(15,2) NOT NULL,
  `last_visit_date` date DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  `today_fraction` double NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `impulse_categories` varchar(400) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_iss_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `point_event` (
  `amount` decimal(15,2) NOT NULL,
  `goal_id` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `reason` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `memo` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` enum('DEPOSIT','WITHDRAWAL') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_point_user_time` (`user_id`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `report` (
  `period` varchar(7) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `body_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKkfdv8hwcg44rrle2xbe1m8rv3` (`user_id`,`period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `savings_goal` (
  `deadline_days` int NOT NULL,
  `priority` bit(1) NOT NULL,
  `sort_order` int NOT NULL,
  `target_amount` decimal(15,2) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `emoji` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `plan_cut_categories` varchar(400) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_goal_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `user_card` (
  `current_performance` int NOT NULL,
  `prev_performance` int NOT NULL,
  `requirement` int NOT NULL,
  `card_code` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `card_color` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `serial_number` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `company_name` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `card_name` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_card_serial` (`user_id`,`serial_number`),
  KEY `idx_user_card_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `user_payment` (
  `amount` int NOT NULL,
  `received_benefit` int NOT NULL,
  `card_code` bigint NOT NULL,
  `payment_date` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `card_serial` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `category1` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `category2` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payment_id` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `merchant_name` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`payment_id`),
  KEY `idx_user_payment_user_date` (`user_id`,`payment_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `user_spending_override` (
  `forced_waste` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `category2` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKo1ukmfyytyxl2cqkeaq6cv8o7` (`user_id`,`category2`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `wishlist_item` (
  `price` decimal(15,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `category_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `image_url` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_url` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source` enum('IMAGE','MANUAL','URL') COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('BOUGHT','CONSIDERING','NOT_BOUGHT') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_wish_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS=1;
