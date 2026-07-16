-- Article-owned idempotency projection for publishing an approved AI report exactly once at the business level.
USE xplanet;

CREATE TABLE IF NOT EXISTS `ai_published_article` (
    `report_id`   BIGINT UNSIGNED NOT NULL,
    `article_id`  BIGINT UNSIGNED NOT NULL,
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`report_id`),
    UNIQUE KEY `uk_ai_published_article` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI报告到社区文章的幂等发布投影';
