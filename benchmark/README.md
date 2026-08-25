# 秒杀压测说明

压测对象只有秒杀提交接口：`POST /api/seckill/activities/{activityId}/orders`。普通结算接口 `POST /api/mall/orders/checkout` 是购物车的同步本地事务，不应拿它冒充秒杀接口，也不应使用秒杀的 Redis Lua、异步排队和请求状态查询。

| 维度 | 普通结算 | 秒杀下单 |
| --- | --- | --- |
| 入口 | `/api/mall/orders/checkout` | `/api/seckill/activities/{id}/orders` |
| 返回 | 已创建的 `OrderVO` | `requestId + QUEUED/REJECTED`，随后轮询状态 |
| 库存 | MySQL 条件扣减，事务内完成 | Redis Lua 先准入；MQ 消费后 MySQL 最终扣减 |
| 适用负载 | 常规、低到中并发交易 | 瞬时高并发、库存远小于请求量 |

## 运行步骤

1. 启动 MySQL、Redis、RocketMQ、用户服务和秒杀服务。
2. 重置本地活动库存并预热：执行 `sql/seckill-reset.sql`，再调用 `POST /api/seckill/admin/activities/1/warmup`。
3. 生成足够的独立用户 token（默认 200 个）：

```powershell
./benchmark/prepare-tokens.ps1 -Count 200
```

4. 安装 [wrk](https://github.com/wg/wrk) 后执行；令牌文件会被 Lua 脚本轮换使用：

```powershell
$env:SECKILL_TOKENS_FILE = "benchmark/tokens.txt"
wrk -t4 -c100 -d20s -s benchmark/seckill.lua http://localhost:8080
```

5. 每次压测后必须做业务验收，而不只看 QPS：

```sql
SELECT COUNT(*) AS orders FROM seckill_order WHERE activity_id = 1;
SELECT total_stock, available_stock FROM seckill_activity WHERE id = 1;
SELECT status, COUNT(*) FROM seckill_request WHERE activity_id = 1 GROUP BY status;
```

通过条件：数据库库存不为负；成功订单数不超过 `total_stock`；同一 `(activity_id, user_id)` 没有重复订单；请求最终进入 `SUCCEEDED` 或明确失败状态。记录结果时同时写明机器配置、库存、用户数、线程、连接数、时长及服务配置，不能引用其他项目的 QPS。
