-- 将旧点赞链路迁移为 interaction 状态事实 + Transactional Outbox + article 持久化投影。
-- 执行前请备份，并先停止 interaction/article 服务，避免迁移期间继续产生旧消息。
USE xplanet;

CREATE TABLE IF NOT EXISTS `like_relation` (
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `article_id`  BIGINT UNSIGNED NOT NULL,
    `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1=有效,0=已取消',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `article_id`),
    KEY `idx_article_status` (`article_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞关系事实表,interaction服务所有';

-- 当前仓库旧版本一定存在 article_like；复制状态后暂时保留旧表，便于核对与回滚。
INSERT INTO `like_relation` (`user_id`, `article_id`, `status`, `create_time`, `update_time`)
SELECT `user_id`, `article_id`, `status`, `create_time`, `update_time`
FROM `article_like`
ON DUPLICATE KEY UPDATE
    `status` = VALUES(`status`),
    `update_time` = VALUES(`update_time`);

CREATE TABLE IF NOT EXISTS `like_outbox` (
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

CREATE TABLE IF NOT EXISTS `like_count_delta` (
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

-- 迁移后核对：旧 article_like 与 like_relation 的行数和 status 分布应一致。
-- 新服务上线后，Redis 中 xp:user:liked:*、xp:article:like:cnt:*、xp:like:buffer* 均为旧链路数据，
-- 确认没有旧版本实例运行后再按运维流程清理，禁止在本脚本中自动删除。
