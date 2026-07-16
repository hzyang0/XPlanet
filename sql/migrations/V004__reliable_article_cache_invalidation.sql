-- 将文章缓存失效从不可恢复的 afterCommit 发送迁移为 Transactional Outbox。
USE xplanet;

CREATE TABLE IF NOT EXISTS `article_change_outbox` (
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
