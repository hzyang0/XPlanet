-- 已有数据库的一次性迁移脚本。
-- 执行前请备份；仅在 user 表还没有 password_hash 字段时执行一次。
USE xplanet;

ALTER TABLE `user`
    ADD COLUMN `password_hash` VARCHAR(100) NULL AFTER `username`;

UPDATE `user`
SET `password_hash` = '{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG'
WHERE `password_hash` IS NULL OR `password_hash` = '';

ALTER TABLE `user`
    MODIFY COLUMN `password_hash` VARCHAR(100) NOT NULL;

-- 迁移后的本地演示密码为 password。首次登录后应通过后续改密功能替换。
