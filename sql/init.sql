CREATE DATABASE IF NOT EXISTS xplanet DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SET NAMES utf8mb4;
USE xplanet;

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(64) NOT NULL DEFAULT '',
  avatar VARCHAR(255) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  subtitle VARCHAR(255) NOT NULL DEFAULT '',
  category VARCHAR(32) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  sales INT NOT NULL DEFAULT 0,
  cover VARCHAR(32) NOT NULL DEFAULT '🛍️',
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_product_category_status(category,status), KEY idx_product_name(name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cart_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  quantity INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_cart_user_product(user_id,product_id), KEY idx_cart_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS normal_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no CHAR(36) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  total_amount DECIMAL(10,2) NOT NULL,
  status VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_normal_order_no(order_no), KEY idx_normal_order_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS normal_order_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  product_name VARCHAR(128) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  quantity INT NOT NULL,
  PRIMARY KEY (id), KEY idx_normal_order_item_order(order_id)
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

INSERT IGNORE INTO `user` (id,username,password_hash,nickname) VALUES
  (1,'alice','$2a$10$/xQHiPJ8e3K/3ftCUHUcNeI18Iuy1Gj0M5g4i6sQU6z3Vx573ewii','Alice'),
  (2,'bob','$2a$10$/xQHiPJ8e3K/3ftCUHUcNeI18Iuy1Gj0M5g4i6sQU6z3Vx573ewii','Bob'),
  (3,'carol','$2a$10$/xQHiPJ8e3K/3ftCUHUcNeI18Iuy1Gj0M5g4i6sQU6z3Vx573ewii','Carol');
INSERT IGNORE INTO product (id,name,subtitle,category,price,stock,sales,cover,status) VALUES
  (10001,'机械键盘 Pro','三模热插拔，限量现货','数码',499.00,200,128,'⌨️',1),
  (10002,'降噪耳机 Air','40dB 主动降噪，通勤必备','数码',329.00,180,96,'🎧',1),
  (10003,'轻量双肩包','15.6 英寸电脑收纳，通勤防泼水','出行',159.00,120,62,'🎒',1),
  (10004,'保温咖啡杯','316 不锈钢，长效保温','生活',89.00,300,210,'☕',1),
  (10005,'人体工学鼠标','静音按键，多设备切换','数码',229.00,150,73,'🖱️',1),
  (10006,'桌面阅读灯','无频闪，三档调光','生活',119.00,260,84,'💡',1);
INSERT IGNORE INTO seckill_activity (id,sku_id,title,total_stock,available_stock,start_time,end_time,status)
VALUES (1,10001,'机械键盘限量秒杀',100,100,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 7 DAY),1);
