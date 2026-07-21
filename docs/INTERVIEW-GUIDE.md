# 秋招面试讲解与问答

## 30 秒项目介绍

XPlanet Research 是一个面向开发者的可追溯研究 Agent 与知识社区。Agent 在预算内动态选择 Web 搜索、网页抓取和站内知识检索，把工具结果转成可定位 Evidence，再由 Writer 生成显式 Claim/Citation，Critic 检查缺证据和冲突并最多补研究一次。Java 控制面用状态机、Transactional Outbox、RocketMQ、checkpoint 和幂等发布保证长任务可靠；用户审核发布的文章会进入下一轮站内检索。

## 最值得讲的四点

1. **Agent 占主线**：Planner 和下一 Action 都是结构化决策；工具、来源、Token、截止时间和反思次数均有界。
2. **质量可追溯**：Source → Evidence → Claim → Citation 有稳定身份；Evidence 保存定位信息和 SHA-256，错误引用不能落库。
3. **知识闭环**：报告人工发布成文章，文章通过内部 Token + MySQL FULLTEXT 被后续 `internal_search` 召回；5 条标注 Recall@5 为 100%。
4. **长任务可靠**：任务/Outbox 同事务，MQ 至少一次投递，节点 checkpoint 恢复，重复创建、重复消费和重复发布分别幂等。

## 高频问题与回答

### 为什么这是 Agent，不是固定工作流？

图的阶段边界是稳定的，但研究循环中的下一步不是写死：`decide_action` 根据计划、已有 Evidence、去重集合和剩余预算输出 `internal_search`、`web_search`、`web_fetch` 或结束。确定性边界保证可靠，动态 Action 提供 Agent 能力。

### 为什么不做多 Agent？

当前问题的主要风险是证据质量和可靠恢复，而不是角色数量。单 Agent + 工具循环更容易控制预算、checkpoint 和评测。只有当子任务确实可并行且数据证明收益大于协调成本时，才引入受限 worker。

### Citation 为什么不等于 Claim 被支持？

Citation ID 合法只说明它指向某条 Evidence；Evidence 内容仍可能与 Claim 无关或冲突。因此先校验 ID/哈希，再计算词面支持，并由 Critic 输出缺口、冲突和不确定性。当前 100% 是离线结构回归，不宣传为事实正确率。

### MQ 重投为什么不重复执行工具？

每个节点完成后先保存 schema v4 checkpoint；恢复时按 `runId + node + inputHash` 找到已完成状态，从下一节点继续。消息层用 `consumer_inbox(eventId)` 去重，业务写入另有唯一键，形成多层幂等。

### 点赞为什么需要 actionId 和 eventId 两种去重？

`actionId` 解决客户端重试同一操作，防止重复改变点赞关系；`eventId` 解决 MQ 至少一次投递，防止消费者重复累计计数。它们处在不同边界，不能互相替代。

### 为什么点赞先累积 delta 再合并？

点赞关系表是真实状态，计数只是可重建投影。消费者先按 eventId 幂等地累积文章 delta，再批量 `like_count += delta`，减少热点行写频率；合并和事件完成标记同事务，失败可重试。Redis 不作为不可丢的事实缓冲。

### 为什么 Java 与 Python 分开？

Java 擅长已有的用户、文章、事务、MQ 与状态机生态，拥有任务和发布事实；Python/LangGraph 适合模型调用、工具编排和评测。内部 HTTP 契约让两侧可以独立测试和扩容，又避免 Python 直接修改 Java 数据库。

### 为什么用 MySQL FULLTEXT 而不是向量库？

秋招版数据量和标注集不足以证明额外向量基础设施的必要性。FULLTEXT 发布即生效、事务事实不复制、部署简单，并已达到当前 Recall@5 门槛。`InternalSearchProvider` 隔离实现，将来替换为 Embedding + pgvector/Qdrant 时 Action 契约不变。

### 为什么没有 Nacos、Dubbo、Seata？

Gateway + Docker DNS/环境变量已经满足当前实例规模；同步跨服务只有少量 HTTP 契约，OpenFeign 足够；跨服务一致性用本地事务 + Outbox/幂等实现。引入治理组件必须由动态实例、复杂 RPC 或强分布式事务需求驱动，而不是为了技术名词。

### 最大困难是什么？

不是“接上模型”，而是让动态流程仍然可恢复、可引用、可评测。解决方式是把不确定性限制在结构化 Action 内，把工具结果先 checkpoint，把证据建模成稳定实体，再以 Critic 和数据集量化回归。

## 必须主动说明的边界

- 当前真实 OpenAI/Web Search 只完成 MockTransport 契约测试，没有密钥就不声称联网质量；
- 30 题是离线确定性评测，Token/成本为 0，不能外推生产延迟；
- MySQL FULLTEXT 是词法检索，不是向量语义 RAG；
- MySQL、Redis、RocketMQ 是单机演示环境，应用可扩展不等于中间件高可用。

