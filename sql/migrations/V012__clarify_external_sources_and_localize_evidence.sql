-- Offline execution uses fixed summaries of external pages. "Offline" describes
-- acquisition mode, not source ownership. Repair old labels and localize the
-- deterministic Chinese demo evidence already stored in reports/articles.
USE xplanet;

UPDATE source_document
SET title = CASE url
        WHEN 'https://github.com/hzyang0/XPlanet' THEN 'XPlanet 项目仓库'
        WHEN 'https://microservices.io/patterns/data/transactional-outbox.html' THEN 'Transactional Outbox 模式'
        WHEN 'https://docs.langchain.com/oss/python/langgraph/quickstart' THEN 'LangGraph 快速入门'
        WHEN 'https://redis.io/docs/latest/develop/data-types/streams/' THEN 'Redis Streams 官方文档'
        WHEN 'https://owasp.org/www-community/attacks/Server_Side_Request_Forgery' THEN 'OWASP 服务端请求伪造说明'
        ELSE title
    END,
    metadata_json = CASE
        WHEN JSON_UNQUOTE(JSON_EXTRACT(COALESCE(metadata_json, JSON_OBJECT()), '$.evidenceType')) = 'offline-corpus'
            THEN JSON_SET(COALESCE(metadata_json, JSON_OBJECT()), '$.evidenceType', 'external-summary')
        ELSE metadata_json
    END
WHERE url IN (
    'https://github.com/hzyang0/XPlanet',
    'https://microservices.io/patterns/data/transactional-outbox.html',
    'https://docs.langchain.com/oss/python/langgraph/quickstart',
    'https://redis.io/docs/latest/develop/data-types/streams/',
    'https://owasp.org/www-community/attacks/Server_Side_Request_Forgery'
);

UPDATE evidence_chunk SET locator = '外部来源摘要' WHERE locator = '离线内置语料';

UPDATE evidence_chunk e
JOIN source_document s ON s.id = e.source_id
SET e.content = 'XPlanet 通过数据库状态机、Transactional Outbox、RocketMQ 和持久化投影保证社区写入可恢复；热点读取由 Caffeine 与 Redis 两级缓存加速。',
    e.content_hash = LOWER(SHA2('XPlanet 通过数据库状态机、Transactional Outbox、RocketMQ 和持久化投影保证社区写入可恢复；热点读取由 Caffeine 与 Redis 两级缓存加速。', 256)),
    s.content_hash = LOWER(SHA2('XPlanet 通过数据库状态机、Transactional Outbox、RocketMQ 和持久化投影保证社区写入可恢复；热点读取由 Caffeine 与 Redis 两级缓存加速。', 256))
WHERE e.content = 'XPlanet uses database state machines, Transactional Outbox, RocketMQ and persistent projections to keep community writes recoverable while Caffeine and Redis serve hotspot reads.';

UPDATE evidence_chunk e
JOIN source_document s ON s.id = e.source_id
SET e.content = 'Transactional Outbox 在同一数据库事务中保存业务变更与待发送事件，再由独立转发器发布消息。由于消息通常至少投递一次，消费者必须实现幂等。',
    e.content_hash = LOWER(SHA2('Transactional Outbox 在同一数据库事务中保存业务变更与待发送事件，再由独立转发器发布消息。由于消息通常至少投递一次，消费者必须实现幂等。', 256)),
    s.content_hash = LOWER(SHA2('Transactional Outbox 在同一数据库事务中保存业务变更与待发送事件，再由独立转发器发布消息。由于消息通常至少投递一次，消费者必须实现幂等。', 256))
WHERE e.content = 'Transactional Outbox stores the business change and an event in one database transaction, then a separate relay publishes it. Consumers must be idempotent because delivery is at least once.';

UPDATE evidence_chunk e
JOIN source_document s ON s.id = e.source_id
SET e.content = 'LangGraph StateGraph 使用显式节点和条件边描述工作流，使 Agent 决策过程可观察，并为检查点恢复提供清晰边界。',
    e.content_hash = LOWER(SHA2('LangGraph StateGraph 使用显式节点和条件边描述工作流，使 Agent 决策过程可观察，并为检查点恢复提供清晰边界。', 256)),
    s.content_hash = LOWER(SHA2('LangGraph StateGraph 使用显式节点和条件边描述工作流，使 Agent 决策过程可观察，并为检查点恢复提供清晰边界。', 256))
WHERE e.content = 'LangGraph StateGraph models workflows as explicit nodes and conditional edges. This makes agent decisions observable and gives recovery work a concrete checkpoint boundary.';

UPDATE evidence_chunk e
JOIN source_document s ON s.id = e.source_id
SET e.content = 'Redis Streams 提供带 ID 的追加式事件结构和有界读取，适合传递临时进度；持久化任务状态仍应保存在数据库中。',
    e.content_hash = LOWER(SHA2('Redis Streams 提供带 ID 的追加式事件结构和有界读取，适合传递临时进度；持久化任务状态仍应保存在数据库中。', 256)),
    s.content_hash = LOWER(SHA2('Redis Streams 提供带 ID 的追加式事件结构和有界读取，适合传递临时进度；持久化任务状态仍应保存在数据库中。', 256))
WHERE e.content = 'Redis Streams provide an append-only event structure with IDs and bounded reads. They fit transient progress delivery while durable task status remains in a database.';

UPDATE evidence_chunk e
JOIN source_document s ON s.id = e.source_id
SET e.content = '服务端 URL 抓取器必须限制协议、校验解析后的地址、约束重定向次数、超时和响应大小，并拒绝访问内部网络地址。',
    e.content_hash = LOWER(SHA2('服务端 URL 抓取器必须限制协议、校验解析后的地址、约束重定向次数、超时和响应大小，并拒绝访问内部网络地址。', 256)),
    s.content_hash = LOWER(SHA2('服务端 URL 抓取器必须限制协议、校验解析后的地址、约束重定向次数、超时和响应大小，并拒绝访问内部网络地址。', 256))
WHERE e.content = 'Server-side URL fetchers must constrain protocols, validate resolved addresses, limit redirects, timeouts and response sizes, and reject internal network destinations.';

UPDATE ai_report SET content = REPLACE(content,
    'XPlanet uses database state machines, Transactional Outbox, RocketMQ and persistent projections to keep community writes recoverable while Caffeine and Redis serve hotspot reads.',
    'XPlanet 通过数据库状态机、Transactional Outbox、RocketMQ 和持久化投影保证社区写入可恢复；热点读取由 Caffeine 与 Redis 两级缓存加速。');
UPDATE ai_report SET content = REPLACE(content,
    'Transactional Outbox stores the business change and an event in one database transaction, then a separate relay publishes it. Consumers must be idempotent because delivery is at least once.',
    'Transactional Outbox 在同一数据库事务中保存业务变更与待发送事件，再由独立转发器发布消息。由于消息通常至少投递一次，消费者必须实现幂等。');
UPDATE ai_report SET content = REPLACE(content,
    'LangGraph StateGraph models workflows as explicit nodes and conditional edges. This makes agent decisions observable and gives recovery work a concrete checkpoint boundary.',
    'LangGraph StateGraph 使用显式节点和条件边描述工作流，使 Agent 决策过程可观察，并为检查点恢复提供清晰边界。');
UPDATE ai_report SET content = REPLACE(content,
    'Redis Streams provide an append-only event structure with IDs and bounded reads. They fit transient progress delivery while durable task status remains in a database.',
    'Redis Streams 提供带 ID 的追加式事件结构和有界读取，适合传递临时进度；持久化任务状态仍应保存在数据库中。');
UPDATE ai_report SET content = REPLACE(content,
    'Server-side URL fetchers must constrain protocols, validate resolved addresses, limit redirects, timeouts and response sizes, and reject internal network destinations.',
    '服务端 URL 抓取器必须限制协议、校验解析后的地址、约束重定向次数、超时和响应大小，并拒绝访问内部网络地址。');
UPDATE ai_report SET content = REPLACE(content, '[离线语料：', '[');

UPDATE article SET content = REPLACE(content,
    'XPlanet uses database state machines, Transactional Outbox, RocketMQ and persistent projections to keep community writes recoverable while Caffeine and Redis serve hotspot reads.',
    'XPlanet 通过数据库状态机、Transactional Outbox、RocketMQ 和持久化投影保证社区写入可恢复；热点读取由 Caffeine 与 Redis 两级缓存加速。');
UPDATE article SET content = REPLACE(content,
    'Transactional Outbox stores the business change and an event in one database transaction, then a separate relay publishes it. Consumers must be idempotent because delivery is at least once.',
    'Transactional Outbox 在同一数据库事务中保存业务变更与待发送事件，再由独立转发器发布消息。由于消息通常至少投递一次，消费者必须实现幂等。');
UPDATE article SET content = REPLACE(content,
    'LangGraph StateGraph models workflows as explicit nodes and conditional edges. This makes agent decisions observable and gives recovery work a concrete checkpoint boundary.',
    'LangGraph StateGraph 使用显式节点和条件边描述工作流，使 Agent 决策过程可观察，并为检查点恢复提供清晰边界。');
UPDATE article SET content = REPLACE(content,
    'Redis Streams provide an append-only event structure with IDs and bounded reads. They fit transient progress delivery while durable task status remains in a database.',
    'Redis Streams 提供带 ID 的追加式事件结构和有界读取，适合传递临时进度；持久化任务状态仍应保存在数据库中。');
UPDATE article SET content = REPLACE(content,
    'Server-side URL fetchers must constrain protocols, validate resolved addresses, limit redirects, timeouts and response sizes, and reject internal network destinations.',
    '服务端 URL 抓取器必须限制协议、校验解析后的地址、约束重定向次数、超时和响应大小，并拒绝访问内部网络地址。');
UPDATE article SET content = REPLACE(content, '[离线语料：', '[');
