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

-- AI 控制面初始结构与 V005 保持一致；CREATE IF NOT EXISTS 迁移可安全验证新数据卷。
DROP TABLE IF EXISTS `consumer_inbox`;
DROP TABLE IF EXISTS `ai_outbox`;
DROP TABLE IF EXISTS `model_usage`;
DROP TABLE IF EXISTS `report_citation`;
DROP TABLE IF EXISTS `ai_report`;
DROP TABLE IF EXISTS `evidence_chunk`;
DROP TABLE IF EXISTS `source_document`;
DROP TABLE IF EXISTS `ai_run_step`;
DROP TABLE IF EXISTS `ai_run`;
DROP TABLE IF EXISTS `ai_task`;

CREATE TABLE `ai_task` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `user_id` BIGINT UNSIGNED NOT NULL,
    `idempotency_key` VARCHAR(128) NOT NULL, `question` VARCHAR(2000) NOT NULL,
    `status` VARCHAR(32) NOT NULL, `current_run_id` CHAR(36) NOT NULL, `version` INT NOT NULL DEFAULT 0,
    `max_sources` INT NOT NULL DEFAULT 5, `max_tool_calls` INT NOT NULL DEFAULT 10,
    `max_tokens` INT NOT NULL DEFAULT 8000, `deadline_seconds` INT NOT NULL DEFAULT 300,
    `last_error` VARCHAR(1000) NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_ai_task_idempotency` (`user_id`, `idempotency_key`),
    KEY `idx_ai_task_user_status` (`user_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI研究任务事实表';

CREATE TABLE `ai_run` (
    `run_id` CHAR(36) NOT NULL, `task_id` BIGINT UNSIGNED NOT NULL, `status` VARCHAR(32) NOT NULL,
    `current_node` VARCHAR(64) NOT NULL, `attempt` INT NOT NULL DEFAULT 1, `last_error` VARCHAR(1000) NULL,
    `started_time` DATETIME NULL, `finished_time` DATETIME NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`run_id`), KEY `idx_ai_run_task` (`task_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI任务执行实例';

CREATE TABLE `ai_run_step` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `run_id` CHAR(36) NOT NULL,
    `node_name` VARCHAR(64) NOT NULL, `input_hash` CHAR(64) NOT NULL, `state_version` INT NOT NULL DEFAULT 1,
    `status` VARCHAR(32) NOT NULL, `checkpoint_json` MEDIUMTEXT NULL,
    `duration_ms` BIGINT NULL, `error_code` VARCHAR(64) NULL, `error_message` VARCHAR(1000) NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_ai_run_step_input` (`run_id`, `node_name`, `input_hash`),
    KEY `idx_ai_run_step_status` (`run_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent节点执行轨迹与幂等记录';

CREATE TABLE `source_document` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `task_id` BIGINT UNSIGNED NOT NULL,
    `run_id` CHAR(36) NOT NULL, `url` VARCHAR(2048) NOT NULL, `title` VARCHAR(500) NOT NULL DEFAULT '',
    `content_hash` CHAR(64) NOT NULL, `retrieved_time` DATETIME NOT NULL, `metadata_json` TEXT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_task_hash` (`task_id`, `content_hash`), KEY `idx_source_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究来源元数据';

CREATE TABLE `evidence_chunk` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `task_id` BIGINT UNSIGNED NOT NULL,
    `run_id` CHAR(36) NOT NULL, `source_id` BIGINT UNSIGNED NOT NULL,
    `locator` VARCHAR(500) NOT NULL DEFAULT '', `content` MEDIUMTEXT NOT NULL, `score` DECIMAL(6,5) NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
    KEY `idx_evidence_run` (`run_id`, `id`), KEY `idx_evidence_source` (`source_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可追溯证据片段';

CREATE TABLE `ai_report` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `task_id` BIGINT UNSIGNED NOT NULL,
    `run_id` CHAR(36) NOT NULL, `version` INT NOT NULL, `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    `title` VARCHAR(500) NOT NULL, `content` MEDIUMTEXT NOT NULL, `quality_score` DECIMAL(6,5) NULL,
    `publish_article_id` BIGINT UNSIGNED NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_ai_report_version` (`task_id`, `version`),
    KEY `idx_ai_report_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化研究报告';

CREATE TABLE `report_citation` (
    `report_id` BIGINT UNSIGNED NOT NULL, `claim_id` VARCHAR(64) NOT NULL,
    `evidence_id` BIGINT UNSIGNED NOT NULL, `support_score` DECIMAL(6,5) NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`report_id`, `claim_id`, `evidence_id`), KEY `idx_citation_evidence` (`evidence_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告结论与证据绑定';

CREATE TABLE `model_usage` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `run_id` CHAR(36) NOT NULL,
    `node_name` VARCHAR(64) NOT NULL, `provider` VARCHAR(64) NOT NULL, `model` VARCHAR(128) NOT NULL,
    `input_tokens` INT NOT NULL DEFAULT 0, `output_tokens` INT NOT NULL DEFAULT 0,
    `estimated_cost` DECIMAL(12,6) NOT NULL DEFAULT 0, `latency_ms` BIGINT NOT NULL DEFAULT 0,
    `retry_count` INT NOT NULL DEFAULT 0, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), KEY `idx_model_usage_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型调用成本和延迟';

CREATE TABLE `ai_outbox` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `event_id` CHAR(36) NOT NULL,
    `aggregate_id` BIGINT UNSIGNED NOT NULL, `run_id` CHAR(36) NOT NULL,
    `event_type` VARCHAR(64) NOT NULL, `aggregate_version` INT NOT NULL, `payload` MEDIUMTEXT NOT NULL,
    `status` TINYINT NOT NULL DEFAULT 0, `retry_count` INT NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, `locked_by` VARCHAR(64) NULL,
    `locked_until` DATETIME NULL, `last_error` VARCHAR(500) NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, `sent_time` DATETIME NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_ai_outbox_event` (`event_id`),
    KEY `idx_ai_outbox_publish` (`status`, `next_retry_time`, `id`),
    KEY `idx_ai_outbox_lease` (`status`, `locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI任务命令Transactional Outbox';

CREATE TABLE `consumer_inbox` (
    `consumer` VARCHAR(128) NOT NULL, `event_id` CHAR(36) NOT NULL,
    `processed_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`consumer`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨服务消息消费幂等记录';

DROP TABLE IF EXISTS `ai_published_article`;
CREATE TABLE `ai_published_article` (
    `report_id` BIGINT UNSIGNED NOT NULL, `article_id` BIGINT UNSIGNED NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`report_id`), UNIQUE KEY `uk_ai_published_article` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI报告到社区文章的幂等发布投影';

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
