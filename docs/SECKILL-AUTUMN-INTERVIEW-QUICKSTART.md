# 秒杀商城：新手快速入门与秋招备战手册

> 目标不是背完所有代码，而是能在秋招面试中讲清楚：系统解决什么问题、两条下单链路为什么不同、关键一致性风险如何被控制、哪些能力已经实现、哪些仍是后续优化。

---

## 1. 先用一分钟讲清项目

这是一个**轻量商城 + 高并发限量秒杀系统**。

- 商城部分提供注册登录、商品浏览与搜索、购物车、普通结算和订单查询；
- 普通结算是低到中并发的同步事务：在 MySQL 事务中完成条件扣库存、订单快照、订单项写入和清空购物车；
- 秒杀部分面对短时间大量请求，使用 Redis Lua 做原子准入和一人一单，使用 Transactional Outbox + RocketMQ 将订单创建异步化，最终由 MySQL 条件扣减和唯一约束兜底，保证不超卖、可恢复、可幂等。

可以这样表述：

> 我没有把秒杀当作普通下单接口的简单放大。普通结算强调事务一致性和用户体验；秒杀强调瞬时流量下的快速拒绝、削峰和最终一致性，所以单独设计了 Redis 准入、Outbox 和异步订单链路。

不要说的内容：

- 不要说“百万并发”“高可用集群”或具体 QPS/P99，除非有对应机器、配置和压测报告；
- 不要说接入真实支付；目前 `PAID` 是本地演示用模拟支付状态；
- 不要说 Redis 是最终库存账本；最终库存约束在 MySQL；
- 不要说消息一定只投递一次；系统按“至少一次投递 + 消费幂等”设计。

---

## 2. 项目地图：先看哪些文件

### 2.1 阅读顺序

按下面顺序读，能在半天形成完整认识：

| 顺序 | 文件/目录 | 需要回答的问题 |
| --- | --- | --- |
| 1 | `README.md` | 系统边界、端口、运行方式和已完成功能是什么？ |
| 2 | `docs/SECKILL-DESIGN.md` | 秒杀的四个不变量是什么？ |
| 3 | `xplanet-seckill/controller/MallController.java` | 普通商城暴露哪些接口？ |
| 4 | `xplanet-seckill/service/MallService.java` | 普通结算如何保证库存和订单一致？ |
| 5 | `xplanet-seckill/controller/SeckillController.java` | 秒杀接口为什么只返回 requestId/状态？ |
| 6 | `xplanet-seckill/service/SeckillService.java` | 秒杀提交的总编排是什么？ |
| 7 | `xplanet-seckill/service/RedisReservationService.java` | Redis Lua 如何原子准入？ |
| 8 | `xplanet-seckill/service/SeckillSubmissionTxService.java` | 为什么 request 和 Outbox 必须同事务写入？ |
| 9 | `xplanet-seckill/service/OutboxRelay.java` | MQ 投递失败如何恢复？ |
| 10 | `xplanet-seckill/service/OrderConsumer.java` | 消费重复、库存不足时怎么处理？ |
| 11 | `sql/init.sql`、`sql/commerce-migration.sql` | 哪些表和唯一约束负责最后兜底？ |
| 12 | `benchmark/README.md`、`benchmark/seckill.lua` | 多用户压测如何做、结果如何验收？ |

### 2.2 模块职责

```text
浏览器 / 前端
       │
       ├── 用户服务 :8083
       │   └── 注册、BCrypt 密码校验、Token 签发
       │
       └── 商城秒杀服务 :8080
           ├── 商品、购物车、普通订单
           └── 秒杀活动、Redis 准入、请求状态、Outbox、消费者

MySQL：业务事实源、最终库存、订单和 Outbox
Redis：秒杀库存预热、一人一单标记、登录限流
RocketMQ：承接已准入请求，异步创建最终订单
```

关键表：

| 表 | 作用 | 面试要点 |
| --- | --- | --- |
| `product` | 普通商品与库存 | 普通库存用条件更新扣减 |
| `cart_item` | 用户购物车 | `(user_id, product_id)` 唯一，避免重复购物车行 |
| `normal_order` / `normal_order_item` | 普通订单与订单快照 | 历史价格和名称不能依赖后续商品变化 |
| `seckill_activity` | 秒杀活动、总库存、最终可用库存 | 数据库库存是最终账本 |
| `seckill_request` | 用户请求状态 | 前端通过 requestId 轮询最终结果 |
| `seckill_order_outbox` | 待投递订单事件 | 解决数据库和 MQ 的双写问题 |
| `seckill_order` | 最终秒杀订单 | `event_id`、活动-用户唯一约束保证幂等 |

---

## 3. 双链路：普通结算和秒杀如何区分

### 3.1 普通结算链路

入口：`POST /api/mall/orders/checkout`

```text
购物车
  → 查询购物车项和商品
  → MySQL 条件扣库存：stock >= quantity
  → 创建 normal_order
  → 创建 normal_order_item（商品名、价格快照）
  → 删除购物车项
  → 同步返回 OrderVO
```

对应实现：`MallController -> MallService.checkout`。

普通结算使用 `@Transactional` 的原因是：扣库存、订单、订单项、清空购物车是一个业务整体。任意一步失败必须全部回滚，不能出现“库存扣了但订单没创建”或“订单创建了但购物车没清”的中间状态。

库存扣减必须是条件更新，等价逻辑为：

```sql
UPDATE product
SET stock = stock - #{quantity}, sales = sales + #{quantity}
WHERE id = #{productId} AND stock >= #{quantity};
```

受影响行数为 `1` 才算成功。不要先 `SELECT stock` 后再由 Java 判断并更新，因为两个并发事务可能同时读到同一库存，形成竞态。

### 3.2 秒杀下单链路

入口：`POST /api/seckill/activities/{activityId}/orders`

```text
用户请求
  → 校验活动时间与状态
  → Redis Lua：库存检查 + 一人一单 + 预扣
  → 本地事务：写 seckill_request + seckill_order_outbox
  → 立即返回 requestId / QUEUED
  → Outbox Relay 定时投递 RocketMQ
  → Consumer 消费消息
  → MySQL 条件扣减活动库存 + 创建 seckill_order + 更新 request
  → 前端轮询请求状态，获得 SUCCEEDED / FAILED
```

对应实现：

- `SeckillController.submit`
- `SeckillService.submit`
- `RedisReservationService.reserve`
- `SeckillSubmissionTxService.save`
- `OutboxRelay`
- `OrderConsumer`

### 3.3 为什么不能共用一个接口

| 对比维度 | 普通结算 | 秒杀 |
| --- | --- | --- |
| 流量形态 | 分散、可预估 | 瞬时集中，远大于库存 |
| 同步性 | 同步创建订单并返回 | 只返回排队资格，异步建单 |
| 前置拦截 | 直接进入数据库事务 | Redis Lua 在内存中筛掉售罄和重复请求 |
| 消息队列 | 不必强制引入 | 用于削峰、解耦和故障恢复 |
| 用户体验 | 立即看到订单 | 看到排队中，再查询最终结果 |

面试回答：

> 普通结算的核心矛盾是事务一致性，直接使用数据库条件扣减即可；秒杀的核心矛盾是大量请求同时竞争一个库存行。若所有流量直接打 MySQL，会形成锁竞争和连接池拥塞。因此秒杀必须把“是否有资格抢”前移到 Redis，通过 Lua 原子挡流；已准入的少量请求再经 MQ 平滑地进入数据库。

### 3.4 双链路的注意点和改进方向

当前普通结算仍需要主动说明的边界：

1. 没有独立的 `Idempotency-Key`，用户连续点击可能产生多笔普通订单；生产环境应增加一次性提交令牌或幂等键表。
2. 只是模拟支付，真实支付应是“待支付订单 + 支付渠道回调 + 幂等确认”，不能在创建订单时直接当作真实已支付。
3. 订单取消、超时关单和库存归还尚未实现。

这不是缺点的回避，面试中应主动表达：当前项目将复杂度聚焦在秒杀高并发不变量上，普通订单保留了可扩展的正确事务基础。

---

## 4. 原子准入：Redis Lua 为什么是秒杀第一道门

### 4.1 准入阶段做什么

秒杀准入不是创建订单，而是快速决定该用户是否获得排队资格。Lua 脚本在 Redis 单线程执行，一次完成：

1. 活动是否已预热；
2. 剩余库存是否大于 0；
3. 当前用户是否已经抢过；
4. 写入用户标记；
5. 将 Redis 可用库存减一。

这样把原本“读库存、查用户、扣库存、记用户”多个 Redis 命令合并为一次不可穿插的执行，避免并发请求之间出现竞争窗口。

### 4.2 返回结果必须区分

`RedisReservationService` 的返回值应该被理解为：

| 结果 | 含义 | 服务行为 |
| --- | --- | --- |
| 准入成功 | Redis 已预扣并标记用户 | 继续持久化 request + Outbox |
| 库存售罄 | 没有资格 | 快速返回 `REJECTED`，不访问数据库 |
| 重复请求 | 用户已经抢过 | 返回已有 request 或重复提示 |
| 未预热 | Redis 中没有活动数据 | 返回可重试提示，不能盲目落库 |

特别注意：**Redis 准入成功不等于订单成功。** 它只说明该请求有资格排队；订单成功必须等待消费者完成 MySQL 事务后，状态变成 `SUCCEEDED`。

### 4.3 高频追问与回答

**Q：为什么 Lua 能保证原子性？**

Redis 以单线程方式串行执行命令，Lua 脚本执行期间不会被其他客户端命令插入；将检查和修改封装为同一脚本后，其他请求不可能在“检查库存”和“扣库存”之间改变数据。

**Q：Lua 脚本慢会怎样？**

Redis 是单线程，长脚本会阻塞后续请求。因此脚本必须只包含 O(1) 的 Redis 操作，不能在 Lua 中做循环扫描、网络请求、数据库访问或复杂计算。

**Q：Redis 重启后库存怎么办？**

Redis 不是最终库存账本。活动开始前通过 `warm` 从 `seckill_activity.available_stock` 预热；Redis 故障恢复后要重新预热，同时要避免在 Redis 重建时覆盖数据库已消耗库存。更严格的生产方案需要活动版本号、预热锁和对账任务。

**Q：Redis 预扣成功后，数据库扣减失败怎么办？**

消费者将请求更新为失败，并回补 Redis 的库存和用户标记。回补需要设计为幂等操作；否则重复失败处理可能多加库存。最终仍依靠 MySQL 的 `available_stock >= 1` 条件更新防超卖。

**Q：为什么不用 Redisson 分布式锁？**

锁会把热点库存竞争串行化，并带来加锁、释放、续期和异常释放的管理成本。秒杀准入本质是几个 Redis 状态的原子读写，Lua 更直接、吞吐更高。分布式锁更适合复杂临界区，而非这种固定的键操作组合。

---

## 5. Transactional Outbox：数据库与 MQ 双写如何可靠

### 5.1 双写问题

错误方案一：先写数据库，再发 MQ。数据库成功后应用崩溃，消息可能永远没发。

错误方案二：先发 MQ，再写数据库。消费者可能先收到消息，但数据库事务尚未提交或最终回滚。

这两步跨越 MySQL 和 RocketMQ，默认没有全局事务，不能简单依赖调用顺序。

### 5.2 当前实现的流程

```text
同一个 MySQL 本地事务
  INSERT seckill_request(status=QUEUED)
  INSERT seckill_order_outbox(status=NEW, event_id=...)
事务提交

独立 Relay 定时扫描 NEW / RETRY Outbox
  → 投递 RocketMQ
  → 成功：标记 SENT
  → 失败：记录 retry_count、next_retry_time，等待重试

Consumer 消费后创建最终订单
```

`seckill_request` 和 Outbox 同一事务提交，是这个模式的关键：只要请求对外可见，就一定存在一条可恢复投递的事件。

### 5.3 Outbox 常见问题

**问题 1：Relay 发送成功但更新 `SENT` 失败，重试会不会重复投递？**

会，所以系统必须接受至少一次投递。解决方式不是强求 MQ 恰好一次，而是让消费者以 `event_id` 唯一键幂等处理。

**问题 2：多个 Relay 实例会不会重复扫描同一条记录？**

单实例演示中不明显；多实例需要租约字段、状态机抢占或 `SELECT ... FOR UPDATE SKIP LOCKED` 等机制，让同一条记录在同一时间只被一个实例领取。

**问题 3：一直发送失败怎么办？**

需要设置最大重试次数、退避重试、死信/失败状态和监控告警；不要无限高频重试压垮下游。目前项目具备重试基础，生产还应补全告警和人工处理闭环。

**问题 4：能否直接用 RocketMQ 事务消息替代 Outbox？**

可以，但会把数据库事务与 MQ 回查逻辑耦合到消息中间件。Outbox 更通用、数据可审计、可补偿，适合业务服务以数据库为事实源的场景；两种方案按团队基础设施和运维能力选择。

### 5.4 面试回答模板

> 秒杀请求入库和发送 MQ 是跨资源操作，我没有只依赖发送顺序，而是在本地事务内同时写入请求记录和 Outbox 事件。Relay 异步扫描并投递，失败延迟重试。即使发送成功后更新 Outbox 状态失败造成重复投递，消费者仍由 eventId 唯一约束和业务唯一约束保证不重复创建订单。

---

## 6. 幂等：为什么需要多层保护

### 6.1 幂等不是一个点，而是一组防线

| 层级 | 风险 | 当前保护 |
| --- | --- | --- |
| 用户请求 | 用户连点、网络重试 | Redis 用户标记，一人一单 |
| 请求持久化 | Redis 重建或并发落库 | `(activity_id, user_id)` 唯一约束/查询已有请求 |
| Outbox 投递 | Relay 重试、状态更新失败 | eventId 固定，允许重复投递 |
| MQ 消费 | MQ 至少一次投递 | `event_id` 唯一约束，重复事件不重复建单 |
| 最终订单 | 同一用户重复抢购 | `(activity_id, user_id)` 唯一约束 |
| 库存 | 消费者并发扣减 | MySQL 条件更新 `available_stock >= 1` |

### 6.2 为什么 Redis 标记还不够

Redis 可能过期、重启、被重建，且 Redis 预扣与数据库写入不是同一事务。因此数据库唯一约束必须是最终兜底。正确思路是：Redis 负责高性能过滤，数据库负责最终正确性。

### 6.3 消费端幂等要注意什么

1. 先识别 `event_id` 是否已处理；
2. 数据库唯一约束是最后防线，捕获 `DuplicateKeyException` 后应查询既有结果并返回幂等成功，而不是把它当系统异常；
3. 消息体必须携带稳定事件标识，不能每次重试都新生成 UUID；
4. 事务提交后再确认状态，避免“订单已创建但请求仍显示处理中”。

### 6.4 高频追问

**Q：幂等键放 Redis 还是 MySQL？**

Redis 适合高频、短期去重，性能好但不适合作为最终依据；MySQL 唯一索引性能较低但具备持久性和事务性。秒杀应两者结合。

**Q：重复消息为什么不能只在内存 Set 里去重？**

服务重启、扩容、多实例时内存状态会丢失或不共享；去重状态必须持久化到数据库，或者使用可靠共享存储。

**Q：普通结算是否已经完全幂等？**

没有。它保证事务性和库存不超卖，但当前没有独立的结算幂等键。应诚实说明，并提出下一步使用 `Idempotency-Key + 请求记录唯一约束` 或一次性提交令牌实现。

---

## 7. 压测：如何做、怎么解释结果

### 7.1 为什么只压秒杀提交接口

压测目标是验证秒杀的高并发准入和最终不变量，接口为：

```text
POST /api/seckill/activities/1/orders
```

普通结算是同步数据库事务，应做功能与低并发正确性验证，但它不是秒杀吞吐能力的证明。

### 7.2 正确压测步骤

1. 启动 MySQL、Redis、RocketMQ、用户服务和秒杀服务；
2. 执行 `sql/seckill-reset.sql`，将活动库存恢复；
3. 调用 warmup，让 Redis 库存与数据库活动库存一致；
4. 运行 `benchmark/prepare-tokens.ps1 -Count 200` 生成独立用户 token；
5. 使用 `wrk` 运行 `benchmark/seckill.lua`，它会轮换 token；
6. 等待 MQ 消费完成；
7. 查询订单数、库存、请求状态分布，并记录压测环境和参数。

示例：

```powershell
$env:SECKILL_TOKENS_FILE = "benchmark/tokens.txt"
wrk -t4 -c100 -d20s -s benchmark/seckill.lua http://localhost:8080
```

### 7.3 压测验收不变量

```sql
SELECT COUNT(*) AS orders FROM seckill_order WHERE activity_id = 1;
SELECT total_stock, available_stock FROM seckill_activity WHERE id = 1;
SELECT status, COUNT(*) FROM seckill_request WHERE activity_id = 1 GROUP BY status;
SELECT activity_id, user_id, COUNT(*)
FROM seckill_order GROUP BY activity_id, user_id HAVING COUNT(*) > 1;
```

必须满足：

- `available_stock >= 0`；
- 成功订单数不大于 `total_stock`；
- 最后一条查询没有结果；
- 已准入请求最终变成 `SUCCEEDED` 或明确失败，不能无限停留在 `QUEUED`。

### 7.4 如何回答“压测过吗”

如果尚未运行专业工具：

> 我已经补齐了多用户 token 轮换的可复现压测脚本和压后不变量校验，但当前没有在固定硬件环境下形成可公开的 QPS/P99 基准，所以不会虚构性能数据。压测重点不是只看吞吐，而是验证库存不为负、订单不超量、同用户不重复下单和消息最终完成。

如果已经实际跑完，才补充机器配置、库存、用户数、线程数、连接数、时长、成功/拒绝比例、QPS、平均延迟和 P99。

---

## 8. 秋招高频问题速答

### Q1：为什么 MySQL 条件更新能防超卖？

库存判断和扣减由一条 `UPDATE ... WHERE stock >= quantity` 原子完成。并发事务中，只有仍满足条件的事务会影响一行；返回 0 表示库存已不足。避免了先读后写的竞态。

### Q2：为什么 Redis 扣库存后还要 MySQL 再扣一次？

Redis 是高并发准入层，用于挡流量和快速失败；MySQL 是持久化事实源，处理故障恢复和最终一致性。两层都有扣减，是性能与正确性的分层，不是重复设计。

### Q3：为什么要异步下单，用户会不会等太久？

异步把峰值请求和数据库写入解耦，避免所有请求同步竞争库存行。接口快速返回 requestId，前端轮询最终状态；这是秒杀场景中用少量等待换取系统可用性的取舍。

### Q4：消息丢了怎么办？

请求和 Outbox 同一事务写入。只要数据库成功，就存在可被 Relay 重试的事件；消息发送失败不会让订单请求凭空消失。

### Q5：MQ 重复投递怎么办？

按至少一次投递设计。使用稳定 `event_id` 和数据库唯一约束去重；重复消费查询到已处理事件后不再创建订单或扣库存。

### Q6：Redis 和 MySQL 库存不一致怎么办？

短时不一致是允许的：Redis 是预占，MySQL 是最终落单。消费失败会回补 Redis；Redis 重启或异常时通过活动预热和对账恢复。系统是否正确以 MySQL 不超卖、订单唯一为准。

### Q7：为什么 Outbox 不直接同步发送？

同步发送会让用户请求受 MQ 可用性和网络延迟影响，也不能保证数据库提交和消息发送原子一致。Outbox 将可靠投递从主请求中分离，用户先拿到排队结果，后台可重试。

### Q8：为什么要保存订单快照？

商品名称和价格可能之后修改或下架。订单项保存提交时的商品名、价格和数量，才能保证历史账单可追溯。

### Q9：如果同一用户用两个设备同时抢？

Redis Lua 的用户标记能快速拦截其中一个；即使极端情况下两条请求穿透到数据库，活动-用户唯一约束仍只允许一笔最终订单。

### Q10：下一步怎么把项目做得更接近生产？

普通结算加幂等键；接入真实支付和关单回库存；Outbox 加租约、死信、指标与告警；活动预热加版本号/分布式锁；补充固定环境下的多用户压测报告；Token 使用成熟 JWT 库并将密钥外置。

---

## 9. 面试前一晚的最小准备清单

1. 自己跑一次“登录 → 加购 → 普通结算 → 查看订单”。
2. 自己跑一次“秒杀 → 得到 requestId → 查询 `SUCCEEDED`”。
3. 打开数据库，认识 `seckill_request`、`seckill_order_outbox`、`seckill_order` 的状态变化。
4. 用白纸画出两条链路，能不看代码讲完。
5. 背熟第 8 节的 Q1、Q2、Q4、Q5、Q6。
6. 明确当前边界：模拟支付、普通结算未做幂等键、未公开压测数字。
7. 准备 60 秒项目介绍和 3 分钟深挖介绍各一版。

### 3 分钟讲述模板

> 我做的是一个轻量商城与高并发秒杀系统。用户侧包含注册登录、商品浏览、购物车、模拟结算和订单查询。普通结算使用 MySQL 本地事务，把条件扣库存、订单头、订单项快照和清空购物车包在一起，保证要么全部成功要么全部回滚。
>
> 秒杀链路独立设计。活动开始前把库存预热到 Redis，用户请求通过 Lua 原子完成库存判断、一人一单判断和库存预扣，只有已准入请求进入数据库。随后我在同一个本地事务里保存秒杀请求和 Outbox 事件，Relay 异步投递 RocketMQ，消费者再用 MySQL 条件更新扣最终库存、创建订单并更新请求状态。这样 Redis 用于高并发挡流，MQ 用于削峰，MySQL 用于最终正确性。
>
> 对于可靠性，我按至少一次投递设计，使用 eventId 和活动-用户唯一约束实现幂等；对于 Redis 与数据库的短暂不一致，消费者失败时会回补 Redis，最终以 MySQL 库存和订单为准。当前我没有虚构压测指标，但已提供多用户 token 轮换的可复现压测脚本，并以库存不为负、订单不超量和用户订单唯一作为验收标准。
