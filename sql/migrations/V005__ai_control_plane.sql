-- AI research control plane: tasks, runs, traceable evidence/report data and reliable command Outbox.
USE xplanet;

CREATE TABLE IF NOT EXISTS `ai_task` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `idempotency_key`  VARCHAR(128)    NOT NULL,
    `question`         VARCHAR(2000)   NOT NULL,
    `status`           VARCHAR(32)     NOT NULL,
    `current_run_id`   CHAR(36)        NOT NULL,
    `version`          INT             NOT NULL DEFAULT 0,
    `max_sources`      INT             NOT NULL DEFAULT 5,
    `max_tool_calls`   INT             NOT NULL DEFAULT 10,
    `max_tokens`       INT             NOT NULL DEFAULT 8000,
    `deadline_seconds` INT             NOT NULL DEFAULT 300,
    `last_error`       VARCHAR(1000)   NULL,
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_task_idempotency` (`user_id`, `idempotency_key`),
    KEY `idx_ai_task_user_status` (`user_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI研究任务事实表';

CREATE TABLE IF NOT EXISTS `ai_run` (
    `run_id`       CHAR(36)        NOT NULL,
    `task_id`      BIGINT UNSIGNED NOT NULL,
    `status`       VARCHAR(32)     NOT NULL,
    `current_node` VARCHAR(64)     NOT NULL,
    `attempt`      INT             NOT NULL DEFAULT 1,
    `last_error`   VARCHAR(1000)   NULL,
    `started_time` DATETIME        NULL,
    `finished_time` DATETIME       NULL,
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`run_id`),
    KEY `idx_ai_run_task` (`task_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI任务执行实例';

CREATE TABLE IF NOT EXISTS `ai_run_step` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `run_id`       CHAR(36)        NOT NULL,
    `node_name`    VARCHAR(64)     NOT NULL,
    `input_hash`   CHAR(64)        NOT NULL,
    `status`       VARCHAR(32)     NOT NULL,
    `duration_ms`  BIGINT          NULL,
    `error_code`   VARCHAR(64)     NULL,
    `error_message` VARCHAR(1000)  NULL,
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_run_step_input` (`run_id`, `node_name`, `input_hash`),
    KEY `idx_ai_run_step_status` (`run_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent节点执行轨迹与幂等记录';

CREATE TABLE IF NOT EXISTS `source_document` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_id`       BIGINT UNSIGNED NOT NULL,
    `run_id`        CHAR(36)        NOT NULL,
    `url`           VARCHAR(2048)   NOT NULL,
    `title`         VARCHAR(500)    NOT NULL DEFAULT '',
    `content_hash`  CHAR(64)        NOT NULL,
    `retrieved_time` DATETIME       NOT NULL,
    `metadata_json` TEXT            NULL,
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_task_hash` (`task_id`, `content_hash`),
    KEY `idx_source_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究来源元数据';

CREATE TABLE IF NOT EXISTS `evidence_chunk` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_id`     BIGINT UNSIGNED NOT NULL,
    `run_id`      CHAR(36)        NOT NULL,
    `source_id`   BIGINT UNSIGNED NOT NULL,
    `locator`     VARCHAR(500)    NOT NULL DEFAULT '',
    `content`     MEDIUMTEXT      NOT NULL,
    `score`       DECIMAL(6,5)    NULL,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_evidence_run` (`run_id`, `id`),
    KEY `idx_evidence_source` (`source_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可追溯证据片段';

CREATE TABLE IF NOT EXISTS `ai_report` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_id`            BIGINT UNSIGNED NOT NULL,
    `run_id`             CHAR(36)        NOT NULL,
    `version`            INT             NOT NULL,
    `status`             VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
    `title`              VARCHAR(500)    NOT NULL,
    `content`            MEDIUMTEXT      NOT NULL,
    `quality_score`      DECIMAL(6,5)    NULL,
    `publish_article_id` BIGINT UNSIGNED NULL,
    `create_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_report_version` (`task_id`, `version`),
    KEY `idx_ai_report_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化研究报告';

CREATE TABLE IF NOT EXISTS `report_citation` (
    `report_id`     BIGINT UNSIGNED NOT NULL,
    `claim_id`      VARCHAR(64)     NOT NULL,
    `evidence_id`   BIGINT UNSIGNED NOT NULL,
    `support_score` DECIMAL(6,5)    NULL,
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`report_id`, `claim_id`, `evidence_id`),
    KEY `idx_citation_evidence` (`evidence_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告结论与证据绑定';

CREATE TABLE IF NOT EXISTS `model_usage` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `run_id`        CHAR(36)        NOT NULL,
    `node_name`     VARCHAR(64)     NOT NULL,
    `provider`      VARCHAR(64)     NOT NULL,
    `model`         VARCHAR(128)    NOT NULL,
    `input_tokens`  INT             NOT NULL DEFAULT 0,
    `output_tokens` INT             NOT NULL DEFAULT 0,
    `estimated_cost` DECIMAL(12,6)  NOT NULL DEFAULT 0,
    `latency_ms`    BIGINT          NOT NULL DEFAULT 0,
    `retry_count`   INT             NOT NULL DEFAULT 0,
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_model_usage_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型调用成本和延迟';

CREATE TABLE IF NOT EXISTS `ai_outbox` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_id`          CHAR(36)        NOT NULL,
    `aggregate_id`      BIGINT UNSIGNED NOT NULL,
    `run_id`            CHAR(36)        NOT NULL,
    `event_type`        VARCHAR(64)     NOT NULL,
    `aggregate_version` INT             NOT NULL,
    `payload`           MEDIUMTEXT      NOT NULL,
    `status`            TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待发送,1=发送中,2=已发送',
    `retry_count`       INT             NOT NULL DEFAULT 0,
    `next_retry_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `locked_by`         VARCHAR(64)     NULL,
    `locked_until`      DATETIME        NULL,
    `last_error`        VARCHAR(500)    NULL,
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `sent_time`         DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_outbox_event` (`event_id`),
    KEY `idx_ai_outbox_publish` (`status`, `next_retry_time`, `id`),
    KEY `idx_ai_outbox_lease` (`status`, `locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI任务命令Transactional Outbox';

CREATE TABLE IF NOT EXISTS `consumer_inbox` (
    `consumer`       VARCHAR(128) NOT NULL,
    `event_id`       CHAR(36)     NOT NULL,
    `processed_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`consumer`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨服务消息消费幂等记录';
