# XPlanet Research 技术原理与设计问答

> 覆盖范围：Java 后端、AI Agent 应用开发，以及二者之间的工程协作。
> 回答原则：先给结论，再讲本项目怎么做，然后解释取舍，最后说演进方向。
> 本文只描述当前仓库已实现或明确标注为边界的能力。

## 1. 手册使用方法

### 1.1 阅读优先级

| 优先级 | 要求 | 内容 |
|---|---|---|
| P0 | 必须独立讲清楚 | Agent 闭环、Evidence/Citation、checkpoint、Outbox、幂等、点赞投影、二级缓存 |
| P1 | 追问时要会 | Gateway、OpenFeign/MQ、MySQL、Redis、RocketMQ、事务、索引、限流 |
| P2 | 知道边界 | JVM、线程池、向量检索、多 Agent、Nacos、Seata、高可用 |

### 1.2 四段式设计分析模板

1. 结论：一句话说明为什么这样选；
2. 项目实现：说出真实模块、表或流程；
3. 解决的问题：可靠性、性能、一致性或复杂度；
4. 边界与演进：当前没解决什么，什么条件下升级。

示例：

> 为什么用 Outbox？因为创建任务时必须同时保证 MySQL 任务事实和 MQ 命令不丢。项目将 ai_task、ai_run 和 ai_outbox 放在同一本地事务，Relay 再投递 RocketMQ。它解决数据库成功但 MQ 发送失败的问题，但只能做到至少一次，所以消费端还要通过 Inbox、唯一键和 checkpoint 幂等。

### 1.3 3 小时快速导读

1. 30 分钟：30 秒和 2 分钟项目介绍；
2. 50 分钟：Agent、Evidence、Critic、checkpoint；
3. 40 分钟：Outbox、MQ 幂等、点赞 delta 投影；
4. 30 分钟：缓存、Redis、MySQL；
5. 20 分钟：Gateway、OpenFeign、JWT；
6. 10 分钟：真实边界和验证数据。

---

## 2. 系统概览

### 2.1 30 秒版本

XPlanet Research 是一个面向开发者的可追溯研究 Agent 与知识社区。Agent 在预算内动态选择站内检索、Web 搜索和网页抓取，把结果转成可定位 Evidence，再由 Writer 生成 Claim/Citation，Critic 检查缺证据和冲突并最多补研究一次。Java 控制面通过状态机、Transactional Outbox、RocketMQ、schema v4 checkpoint 和幂等发布保证长任务可靠；人工审核发布的文章会进入后续站内检索，形成研究、审核、发布、再研究的知识闭环。

### 2.2 2 分钟版本

项目不是在传统社区旁边增加聊天框，而是把 Agent 作为业务主线。

用户从 Gateway 创建研究任务，Java AI 服务在同一个 MySQL 事务中写入 Task、Run 和 Outbox。Relay 将命令发送到 RocketMQ，消费者调用 Python/LangGraph Agent。Agent 的阶段边界固定，但研究循环中的下一步由结构化 ToolAction 动态决定，可以调用 internal_search、web_search 或 web_fetch。

工具结果不会直接拼成答案，而是先转成带来源、定位和 SHA-256 的 Evidence；Writer 输出显式 Claim/Citation，Critic 检查引用、缺口和冲突，必要时只允许一次定向补研究。每个节点完成后持久化 schema v4 checkpoint，所以进程退出或 MQ 重投后可以从下一节点继续。

报告回到 Java 后经过事务校验和人工审核，通过 OpenFeign 幂等发布为文章，新文章由 MySQL ngram FULLTEXT 立即进入站内检索。

后端还包含两条可靠性链路：点赞关系是事实源，通过 Outbox、RocketMQ、eventId 去重和 delta 批量投影更新文章计数；文章缓存使用 Caffeine + Redis，数据库更新与缓存失效 Outbox 同事务，再广播删除所有实例缓存。

当前验证包括 Java 105 项、Python 29 项、30 条离线评测、5 条站内 Recall@5、checkpoint 强退恢复和完整 Docker smoke。真实 OpenAI 联网质量、生产 QPS 和中间件高可用没有包装成已完成能力。

### 2.3 一句话定位

> 一个以可追溯研究 Agent 为主线、以 Java 可靠控制面和知识社区为闭环的 AI 原生后端项目。

---

## 3. 架构与模块职责

~~~mermaid
flowchart LR
    U["浏览器工作台"] --> G["Spring Cloud Gateway"]
    G --> USER["User Service"]
    G --> ARTICLE["Article Service"]
    G --> INTERACTION["Interaction Service"]
    G --> AI["AI Control Plane"]
    AI -->|"Task + Run + Outbox"| MYSQL["MySQL"]
    AI -->|"Relay"| MQ["RocketMQ"]
    MQ --> CONSUMER["AgentTaskConsumer"]
    CONSUMER -->|"内部 HTTP"| AGENT["Python LangGraph Agent"]
    AGENT -->|"internal_search"| ARTICLE
    AGENT -->|"Web Search / Fetch"| WEB["External Web"]
    AGENT -->|"checkpoint / result"| AI
    AI -->|"人工审核后 OpenFeign"| ARTICLE
    ARTICLE --> REDIS["Redis / Caffeine"]
    INTERACTION -->|"Outbox + MQ"| ARTICLE
~~~

| 模块 | 职责 | 不负责什么 |
|---|---|---|
| Gateway | 路由、JWT 前置校验、CORS、TraceId | 不作为唯一安全边界 |
| User | 登录、用户信息、bcrypt 密码校验 | 不拥有文章和 AI 任务 |
| Article | 文章、评论、缓存、全文检索、AI 文章落地 | 不拥有点赞关系事实 |
| Interaction | 点赞关系、点赞 Outbox | 不同步维护文章计数 |
| AI | Task/Run 状态机、Outbox、进度、报告、审核 | 不负责模型工具编排 |
| Python Agent | Planner、Action、工具、Evidence、Writer、Critic | 不直接修改 Java 数据库 |
| Redis | 缓存、限流、热榜、进度 | 不是核心业务事实源 |
| RocketMQ | 长任务、点赞投影、缓存失效的可靠异步传输 | 不单独保证业务幂等 |
| MySQL | 事实、Outbox、checkpoint、报告和引用 | 不承担模型推理 |

---

## 4. Agent、LangGraph 与 RAG

### Q1：为什么这是 Agent，不是固定工作流？P0

阶段边界固定，但研究循环中的下一步不是写死。decide_action 根据计划、已有 Evidence、已尝试查询和 URL、剩余工具次数及 deadline，选择 internal_search、web_search、web_fetch 或结束。确定性边界保证可靠，动态 Action 提供 Agent 能力。

追问：节点固定为什么还叫 Agent？

> Agent 不要求所有节点动态生成，关键是它能依据环境反馈自主选择下一 Action；固定安全边界是生产约束。

### Q2：LangGraph解决什么？P0

- 将 Agent 表达为显式状态图；
- 支持条件路由和有界循环；
- 节点输入输出可测试；
- State 可序列化为 checkpoint；
- 能从确定节点恢复；
- 比一个超大 Prompt 更容易观测和回归。

### Q3：Planner、Decide Action、Writer、Critic 如何分工？

- Planner：拆解目标，生成初始研究计划；
- Decide Action：根据当前状态选择工具或结束；
- Writer：基于已有 Evidence 输出 Claim/Citation；
- Critic：检查错误引用、证据缺口、冲突和不确定性。

### Q4：为什么 Tool Calling 必须结构化？P0

自然语言工具指令难校验，可能出现不存在的工具或缺参数。结构化 ToolAction 可以做 Pydantic Schema 校验、工具白名单、预算扣减、日志记录和单元测试。

### Q5：三个工具为什么拆开？

- internal_search 查询站内知识；
- web_search 发现网页候选；
- web_fetch 获取可形成 Evidence 的正文；
- 拆分后能分别限制调用次数、URL、权限和超时；
- 搜索摘要不能直接当作可靠正文。

### Q6：什么是 RAG？本项目有什么不同？P0

RAG 是先检索外部知识，再基于知识生成。本项目在检索和生成之间显式建模 Source → Evidence → Claim → Citation，并通过 Critic 和 Java 事务校验保证引用结构，重点是结论可追溯。

### Q7：Source、Evidence、Claim、Citation 分别是什么？P0

- Source：来源文档元数据；
- Evidence：来源中的具体片段，带 locator 和 contentHash；
- Claim：报告中的明确结论；
- Citation：Claim 与 Evidence 的绑定。

### Q8：Citation 为什么不代表 Claim 正确？P0

引用 ID 合法只能证明对象存在，证据仍可能无关、相反或只支持部分结论。因此要校验 ID 和哈希、做词面支持门禁，并由 Critic 检查缺口和冲突。真实语义正确性仍需要模型 Judge 或人工审计。

### Q9：Evidence 为什么保存 SHA-256？

用于稳定标识、去重、检测变化、防止 checkpoint 恢复后的证据身份漂移。哈希只能证明内容一致，不能证明内容真实。

### Q10：为什么 Critic 最多补研究一次？P0

无限反思会导致循环、Token、成本和延迟失控。项目同时限制来源、工具、Token、deadline 和补研究次数，追求有界自治。

### Q11：为什么需要 Human-in-the-loop？P0

报告发布会产生外部副作用。进入 WAITING_REVIEW 后由用户审核，可以控制事实错误和内容责任，并通过唯一投影保证重复发布幂等。

### Q12：为什么不用多 Agent？

当前瓶颈是证据质量、恢复和评测。多 Agent 会增加状态协调、Token、重复搜索、冲突合并和 checkpoint 难度。只有子任务确实可并行且数据证明收益明显时才引入有界 Worker。

### Q13：为什么 Java 和 Python 分开？P0

Java 拥有用户、文章、事务、MQ、状态和发布事实；Python/LangGraph 适合模型 SDK、工具编排和评测。内部 HTTP 让两侧独立测试扩容，也避免 Python 直接修改 Java 数据库。代价是网络调用和跨语言契约维护。

### Q14：为什么 Python 不直接连接业务库？

否则会绕过 Java 状态机、权限、事务校验和审计。当前 Java 拥有事实，Python 只回传 checkpoint、进度和结果。

### Q15：模型或工具失败怎么办？

- 调用有超时；
- Action 和参数做 Schema 校验；
- 重试次数有上限；
- checkpoint 保存已完成状态；
- 错误回传 Java 更新任务状态；
- 外部失败不能绕过 Evidence/Citation 校验。

---

## 5. checkpoint、恢复与状态机

### Q1：checkpoint 是什么？P0

节点完成后的持久化 State 快照，包含下一节点、Evidence、已尝试查询、工具次数、预算和中间结果。进程退出后可从下一节点继续。

### Q2：为什么工具完成后要及时 checkpoint？P0

模型和 Web 工具昂贵。若执行后未保存便崩溃，恢复时会重复调用。节点边界持久化可缩小重复窗口。

### Q3：MQ 去重后为什么还要 checkpoint？P0

Inbox 只能说明消息是否处理过，不能表达任务执行到哪一步。消费者可能已经接收消息，但 Agent 在中途退出。

### Q4：如何避免恢复后重复工具调用？

- schema v4 checkpoint 保存完整状态；
- 保存 attempted queries 和 URLs；
- 保存下一恢复节点；
- runId + node + inputHash 标识节点；
- MQ 层还有 consumer_inbox(eventId)。

### Q5：为什么只保留 schema v4？

v1～v3 是开发阶段格式，最终切换时没有需要继续运行的旧任务。长期兼容会增加分支和测试矩阵。真实生产升级必须先排空、迁移或设置双读窗口。

### Q6：状态机有什么价值？

限制非法跳转，例如任务不能从 CREATED 直接到 PUBLISHED。配合 version、事务和唯一约束，防止并发取消、完成、审核互相覆盖。

---

## 6. Outbox、事务与最终一致性

### Q1：项目中有哪些双写问题？P0

- 创建 AI Task + 发送命令；
- 点赞关系变化 + 发送计数事件；
- 文章更新 + 发送缓存失效；
- 报告审核 + 发布文章。

### Q2：Transactional Outbox 是什么？P0

业务数据和 Outbox Event 在同一个 MySQL 本地事务提交。Relay 之后扫描、领取并发送 MQ。只要业务成功，事件就不会因为进程在发送前退出而丢失。

### Q3：Outbox 能做到 exactly-once 吗？P0

不能。MQ 发送成功后、更新 Outbox 状态前崩溃会再次发送。因此采用至少一次投递 + 消费幂等，实现业务结果只生效一次。

### Q4：Relay 为什么需要租约？

多实例通过 locked_by、locked_until 和状态领取事件，防止同时发送；实例崩溃后租约到期，其他实例可接管。

### Q5：为什么退避重试？

中间件故障时立即高频重试会打满数据库、CPU 和日志。退避能降低故障期压力。

### Q6：为什么不用 Seata？P0

项目不要求多个服务同步强提交，长任务和点赞允许最终一致。本地事务 + Outbox + MQ + 幂等更解耦，没有全局锁和协调器。强一致扣款等场景才需要重新评估 TCC/Saga/Seata。

### Q7：最终一致性是什么？

请求成功时事实正确，派生状态稍后完成。例如 like_relation 已变化，article.like_count 经 MQ 和投影稍后更新。

### Q8：长期不一致如何治理？

监控 Outbox、重试、MQ 堆积和 Delta 状态；定期从事实表重算投影；死信人工补偿；以事实表作为对账基准。

---

## 7. 幂等设计

### Q1：幂等是什么？P0

同一请求或事件执行多次，最终结果与执行一次一致。幂等不是接口只被调用一次，而是重复调用不重复产生副作用。

### Q2：项目有哪些幂等键？P0

| 键 | 边界 | 作用 |
|---|---|---|
| Idempotency-Key | 客户端 → AI Task | 防止重复创建任务 |
| actionId | 客户端 → Like | 防止同一点赞动作重试 |
| eventId | Producer → Consumer | 防止 MQ 重复累计 |
| consumer + eventId | Inbox | 消息消费去重 |
| runId + node + inputHash | Agent | 节点恢复幂等 |
| reportId | 审核发布 | 防止重复创建文章 |

### Q3：actionId 和 eventId 为什么不能互换？P0

actionId 处理 HTTP 请求重试，eventId 处理 MQ 投递重试，处在不同边界。

### Q4：SETNX 为什么不能做最终去重？P0

- Key 可能过期或淘汰；
- SETNX 成功后 MySQL 事务可能失败；
- Redis 与 MySQL 没有共同事务；
- Redis 故障时状态可能丢失。

Redis可做快速拦截，数据库事实和唯一索引才是最终防线。

### Q5：为什么不能先查再插？

并发请求可能同时查到不存在，然后同时插入。必须靠唯一索引仲裁，应用捕获冲突后返回已有结果。

### Q6：幂等记录何时删除？

取决于消息最大重投窗口和业务审计需求。消息仍可能重投时不能提前删除；业务唯一关系通常长期保留。

---

## 8. 点赞状态机与批量投影

### Q1：为什么 Interaction 拥有点赞？P0

点赞是用户和文章之间的关系，like_relation 是事实。Article 只拥有文章和计数投影，避免两个服务共同写事实。

### Q2：为什么先验证文章存在？

避免给已删除或不存在文章写关系和 Outbox。通过 OpenFeign 同步检查，因为请求需要立即知道能否点赞。

### Q3：完整流程？P0

~~~text
验证文章
→ 更新 like_relation
→ 同事务写 like_outbox
→ Relay 发送 RocketMQ
→ Consumer 按 eventId 去重
→ 写 like_count_delta
→ 按 articleId 聚合
→ article.like_count += SUM(delta)
~~~

### Q4：为什么关系是事实源？P0

点赞的真实含义是某用户当前是否点赞。计数可以重建；关系和计数冲突时以关系为准。

### Q5：为什么不在请求中直接加计数？P0

热点文章会让同一行承受大量更新和行锁竞争；同步跨服务更新还会放大延迟和故障。异步投影将用户写路径和热点计数解耦。

### Q6：为什么先记录 delta 再合并？P0

同一文章短时间的 +1、+1、-1、+1 可以聚合成一次 +2，减少热点行写入。Delta 和事件状态在事务中处理，失败可重试。

### Q7：为什么不用 Redis INCR 做最终计数？

Redis 快，但点赞关系才是不可丢的事实。只用 Redis 会增加持久化、重放、对账和跨库一致性问题。

### Q8：为什么不依赖顺序消息？

顺序仍不能解决重复投递、消费者崩溃、请求重复和数据库回滚。项目依靠事实状态、eventId 和唯一约束。

---

## 9. 缓存与 Redis

### Q1：为什么 Caffeine + Redis？P0

Caffeine L1 无网络开销；Redis L2 多实例共享；MySQL是最终事实。读取顺序为 L1 → L2 → MySQL。

### Q2：Cache Aside 是什么？P0

读时缓存 miss 查库并回填；写时先更新数据库，再使缓存失效。避免数据库和缓存双写顺序问题。

### Q3：穿透、击穿、雪崩区别？P0

| 问题 | 含义 | 解决 |
|---|---|---|
| 穿透 | 不存在数据持续访问 DB | 参数校验、空值缓存、布隆过滤器 |
| 击穿 | 单热点 Key 失效并发回源 | 分布式锁、单请求重建 |
| 雪崩 | 大量 Key 同时过期 | TTL 抖动、预热、限流降级 |

### Q4：为什么写库后删缓存，不更新缓存？

并发写可能让较早事务最后覆盖缓存；删除后由下一次读取从数据库重建更安全。

### Q5：如何保证多实例缓存一致？P0

文章事务写缓存失效 Outbox；Relay 投递 MQ；每个实例删除自己的 Caffeine L1，同时删除共享 Redis L2。

### Q6：为什么普通延迟双删不够？

第二删依赖进程和定时任务不失败，可能丢失且难审计。Outbox将失效变成可重试的持久事件。

### Q7：为什么 ZSet 适合热榜？

member 存文章 ID，score 存热度，支持排序和 TopN。当前热度主要由点赞决定；view_count 没有独立采集链路，不能声称已有完整浏览热度模型。

### Q8：限流是什么方案？

当前使用 Redis 固定窗口提供基础保护。窗口边界可能突刺；严格场景可用滑动窗口、令牌桶、漏桶或 Sentinel。

### Q9：缓存为什么不能做事实源？

缓存会过期、淘汰、丢失和重建。Task、点赞关系、Outbox、报告必须在具有事务和约束的 MySQL。

---

## 10. RocketMQ

### Q1：为什么用 RocketMQ？P0

用于 AI 长任务、点赞投影、缓存失效，提供削峰、解耦和失败重试。

### Q2：主要角色？

Producer、NameServer、Broker、Consumer、Topic 和 Consumer Group。

### Q3：至少一次、至多一次、恰好一次？P0

- 至多一次：可能丢，不重复；
- 至少一次：尽量不丢，可能重复；
- 恰好一次：业务结果只生效一次。

工程上常用至少一次 + 消费幂等得到业务上的 exactly-once effect。

### Q4：集群与广播消费？

集群模式下一条消息由组内一个实例处理；广播模式每个实例都处理。AI Task/点赞投影适合集群，所有实例 L1 缓存失效需要广播语义。

### Q5：消费失败怎么办？

由 MQ 重投，业务通过 Inbox、唯一键和事务保证安全；超过阈值应进入死信和人工补偿。

### Q6：积压怎么办？

先查生产速度、消费耗时和下游瓶颈，再扩消费者、增批量、优化数据库、聚合热点或降级。不能只加线程把压力转移给 MySQL。

### Q7：为什么不能只依赖事务消息？

生产可靠也不能解决消费重复、部分执行和数据库失败，业务幂等仍必需。Outbox还能统一覆盖多条业务链路。

---

## 11. MySQL、事务与索引

### Q1：为什么事实放 MySQL？P0

MySQL提供 ACID、唯一约束、行锁和审计，适合 Task、Outbox、点赞关系、Evidence、Citation 和发布投影。

### Q2：ACID？

Atomicity、Consistency、Isolation、Durability，即原子性、一致性、隔离性、持久性。

### Q3：隔离级别？

Read Uncommitted、Read Committed、Repeatable Read、Serializable。还要能解释脏读、不可重复读和幻读。

### Q4：Transactional 为什么会失效？P1

同类内部调用、非 public、非 Spring Bean、异常被吞、受检异常未配置、新线程没有事务上下文等。

### Q5：唯一索引为何是幂等最终防线？P0

应用先查再插有并发窗口，唯一索引在提交时仲裁，只允许一个成功。

### Q6：最左匹配？

联合索引 (a,b,c) 通常支持以 a 开头的组合。索引设计要结合 WHERE、ORDER BY、选择性和执行计划。

### Q7：索引为什么不是越多越好？

索引占空间，增加写入维护、页分裂和优化器成本，应由慢查询和 EXPLAIN 驱动。

### Q8：EXPLAIN 看什么？

type、key、rows、filtered，以及 Extra 中的 filesort、temporary 等。

### Q9：为什么用 FULLTEXT？P0

LIKE 前置通配符难用普通 B+Tree；ngram FULLTEXT使用倒排索引支持中文词法检索，发布即生效且不复制第二份事实。

### Q10：为什么不用向量库？P0

当前 5 条标注 Recall@5 已达 100%，没有数据证明额外基础设施收益。未来同义表达召回低于门槛时，通过 Provider 替换为 Embedding + pgvector/Qdrant。

### Q11：Flyway解决什么？

数据库按 V004～V009 版本化。空库执行完整 V004，已有 V4 库建立 baseline 后统一执行增量，避免手工改库。

### Q12：乐观锁和悲观锁？

悲观锁适合高冲突短操作；乐观锁通过 version 条件更新，适合可重试场景。AI Task 的版本和状态约束可避免并发状态覆盖。

---

## 12. Spring、Gateway 与 OpenFeign

### Q1：IOC/DI？

对象和依赖由 Spring 容器管理，降低耦合并便于替换和测试。

### Q2：AOP？项目哪里用？

AOP处理事务、限流等横切逻辑。项目的注解限流通过 Aspect 统一执行。

### Q3：Filter、Interceptor、AOP 区别？

Filter在请求入口；Interceptor围绕 Controller；AOP围绕 Bean 方法。Gateway使用响应式 Filter。

### Q4：Gateway 做什么？P0

统一入口、路由、JWT 前置校验、CORS、TraceId 和隐藏内部地址。

### Q5：有 Gateway 为什么服务仍鉴权？P0

防止内部端口暴露、绕过网关和伪造转发头。服务仍校验 JWT、资源归属或内部 Token，属于纵深防御。

### Q6：OpenFeign 和 MQ 怎么选？P0

需要立即结果用 OpenFeign，如文章存在性和审核发布；允许延迟、需要削峰重试用 MQ，如 AI 任务、点赞投影和缓存失效。

### Q7：Feign 失败怎么处理？

设置连接/读取超时，写请求必须幂等；降级不能伪造成功，关键写操作应明确失败或补偿。

### Q8：为什么不用 Dubbo？

同步调用少，HTTP/OpenFeign直观且跨语言。大规模内部 RPC 和治理需求明确时再引入。

### Q9：为什么不用 Nacos？

固定实例由 Docker DNS 和环境变量寻址。动态实例、集中配置和治理出现后再引入。

---

## 13. JWT、密码与 Agent 安全

### Q1：JWT 结构？

Header、Payload、Signature。签名防篡改，不代表 Payload 加密。

### Q2：JWT 优缺点？

优点是无 Session、跨服务验证方便；缺点是撤销、轮换和泄漏控制复杂。

### Q3：为什么 bcrypt？

随机盐、成本可调、相同密码哈希不同，增加暴力破解成本。数据库不保存明文。

### Q4：内部 Token 做什么？

用于 Java AI、Python Agent 和 Article 内部检索间的服务身份。生产还应配合网络隔离、Secret 管理、轮换和 mTLS。

### Q5：为什么 Web Fetch 要防 SSRF？P0

用户可能诱导 Agent 访问 localhost、私网、云元数据或管理端口。需要限制协议、检查解析 IP、拒绝私网/回环、控制重定向、响应大小和超时。

### Q6：Prompt Injection 怎么防？

网页是数据，不是系统指令。工具权限、预算和输出 Schema由宿主程序控制；高风险副作用需要人工审批。

### Q7：模型输出为什么再次校验？

LLM 输出不可信，可能缺字段、越权或引用不存在 Evidence。Python Pydantic 和 Java 事务校验形成双层边界。

---

## 14. Java 并发与 JVM 追问

> 以下是岗位延伸题，不要声称项目做了不存在的 JVM 调优。

### Q1：synchronized 和 ReentrantLock？

synchronized自动释放、语法简单；ReentrantLock支持可中断、超时、公平锁和多个 Condition，但要在 finally 释放。

### Q2：volatile 保证什么？

可见性和部分有序性，不保证 count++ 复合操作原子。

### Q3：CAS 是什么？

比较当前值和期望值，相同才更新。问题包括 ABA、自旋消耗和高竞争退化。

### Q4：线程池核心参数？

corePoolSize、maximumPoolSize、keepAliveTime、workQueue、threadFactory、rejectedExecutionHandler。

### Q5：为什么不建议直接 Executors？

部分工厂使用无界队列或大量线程，存在 OOM 风险。应明确队列、线程数和拒绝策略。

### Q6：多实例定时任务如何防重复？

数据库/Redis分布式锁、任务分片或带租约领取。项目 Outbox Relay 使用租约思路。

### Q7：Java 内存区域？

堆、虚拟机栈、程序计数器、本地方法栈、元空间。线程多消耗栈和调度资源，缓存无界增加堆压力。

### Q8：OOM 如何排查？

保留 Heap Dump，使用 MAT/JFR 分析大对象、泄漏链、线程和分配热点。Caffeine、批量队列和 Agent State 都要有界。

---

## 15. Docker、测试与工程化

### Q1：Image 和 Container？

Image是只读模板，Container是运行实例。Dockerfile定义构建，Compose编排服务。

### Q2：容器间为什么不用 localhost？

localhost指容器自身。服务之间用 Compose Service Name，如 mysql:3306、agent:8000。

### Q3：健康检查与 depends_on？

depends_on主要控制启动顺序，不一定代表业务可用；healthcheck验证实际健康，启动脚本还要等待 healthy。

### Q4：单元、集成、E2E？

单元测试类/函数；集成测试真实中间件协作；E2E从 Gateway或浏览器验证闭环。

### Q5：Mock 有什么局限？

只能验证契约和分支，不能证明真实网络、数据库锁、MQ重投和模型质量，所以项目还有故障注入、Recall和Docker smoke。

### Q6：当前验证数据？P0

| 验证 | 结果 |
|---|---:|
| Java 单元测试 | 105 项 |
| Python 单元测试 | 29 项 |
| 离线评测 | 30/30 |
| Citation Index Validity | 100% |
| Deterministic Lexical Claim Support | 100% |
| Internal Recall@5 | 5/5 |
| checkpoint 强退恢复 | 2 attempts，工具未重复 |
| Docker smoke | 研究、审核、发布、召回、点赞、缓存通过 |

### Q7：离线 100% 为什么不是模型准确率？P0

离线 Provider使用固定语料，不调用外部模型，只验证工作流、预算、Evidence身份和Citation结构。

### Q8：CI 应做什么？

Java/Python测试、离线评测、Compose验证、脚本解析、依赖扫描、镜像构建和临时数据库迁移验证。

---

## 16. 组件取舍必答

### Q1：为什么没有 Nacos、Dubbo、Seata？

固定规模由 Gateway、Docker DNS和环境变量满足；少量同步调用用OpenFeign；跨服务一致性用本地事务 + Outbox + 幂等。组件由真实需求驱动。

### Q2：为什么没有向量库？

词法召回已满足当前门槛，数据不足以证明向量基础设施收益；Provider已经隔离未来替换点。

### Q3：为什么没有多 Agent？

当前优先证据、恢复和评测；多 Agent会扩大状态、成本和协调复杂度。

### Q4：为什么没有 Kubernetes？

当前是单机可复现环境，没有真实多节点调度和弹性需求，Compose 更容易复现。

### Q5：有 Gateway 就是完整微服务吗？

不是。还需要认证、数据所有权、事务边界、可靠异步、观测、测试和部署。当前是可运行工程基线，不是生产平台。

### Q6：最大困难是什么？P0

不是接入模型，而是让动态流程仍然可恢复、可引用、可评测。解决方式是结构化 Action、有界预算、工具结果 checkpoint、稳定 Evidence身份、Critic和Java事务校验。

---

## 17. 场景题标准答案

### 场景 1：Task 入库后 MQ 挂了

Task和Outbox同事务存在，Relay退避重试，Broker恢复后补发。

### 场景 2：MQ发送成功，更新Outbox前崩溃

事件会再次发送；消费者通过Inbox/eventId去重。

### 场景 3：Agent搜索完成后退出

节点checkpoint已保存，重投后从下一节点继续。

### 场景 4：同一Idempotency-Key并发创建

最终由 user_id + idempotency_key 唯一约束仲裁，返回已有任务。

### 场景 5：同一报告重复发布

ai_published_article(report_id)唯一投影返回同一Article。

### 场景 6：不存在文章点赞

Interaction先同步验证，不存在则不写关系和Outbox。

### 场景 7：点赞成功但计数未立即变化

关系事实已提交，计数是最终一致的异步投影。

### 场景 8：某实例仍读旧缓存

文章变更Outbox经MQ广播，删除各实例L1和共享L2。

### 场景 9：Redis数据丢失

缓存、热榜和短期进度可重建；MySQL事实不丢。相关功能短暂降级。

### 场景 10：模型重复选择同一查询

State记录查询和URL去重，工具数和deadline有硬上限。

### 场景 11：网页要求泄露系统Prompt

网页只作为不可信数据，不能改变系统指令或工具权限。

### 场景 12：流量增长十倍

先看Gateway P95、MySQL锁、缓存命中率、MQ积压、Agent外部延迟，再按瓶颈扩容或优化，不能先堆组件。

---

## 18. 容易答错的地方

| 错误说法 | 正确口径 |
|---|---|
| 项目是微服务聊天机器人 | 是研究Agent与知识社区闭环 |
| Citation存在就说明结论正确 | 只说明引用合法，还要验证支持与冲突 |
| Outbox保证只发送一次 | 保证不易丢但可能重复，消费端幂等 |
| SETNX已经完全去重 | Redis辅助，数据库唯一键和事实兜底 |
| 点赞数是事实 | 点赞关系是事实，计数是投影 |
| MQ有顺序就不需要幂等 | 顺序不能解决重复和崩溃 |
| Gateway之后服务不用鉴权 | 服务仍需纵深校验 |
| 离线30/30代表真实准确率100% | 只证明离线结构回归 |
| FULLTEXT是向量RAG | FULLTEXT是词法检索 |
| 项目达到几万QPS | 当前没有完整容量基线 |
| 中间件已高可用 | 当前是本地单节点环境 |
| 热榜已有真实浏览热度 | view_count尚无独立采集 |

---

## 19. 系统能力摘要

### 19.1 系统名称

XPlanet Research｜可追溯技术研究 Agent 与知识社区

### 19.2 核心能力

- 基于 Java/Spring Boot 与 Python/LangGraph 构建可追溯研究 Agent，支持站内检索、Web搜索、网页抓取的动态工具循环，并以有界预算控制来源、工具、Token和deadline；
- 设计 Source–Evidence–Claim–Citation 数据模型及 Critic 门禁，Evidence保存定位和SHA-256，错误引用由Python和Java事务双层校验；
- 以 Task/Run/Outbox、RocketMQ、Inbox和schema v4 checkpoint实现长任务至少一次投递、节点恢复和多层幂等；
- 建立人工审核、幂等发布和MySQL ngram FULLTEXT知识回流，发布文章可被后续Agent召回；
- 以点赞关系为事实源，通过Outbox、eventId去重和delta批量投影削减热点文章行写入；
- 使用Caffeine + Redis二级缓存，以事务Outbox和MQ广播实现多实例可靠失效；
- 建立Java/Python单测、30条离线评测、Recall@5、进程强退恢复及Docker全链路smoke。

### 19.3 不成立的能力声明

- 生产级高可用微服务平台；
- 支持十万QPS；
- 模型准确率100%；
- 已实现向量RAG；
- 已实现多Agent协同。

---

## 20. 连续设计问题

### Agent追问链

为什么是Agent → Action如何决定 → 如何限制循环 → 工具失败怎么办 → Evidence如何建模 → Citation如何校验 → Critic是否无限反思 → 如何恢复 → 为什么不用多Agent → 如何真实评测。

### Outbox追问链

双写问题 → Outbox原理 → 发送后崩溃 → 消费幂等 → 幂等记录失败 → 积压处理 → 多实例领取 → 为什么不用Seata。

### 点赞追问链

服务归属 → 点赞事实 → 为什么不直接加计数 → actionId/eventId → SETNX够不够 → delta合并 → 失败恢复 → 对账重建。

### 缓存追问链

二级缓存 → 穿透/击穿/雪崩 → 为什么删缓存 → 删除失败 → 多实例L1 → Redis故障 → 命中率和热点监控。

---

## 21. 学习自检清单

- [ ] 一句话项目定位；
- [ ] 一条完整主链路；
- [ ] Agent与固定工作流；
- [ ] Source/Evidence/Claim/Citation；
- [ ] checkpoint与MQ幂等；
- [ ] Outbox为什么可能重复；
- [ ] actionId/eventId/Idempotency-Key；
- [ ] 点赞关系和计数投影；
- [ ] delta批量合并；
- [ ] Cache Aside和可靠失效；
- [ ] OpenFeign与MQ选型；
- [ ] 为什么没有Nacos/Dubbo/Seata；
- [ ] 为什么不用向量库和多Agent；
- [ ] 当前验证数据；
- [ ] 真实边界：联网质量、QPS、中间件HA、view_count。

继续参考：

- [架构设计](ARCHITECTURE.md)
- [零基础入门](BEGINNER-GUIDE.md)
- [实验与边界](EXPERIMENTS.md)
- [当前系统范围](CURRENT-SCOPE.md)
- [功能巡检](VERIFICATION-GUIDE.md)
