USE xplanet;
SET NAMES utf8mb4;

SET @has_password_hash = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='xplanet' AND TABLE_NAME='user' AND COLUMN_NAME='password_hash');
SET @add_password_hash = IF(@has_password_hash = 0, 'ALTER TABLE `user` ADD COLUMN password_hash VARCHAR(100) NOT NULL DEFAULT '''' AFTER username', 'SELECT 1');
PREPARE add_password_hash_stmt FROM @add_password_hash;
EXECUTE add_password_hash_stmt;
DEALLOCATE PREPARE add_password_hash_stmt;
UPDATE `user` SET password_hash='$2a$10$/xQHiPJ8e3K/3ftCUHUcNeI18Iuy1Gj0M5g4i6sQU6z3Vx573ewii' WHERE password_hash='' OR username IN ('alice','bob','carol');

CREATE TABLE IF NOT EXISTS product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, name VARCHAR(128) NOT NULL, subtitle VARCHAR(255) NOT NULL DEFAULT '',
  category VARCHAR(32) NOT NULL, price DECIMAL(10,2) NOT NULL, stock INT NOT NULL DEFAULT 0, sales INT NOT NULL DEFAULT 0,
  cover VARCHAR(32) NOT NULL DEFAULT '🛍️', status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_product_category_status(category,status), KEY idx_product_name(name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cart_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, user_id BIGINT UNSIGNED NOT NULL, product_id BIGINT UNSIGNED NOT NULL, quantity INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_cart_user_product(user_id,product_id), KEY idx_cart_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS normal_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, order_no CHAR(36) NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
  total_amount DECIMAL(10,2) NOT NULL, status VARCHAR(16) NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_normal_order_no(order_no), KEY idx_normal_order_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS normal_order_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, order_id BIGINT UNSIGNED NOT NULL, product_id BIGINT UNSIGNED NOT NULL,
  product_name VARCHAR(128) NOT NULL, price DECIMAL(10,2) NOT NULL, quantity INT NOT NULL,
  PRIMARY KEY (id), KEY idx_normal_order_item_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO product (id,name,subtitle,category,price,stock,sales,cover,status) VALUES
  (10001,'机械键盘 Pro','三模热插拔，限量现货','数码',499.00,200,128,'⌨️',1),
  (10002,'降噪耳机 Air','40dB 主动降噪，通勤必备','数码',329.00,180,96,'🎧',1),
  (10003,'轻量双肩包','15.6 英寸电脑收纳，通勤防泼水','出行',159.00,120,62,'🎒',1),
  (10004,'保温咖啡杯','316 不锈钢，长效保温','生活',89.00,300,210,'☕',1),
  (10005,'人体工学鼠标','静音按键，多设备切换','数码',229.00,150,73,'🖱️',1),
  (10006,'桌面阅读灯','无频闪，三档调光','生活',119.00,260,84,'💡',1);
