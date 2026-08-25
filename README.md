# XPlanet 乐购商城

一个可本地运行的轻量商城与高并发秒杀系统。普通商品提供注册登录、商品浏览与搜索、购物车、模拟支付结算和我的订单；限量秒杀保留独立的可靠异步下单链路。

## 功能边界

- 账号注册、BCrypt 密码校验与 Token 登录；
- 商品首页、分类筛选、关键词搜索；
- 购物车加购、数量调整、删除与金额汇总；
- 普通订单：数据库事务、条件扣库存、订单快照、模拟支付；
- 秒杀订单：Redis Lua 原子准入、一人一单、Transactional Outbox、RocketMQ 异步消费、最终数据库条件扣库存与订单状态查询。

支付对接、物流、优惠券和售后并未伪造为已实现能力；这里的 `PAID` 表示本地演示用的模拟支付成功。

## 架构

```text
浏览器（4173）
  ├─ 用户服务（8083）：注册 / BCrypt 校验 / Token
  └─ 商城与秒杀服务（8080）
       ├─ 商品、购物车、普通订单 ── MySQL 事务 + 条件扣库存
       └─ 秒杀 ── Redis Lua ── request + Outbox ── RocketMQ ── 幂等消费者 ── MySQL
```

## 启动

```powershell
docker compose -f docker/docker-compose-seckill-rmq.yml up -d
.\scripts\apply-commerce-schema.ps1 # 重用已有 MySQL 数据卷时执行一次

$env:MYSQL_PORT='13306'; $env:REDIS_PORT='16379'; $env:ROCKETMQ_NS='localhost:19876'
mvn clean package

# 两个 PowerShell 窗口分别执行
java -jar xplanet-user\target\xplanet-user-1.0.0.jar
java -jar xplanet-seckill\target\xplanet-seckill-1.0.0.jar

# 第三个窗口
.\scripts\start-web.ps1
```

访问 [http://localhost:4173](http://localhost:4173)，可使用演示账号 `alice / password`。秒杀活动由定时任务自动预热，首次访问无需手动调用预热接口。

## 验收重点

- 普通链路：登录 → 搜索商品 → 加购 → 调整数量 → 结算 → 我的订单；
- 秒杀链路：登录 → 点击秒杀 → `QUEUED` → `SUCCEEDED`，重复点击返回同一请求；
- 普通订单通过 `UPDATE product ... WHERE stock >= quantity` 防止超卖；
- 秒杀以 Redis 降低数据库竞争，但以 MySQL 条件扣减作为最终库存账本；
- 消息投递和消费者处理均可重试，借助唯一键保持幂等。
