# 架构与关键设计

## 1. 模块划分

```
┌──────────────────────────────────────────────────────────┐
│                     Gateway (8080)                       │
│       redis-rate-limiter (IP)  +  TraceId 注入           │
└────────────┬──────────────┬──────────────┬───────────────┘
             │              │              │
       ┌─────▼──────┐ ┌─────▼─────┐ ┌──────▼─────┐
       │  Article   │ │Interaction│ │    User    │
       │   (8081)   │ │  (8082)   │ │   (8083)   │
       └─────┬──────┘ └─────┬─────┘ └──────┬─────┘
             │              │              │
       ┌─────┴──────────────┴──────────────┘
       │
       ▼                   ┌──────────────────┐
   ┌───────┐ ───binlog──▶  │  Canal Server    │
   │ MySQL │               │  + Canal Client  │
   └───────┘               └────────┬─────────┘
       ▲                            │
       │                            ▼
   ┌───┴────────┐         ┌────────────────────┐
   │   Redis    │ ◀────── │     RocketMQ       │
   │  (L2 cache)│         │  like / change     │
   └────────────┘         └────────────────────┘
        ▲
        │  Caffeine L1(本地,各实例自治)
        │
   ┌────┴────────────────────────────┐
   │      Application Instances       │
   └─────────────────────────────────┘
```

## 2. 二级缓存(读路径)

### 2.1 命中路径
```
请求 → L1(Caffeine) → L2(Redis) → DB
       ~1μs            ~1ms        ~30ms
```

### 2.2 击穿保护(L1+L2 都 miss)

```
1. Redisson tryLock(article:rebuild:{id}, wait=200ms, lease=3s)
2. 抢到锁:
   2a. double-check L2(可能在等锁时已被别人回填)
   2b. 回源 DB → 序列化 JSON → 写 L2(TTL = 30min ± 5min 抖动) → 写 L1
3. 没抢到:
   3a. sleep 50ms 后再读 L2
   3b. 仍 miss → 直接回源 DB(降级,接受少量穿透)
```

**为什么 lease=3s?** 留够回源 + 写缓存的余量;Redisson 看门狗会自动续期,真要执行超过 3s 也不会被强占。

### 2.3 穿透保护

DB 查到 null 时,在 L2 写 `__EMPTY__` 哨兵值,TTL 60s。下次同样的 articleId 命中哨兵后,
解码逻辑返回 null,不再回源。

短 TTL 保证文章被创建后 60s 内可见。

### 2.4 雪崩保护

L2 TTL = 30min 基线 + (0~5min) 随机抖动。同一批写入的 key 不会同时失效。

## 3. 缓存一致性(写路径)

### 3.1 Cache Aside + 延迟双删

```
事务边界:
  BEGIN
    UPDATE article SET ... WHERE id = ?
    invalidate(L1 + L2)          ← 第一删
  COMMIT
  publish ArticleChangeMessage    ← 广播给其他实例清 L1
  async sleep 1s → invalidate L2  ← 第二删,杀死竞态期间的旧值回填
```

**为什么需要第二删?**
经典竞态:
```
T1(读): 读 cache miss → 查 DB(拿到旧值 V0) → 准备写回 cache
T2(写): 更新 DB(V1) → 删 cache
T1(继续): 把 V0 写入 cache  ← 脏数据
```
延迟 1s 再删一次,把 T1 写入的 V0 杀掉。

延迟时长 = max(回源耗时 + 写缓存耗时) × 安全系数。本项目设 1s 是经验值。

### 3.2 MQ 广播保证多实例 L1 一致

L1 是本进程的 Caffeine,各实例独立。写发生在实例 A 时,只有 A 清了自己的 L1,
B/C/D 的 L1 还是旧值。

通过 `RocketMQMessageListener(messageModel = BROADCASTING)` 让所有实例都收到消息,
各自清自己的 L1。**广播模式不会重复消费同一条消息(每个实例一份)。**

### 3.3 Canal 兜底

应用层双删依赖业务代码每次都正确清缓存。Canal 是最后一道防线:

```
任何对 article 表的 binlog → Canal Server → Canal Client → MQ → 所有实例清 L1 + L2
```

业务代码漏写、DBA 直改、脚本批量改 — 都会被 binlog 捕获。

**Canal 不能替代双删**,因为 binlog 同步有秒级延迟,主路径还是要双删;Canal 是兜底。

## 4. 点赞削峰(写优化)

### 4.1 写入流程

```
用户点赞 click
  ↓ ~5ms
LikeService:
  1. Redis SETNX 幂等键(60s TTL)
  2. Redis SADD 用户已赞集合
  3. Redis INCR 文章实时计数(给前端展示)
  4. 异步发 MQ(顺序消息,按 userId hash 选 queue)
  ↓ 接口返回
LikeMessageConsumer(常驻):
  1. MQ 幂等检查(actionId)
  2. 累加到内存 buffer: Map<articleId, AtomicLong delta>
  3. 每 500ms 或 200 条 flush 一次 → 合并 update DB
```

### 4.2 合并效果

100 个用户对文章 1 点赞:
- 不合并: 100 条 SQL `UPDATE article SET like_count = like_count + 1 WHERE id = 1`
- 合并: 1 条 SQL `UPDATE article SET like_count = like_count + 100 WHERE id = 1`

DB UPDATE QPS 大幅下降,行锁竞争减少。

### 4.3 顺序保证

`rocketMQTemplate.asyncSendOrderly(..., hashKey = userId, ...)`

RocketMQ 按 hashKey 选择 MessageQueue,同一 hashKey 进同一队列 → 单消费者顺序消费。
保证同一用户 "点赞 → 取消 → 再点赞" 不会乱序。

### 4.4 幂等

每条消息携带 UUID actionId,消费端 `SETNX xp:mq:like:idem:{actionId}` TTL 10min。
重投 / 重启重消费都会被吞掉。

## 5. 限流降级

### 5.1 网关层(粗粒度)

`spring-cloud-gateway` + Redis Lua 令牌桶,按 IP 限流:
- 文章接口: 100 QPS / IP,burst 200
- 点赞接口: 200 QPS / IP,burst 400

挡住明显的爬虫和异常流量。

### 5.2 应用层(细粒度,热点参数)

Sentinel `@SentinelResource("article:detail")` 配合 `ParamFlowRule`:
- 每个 articleId 单机限制 50 QPS
- 已知热点 articleId=100 单独配 200 QPS

效果: 单篇文章被恶意刷 100 QPS,只挡这一篇,不影响其他文章。

### 5.3 降级

```java
@SentinelResource(
    blockHandler = "detailBlockHandler", // 限流/熔断
    fallback     = "detailFallback",     // 业务异常
    exceptionsToIgnore = {BizException.class}  // 业务异常正常抛出
)
```

被 block 时返回错误码 5001 而不是 500,让客户端能识别并友好提示。

## 6. 可观测性

- Spring Boot Actuator + Micrometer + Prometheus
- 自定义 Gauge: Caffeine 命中率、L1 size
- Grafana 仪表盘(目录 `docker/grafana/dashboard.json`,本仓库未提供,自己导入)

## 7. 已知简化(面试可主动说出来加分)

- 没接 Nacos 配置中心(Sentinel 规则代码化)
- 没做认证鉴权(只用 X-User-Id header demo)
- ZK 持久化 Canal 位点未配置
- LikeMessageConsumer 内存 buffer,实例 crash 会丢失尚未 flush 的 delta(可改成定时持久化或落 Redis Stream)
- 没接 SkyWalking/Jaeger 分布式追踪,只在 Gateway 注入 TraceId
- 单机 Redis,没起集群/哨兵(已在 application.yml 留扩展位)

这些限制都是有意识的取舍,而不是"不知道",面试时可以主动展开聊改造方案。
