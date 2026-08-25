CREATE DATABASE IF NOT EXISTS xplanet DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE xplanet;

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  nickname VARCHAR(64) NOT NULL DEFAULT '',
  avatar VARCHAR(255) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill_activity (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sku_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(128) NOT NULL,
  total_stock INT NOT NULL,
  available_stock INT NOT NULL,
  start_time DATETIME NOT NULL, end_time DATETIME NOT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active,0=closed',
  PRIMARY KEY (id), KEY idx_activity_status_time(status,start_time,end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_no CHAR(36) NOT NULL,
  activity_id BIGINT UNSIGNED NOT NULL, sku_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'QUEUED/SUCCEEDED/FAILED',
  order_id BIGINT UNSIGNED NULL, fail_reason VARCHAR(255) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_request_no(request_no), UNIQUE KEY uk_activity_user(activity_id,user_id), KEY idx_request_status(status,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL, activity_id BIGINT UNSIGNED NOT NULL, sku_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_order_event(event_id), UNIQUE KEY uk_order_activity_user(activity_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill_order_outbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL, request_id BIGINT UNSIGNED NOT NULL, payload TEXT NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/RETRY/SENT', retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NOT NULL, last_error VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_time DATETIME NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_outbox_event(event_id), KEY idx_outbox_retry(status,next_retry_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `user` (id,username,nickname) VALUES (1,'alice','Alice'),(2,'bob','Bob'),(3,'carol','Carol');
INSERT IGNORE INTO seckill_activity (id,sku_id,title,total_stock,available_stock,start_time,end_time,status)
VALUES (1,10001,'机械键盘限量秒杀',100,100,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 7 DAY),1);
