# XPlanet Flash Sale

一个可本地运行、可压测验证的高并发限量商品下单系统。它不做购物车、支付和完整商城，而是把秒杀中最难讲清的链路做成闭环：**原子准入、异步削峰、可靠消息、幂等落单、最终库存保护和失败补偿**。

## 核心流程

```text
POST /activities/{id}/orders (Bearer Token)
  -> Redis Lua: 活动预热库存 + 一人一单标记 + DECR（一个原子操作）
  -> MySQL: seckill_request + seckill_order_outbox（同一事务）
  -> 定时 Relay: Outbox 重试投递 RocketMQ
  -> Consumer: eventId 幂等 + DB 条件扣库存 + 唯一订单
  -> GET /requests/{requestId}: QUEUED / SUCCEEDED / FAILED
```

Redis 是高并发准入层，不是最终账本；`UPDATE ... SET available_stock=available_stock-1 WHERE available_stock>0` 是最终防超卖保护。RocketMQ 为至少一次投递，因此消费者同时使用 `event_id` 唯一键和 `(activity_id,user_id)` 唯一键。若数据库写入失败，准入记录会通过 Lua 回补；若重复消息在扣库后才被发现，会回补数据库库存。

## 运行

```powershell
docker compose -f docker/docker-compose-infra.yml up -d
.\scripts\apply-schema.ps1 # only required when reusing an existing Docker MySQL volume
# 在两个 PowerShell 窗口分别执行：
mvn -f xplanet-user/pom.xml spring-boot:run
mvn -f xplanet-seckill/pom.xml spring-boot:run
```

如果本机同时运行了旧版 XPlanet（其 RocketMQ Broker 对容器内地址注册），使用隔离消息队列进行秒杀验证：

```powershell
docker compose -f docker/docker-compose-seckill-rmq.yml up -d
$env:ROCKETMQ_NS='localhost:19876'
mvn -f xplanet-seckill/pom.xml spring-boot:run
```

启动后使用 `POST http://localhost:8083/api/user/login`，请求体 `{"username":"alice","password":"password"}` 获得 token。然后对 8080 的受保护请求加头：`Authorization: Bearer <token>`。

首次启动需执行 Redis 预热（演示账号任意 token 均可）：

```powershell
Invoke-RestMethod -Method Post -Headers @{Authorization='Bearer <token>'} http://localhost:8080/api/seckill/admin/activities/1/warmup
Invoke-RestMethod -Method Post -Headers @{Authorization='Bearer <token>'} http://localhost:8080/api/seckill/activities/1/orders
Invoke-RestMethod -Headers @{Authorization='Bearer <token>'} http://localhost:8080/api/seckill/requests/<requestId>
```

`sql/seckill-reset.sql` 仅用于本地复测：清理订单与请求，并重置活动数据库库存；之后必须再次预热 Redis。

## 验收标准

- 100 个库存、并发请求数大于 100 时，`seckill_order` 成功订单数不超过 100；
- 相同用户重复提交始终返回同一个 requestId；
- 停止 RocketMQ 后，Outbox 状态为 `RETRY`，恢复 MQ 后最终变为 `SENT`；
- 同一 `eventId` 重复投递不会产生第二笔订单，也不会多扣数据库库存。

完整设计与压测步骤见 [docs/SECKILL-DESIGN.md](docs/SECKILL-DESIGN.md)。
