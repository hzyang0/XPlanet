# 秒杀系统设计与验收手册

## 范围

本项目只实现“限量 SKU 的秒杀下单”。支付、购物车、优惠叠加和分布式事务协调器不在范围内；它们不会帮助证明核心并发不变量，反而会使本地演示不稳定。

## 四个不变量

1. 任一活动成功订单数不大于 `total_stock`。
2. 同一用户在同一活动至多拥有一笔订单。
3. 数据库提交的下单请求最终可被 MQ 投递，或保留为可重试 Outbox。
4. 重复投递不会创建第二笔订单或重复扣库存。

## 表与职责

| 表 | 作用 |
| --- | --- |
| `seckill_activity` | 商品活动与最终可用库存 |
| `seckill_request` | 用户端可轮询的排队状态 |
| `seckill_order_outbox` | 请求事务内的可靠消息记录 |
| `seckill_order` | 最终订单；`event_id`、用户活动组合均唯一 |

## 手动验收

1. 启动 Docker 基础设施、`xplanet-user` 和 `xplanet-seckill`。
2. 登录 alice、bob、carol 获取独立 token，调用预热接口。
3. 三个用户并发请求下单，轮询 request 状态应变为 `SUCCEEDED`；同一 token 再次请求应返回原 requestId。
4. 用 JMeter 对下单接口压入大于活动库存的并发请求；数据库执行：

```sql
SELECT COUNT(*) FROM seckill_order WHERE activity_id=1;
SELECT available_stock FROM seckill_activity WHERE id=1;
```

订单数必须不超过 100，库存不能为负。
5. 临时停止 RocketMQ 后提交一个请求，检查 Outbox 为 `RETRY`；恢复 RocketMQ，等待 Relay 重试，最终订单成功、Outbox 为 `SENT`。

不能把“Redis Lua 预扣成功”称为订单成功；它只代表拿到排队资格。只有消费者的数据库事务提交后，状态才转为 `SUCCEEDED`。
