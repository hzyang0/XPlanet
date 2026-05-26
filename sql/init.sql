-- xplanet schema
CREATE DATABASE IF NOT EXISTS xplanet DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE xplanet;

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(64)  NOT NULL,
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

DROP TABLE IF EXISTS `article_like`;
CREATE TABLE `article_like` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `article_id`  BIGINT UNSIGNED NOT NULL,
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1=有效,0=已取消',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_article` (`user_id`, `article_id`),
    KEY `idx_article_id` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章点赞表';

-- 测试数据
INSERT INTO `user` (`id`, `username`, `nickname`) VALUES
(1, 'alice', 'Alice'),
(2, 'bob',   'Bob'),
(100, 'demo', 'Demo User');

INSERT INTO `article` (`id`, `author_id`, `title`, `content`, `tags`) VALUES
(1, 1, 'Caffeine + Redis 二级缓存实战', '本文介绍如何构建抗热点的二级缓存架构...', 'cache,redis,caffeine'),
(2, 1, 'RocketMQ 批量消费削峰',         '通过缓冲合并把同一文章的 N 次点赞合成 1 次 update...', 'mq,rocketmq'),
(3, 2, 'Cache Aside 延迟双删全解析',    '为什么必须双删,以及第二删延迟到底设多少...', 'cache,consistency'),
(100, 1, '【热点】高并发缓存击穿应对',     '这是一篇模拟热点文章,用于演示缓存击穿时的分布式锁重建', 'cache,hotkey');
