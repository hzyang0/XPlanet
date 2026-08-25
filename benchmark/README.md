# 压测说明

不要复用其他项目的 QPS 数字。本项目只记录在本机、明确硬件和服务配置下获得的结果。

1. 启动 MySQL、Redis、RocketMQ、用户服务、秒杀服务并预热活动 1。
2. 用不同用户令牌进行正确性验证；同一个 token 的重复请求应被“一人一单”拒绝。
3. 复制 `seckill.lua`，替换令牌后运行：

```bash
wrk -t4 -c100 -d20s -s benchmark/seckill.lua http://localhost:8080
```

4. 每次压测后检查 `seckill_order` 总量及 `seckill_activity.available_stock`。库存永不为负、订单数不超过活动库存，才算通过。
