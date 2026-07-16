-- xplanet schema
CREATE DATABASE IF NOT EXISTS xplanet DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE xplanet;

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(64)  NOT NULL,
    `password_hash` VARCHAR(100) NOT NULL,
    `nickname`    VARCHAR(64)  NOT NULL DEFAULT '',
    `avatar`      VARCHAR(255) NOT NULL DEFAULT '',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

DROP TABLE IF EXISTS `article`;
CREATE TABLE `article` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `author_id`   BIGINT UNSIGNED NOT NULL,
    `title`       VARCHAR(200)    NOT NULL,
    `content`     MEDIUMTEXT      NOT NULL,
    `tags`        VARCHAR(255)    NOT NULL DEFAULT '',
    `like_count`  BIGINT          NOT NULL DEFAULT 0,
    `view_count`  BIGINT          NOT NULL DEFAULT 0,
    `deleted`     TINYINT         NOT NULL DEFAULT 0,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_author_id` (`author_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表';

DROP TABLE IF EXISTS `article_change_outbox`;
CREATE TABLE `article_change_outbox` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_id`        CHAR(36)        NOT NULL,
    `article_id`      BIGINT UNSIGNED NOT NULL,
    `operation`       VARCHAR(16)     NOT NULL,
    `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待发送,1=发送中,2=已发送',
    `retry_count`     INT             NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `locked_by`       VARCHAR(64)     NULL,
    `locked_until`    DATETIME        NULL,
    `last_error`      VARCHAR(500)    NULL,
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `sent_time`       DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_change_event` (`event_id`),
    KEY `idx_article_change_publish` (`status`, `next_retry_time`, `id`),
    KEY `idx_article_change_lease` (`status`, `locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章缓存失效Transactional Outbox';

DROP TABLE IF EXISTS `like_relation`;
CREATE TABLE `like_relation` (
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `article_id`  BIGINT UNSIGNED NOT NULL,
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1=有效,0=已取消',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `article_id`),
    KEY `idx_article_status` (`article_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞关系事实表,interaction服务所有';

DROP TABLE IF EXISTS `like_outbox`;
CREATE TABLE `like_outbox` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_id`        CHAR(36)        NOT NULL,
    `user_id`         BIGINT UNSIGNED NOT NULL,
    `article_id`      BIGINT UNSIGNED NOT NULL,
    `delta`           TINYINT         NOT NULL COMMENT '1=点赞,-1=取消',
    `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待发送,1=发送中,2=已发送',
    `retry_count`     INT             NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `locked_by`       VARCHAR(64)     NULL,
    `locked_until`    DATETIME        NULL,
    `last_error`      VARCHAR(500)    NULL,
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `sent_time`       DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_like_outbox_event` (`event_id`),
    KEY `idx_like_outbox_publish` (`status`, `next_retry_time`, `id`),
    KEY `idx_like_outbox_lease` (`status`, `locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞事件Transactional Outbox';

DROP TABLE IF EXISTS `like_count_delta`;
CREATE TABLE `like_count_delta` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_id`     CHAR(36)        NOT NULL,
    `article_id`   BIGINT UNSIGNED NOT NULL,
    `delta`        TINYINT         NOT NULL COMMENT '1或-1',
    `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待投影,1=已应用,2=拒绝',
    `error`        VARCHAR(255)    NULL,
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `applied_time` DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_like_delta_event` (`event_id`),
    KEY `idx_like_delta_pending` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章点赞计数持久化投影队列';

DROP TABLE IF EXISTS `comment`;
CREATE TABLE `comment` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `article_id`  BIGINT UNSIGNED NOT NULL,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父评论id,0表示顶级评论',
    `content`     VARCHAR(1000)   NOT NULL,
    `deleted`     TINYINT         NOT NULL DEFAULT 0,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_article` (`article_id`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';

-- 测试数据
-- 本地演示账号的初始密码均为 password；数据库只保存 bcrypt 哈希。
INSERT INTO `user` (`id`, `username`, `password_hash`, `nickname`) VALUES
(1, 'alice', '{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Alice'),
(2, 'bob',   '{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Bob'),
(100, 'demo', '{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Demo User');

INSERT INTO `article` (`id`, `author_id`, `title`, `content`, `tags`) VALUES
(1, 1, 'Caffeine + Redis 二级缓存实战', '本文介绍如何构建抗热点的二级缓存架构...', 'cache,redis,caffeine'),
(2, 1, 'RocketMQ 批量消费削峰',         '通过缓冲合并把同一文章的 N 次点赞合成 1 次 update...', 'mq,rocketmq'),
(3, 2, 'Cache Aside 延迟双删全解析',    '为什么必须双删,以及第二删延迟到底设多少...', 'cache,consistency'),
(100, 1, '【热点】高并发缓存击穿应对',     '这是一篇模拟热点文章,用于演示缓存击穿时的分布式锁重建', 'cache,hotkey');

INSERT INTO `comment` (`article_id`, `user_id`, `parent_id`, `content`) VALUES
(1, 2, 0, '写得很清楚,二级缓存这块受教了'),
(1, 1, 1, '谢谢,后面会补一篇一致性的'),
(1, 100, 0, 'Caffeine 的 W-TinyLFU 那段能再展开讲讲吗'),
(2, 2, 0, '削峰批量合并这个思路很实用');
