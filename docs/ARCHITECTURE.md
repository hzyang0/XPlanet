# 架构与关键设计

## 1. 模块划分

```
   ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
   │  Article   │ │Interaction │ │    User    │ │ AI Control │
   │   (8081)   │ │   (8082)   │ │   (8083)   │ │   (8084)   │
   │ 文章+二级缓存│ │  点赞削峰   │ │  认证用户   │ │任务+可靠命令│
   └─────┬──────┘ └─────┬──────┘ └──────┬─────┘ └──────┬─────┘
         │              │               │              │
   ┌─────┴──────────────┴───────────────┴──────────────┘
   │
   ▼
┌───────┐   ┌────────────┐   ┌────────────────────┐
│ MySQL │   │   Redis    │   │     RocketMQ       │
│(主数据)│   │ L2 缓存 +  │   │  like(削峰)+      │
│       │   │ 锁 + 限流  │   │  change(L1广播)   │
└───────┘   └─────┬──────┘   └────────────────────┘
                  ▲
                  │  各实例进程内 Caffeine L1
            ┌─────┴──────────────────┐
            │   Application 实例      │
            └────────────────────────┘
```

## 2. 二级缓存(读路径)

### 2.1 命中路径
```
请求 → L1(Caffeine) → L2(Redis) → DB
       ~1μs            ~1ms        ~30ms
```

### 2.2 击穿保护(L1+L2 都 miss)

```
1. Redisson tryLock(article:rebuild:{id}, wait=200ms)，不传显式 lease
2. 抢到锁:
   2a. double-check L2(可能在等锁时已被别人回填)
   2b. 回源 DB → 序列化 JSON → 写 L2(TTL = 30min ± 5min 抖动) → 写 L1
3. 没抢到:
   3a. sleep 50ms 后再读 L2
   3b. 仍 miss → 直接回源 DB(降级,接受少量穿透)
```

**为什么不传显式 lease?** 三参数 `tryLock(wait, lease, unit)` 到期会直接释放，不启用看门狗。
当前使用两参数等待重载，让 Redisson watchdog 在持锁线程存活时续期，避免慢查询超过固定租约后第二个线程同时回源。

### 2.3 穿透保护

DB 查到 null 时,在 L2 写 `__EMPTY__` 哨兵值,TTL 60s。下次同样的 articleId 命中哨兵后,
解码逻辑返回 null,不再回源。

短 TTL 保证文章被创建后 60s 内可见。

### 2.4 雪崩保护

L2 TTL = 30min 基线 + (0~5min) 随机抖动。同一批写入的 key 不会同时失效。

## 3. 缓存一致性(写路径)

### 3.1 Cache Aside + 可靠失效 Outbox

```
事务边界:
  BEGIN
    UPDATE article SET ... WHERE id = ?
    invalidate(L1 + L2)          ← 第一删
    INSERT article_change_outbox ← 立即失效事件
    INSERT article_change_outbox ← 1s 后可发送的延迟失效事件
  COMMIT
  relay → publish ArticleChangeMessage
  consumers → invalidate(L1 + L2)
```

**为什么需要第二删?**
经典竞态:
```
T1(读): 读 cache miss → 查 DB(拿到旧值 V0) → 准备写回 cache
T2(写): 更新 DB(V1) → 删 cache
T1(继续): 把 V0 写入 cache  ← 脏数据
```
延迟 1s 再删一次,把 T1 写入的 V0 杀掉。延迟不再依赖 JVM 中 `sleep` 的临时任务，
而是由 `next_retry_time` 持久化调度；MQ 暂停或实例崩溃后，relay 可以继续发送。

延迟时长 = max(回源耗时 + 写缓存耗时) × 安全系数。本项目设 1s 是经验值。

### 3.2 MQ 广播保证多实例 L1 一致

L1 是本进程的 Caffeine,各实例独立。写发生在实例 A 时,只有 A 清了自己的 L1,
B/C/D 的 L1 还是旧值。

通过 `RocketMQMessageListener(messageModel = BROADCASTING)` 让所有在线实例都收到消息,
各自清自己的 L1，并幂等删除共享 L2。Outbox 采用至少一次投递，重复消息通过缓存删除的天然幂等吸收。

## 4. 点赞状态机、Outbox 与批量投影

### 4.1 写入流程

```
用户点赞 click
  ↓ interaction 本地事务
LikeService:
  1. 条件迁移 like_relation 状态
  2. 状态真实变化时写 like_outbox(eventId, delta)
  3. 事务提交后接口返回
  ↓
LikeOutboxPublisher:
  1. 用 owner + locked_until 抢占待发送事件
  2. 同步投递 MQ;失败释放并指数退避
  3. 发送成功后标记 SENT
  ↓
LikeMessageConsumer:
  1. INSERT IGNORE like_count_delta(eventId唯一)
  2. 重复消息直接成为幂等 no-op
  ↓ 每 500ms
LikeCountProjectionService:
  1. SELECT ... FOR UPDATE SKIP LOCKED 获取批次
  2. 按 articleId 求和
  3. 更新 article.like_count 并在同事务标记事件完成
```

### 4.2 合并效果

100 个用户对文章 1 点赞:
- 不合并: 100 条 SQL `UPDATE article SET like_count = like_count + 1 WHERE id = 1`
- 合并: 1 条 SQL `UPDATE article SET like_count = like_count + 100 WHERE id = 1`

DB UPDATE QPS 大幅下降,行锁竞争减少。

### 4.3 为什么不依赖顺序消息

`like_relation` 在数据库条件更新中决定状态是否真正变化，只有变化才产生 `+1/-1`。
计数事件只做整数求和，加法满足交换律，因此 MQ 重复或乱序不影响最终计数。
顺序投递可以作为吞吐/局部性优化，但不再承担正确性职责。

### 4.4 幂等

每条消息携带 UUID eventId，`like_count_delta.event_id` 有数据库唯一约束。
Outbox relay 在“发送成功但标记前崩溃”时允许重复投递，消费端持久化唯一约束吸收重复；
Redis SETNX 不再作为最终保证。

### 4.5 故障恢复

- 状态更新失败：本地事务整体回滚，不产生 Outbox；
- MQ 不可用：Outbox 保留事件并指数退避；
- relay 发送后崩溃：租约到期后重发，消费端去重；
- 消费实例崩溃：未确认消息由 MQ 重投；
- 投影实例崩溃：计数更新和事件标记处于同一事务，整体提交或整体回滚；
- 多实例投影：`SKIP LOCKED` 避免处理同一批数据库行。

## 5. 已知取舍(面试可主动说出来加分)

- 点赞事实与计数投影当前共享一个 MySQL 实例，但已按表明确 interaction/article 所有权；后续拆库时协议保持不变
- Outbox/投影目前只有日志和表状态，尚未接入积压量、失败率和最老事件时长监控
- 单机 Redis,没起集群/哨兵
- article 通过 OpenFeign 调 user 服务；熔断和 TraceId 透传仍待完善
- 没接配置中心 / 注册中心,服务地址写在配置里
- AI 当前只完成 Java 控制面、表结构和任务命令 Outbox；Python Agent、结果回传、SSE 和人工审核尚未实现

**关于刻意不做的部分**:网关、注册中心、分布式事务、监控全家桶在这个业务规模下属于过度设计,没有引入。
缓存一致性也没上 Canal binlog 兜底——社区场景下「双删 + MQ 广播」已足够,binlog 兜底是为不存在的问题加复杂度。
工程的判断力体现在「该用什么」,也体现在「不该用什么」。

## 6. 部署与迁移

- 本地混合模式通过 `scripts/setup-infra.ps1` 启动中间件，RocketMQ broker 广播宿主机地址，Java 服务在 IDE 或本机 JVM 中运行；
- 全 Docker 模式通过 `scripts/start-docker.ps1` 切换 broker 容器地址、执行 Flyway、构建三个应用镜像并等待健康；
- `sql/init.sql` 负责新数据卷的当前完整结构，Flyway 对历史数据库建立 V4 baseline，并已通过 V005 增加 AI 控制面；以后继续追加版本脚本；
- 两种模式共用固定 `xplanet-net` 网络，但分别使用 `broker-host.conf` 和 `broker-docker.conf`，避免 broker 把客户端无法访问的地址注册到 NameServer；
- CI 执行全 Reactor 单元测试、两份 Compose 配置解析和全部 PowerShell 脚本语法检查；真实 MySQL/Redis/RocketMQ 行为由 `scripts/smoke-test.ps1` 验证。
