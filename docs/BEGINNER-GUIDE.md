# XPlanet 后端快速入门

XPlanet 是一个面向开发者社区的 Spring Boot 微服务后端。它提供用户登录、文章发布与阅读、评论、点赞、热门榜单，以及面向热点访问和异步写入的可靠性设计。

## 1. 先理解整体边界

```text
客户端
  │
  ▼
Gateway :8080 ── JWT 鉴权、链路追踪、统一路由
  ├── User :8083        用户注册、登录、用户资料
  ├── Article :8081     文章、评论、二级缓存、热榜
  └── Interaction :8082 点赞写入、削峰、Outbox

MySQL：业务事实源
Redis：缓存、限流、热榜、点赞增量
RocketMQ：点赞与缓存失效事件的可靠异步传递
```

项目只维护社区后端能力：不提供 AI、Python 服务或 `/api/ai/**` 路由。

## 2. 推荐阅读顺序

1. `README.md`：启动方式、服务端口和可调用接口。
2. `xplanet-gateway`：从 `GatewayAuthenticationFilter` 和 `application.yml` 看统一入口、JWT 校验与路由。
3. `xplanet-article`：从 `ArticleController` → `ArticleServiceImpl` → `ArticleCacheManager` 追踪一次文章详情查询。
4. `xplanet-interaction`：从 `LikeService` → `LikeOutboxPublisher` → `LikeMessageConsumer` 追踪一次点赞。
5. `xplanet-article/outbox` 与 `projection`：理解文章缓存失效和点赞计数最终一致性。
6. `docs/ARCHITECTURE.md`、`docs/HA-AND-DEGRADE.md`：复盘缓存、消息可靠投递、限流与扩展取舍。

## 3. 两条关键链路

### 文章详情：Cache Aside + 两级缓存

1. 先查进程内 Caffeine；未命中再查 Redis；再未命中才访问 MySQL。
2. 热点重建用 Redisson 锁和 Double Check，避免大量请求同时打到数据库。
3. 空值缓存防穿透，随机 TTL 分散过期，Redis 不可用时降级到数据库。
4. 文章更新在本地事务中写入 Outbox；后台投递变更事件，消费者删除 Redis，并广播清理各实例的本地 Caffeine，降低旧值回填窗口。

### 点赞：异步削峰 + 最终一致性

1. 网关和服务端校验 JWT，调用方携带 `Idempotency-Key`。
2. 点赞关系以用户和文章的唯一约束保证幂等；业务事务内同时记录点赞 Outbox。
3. Publisher 将 Outbox 事件投递到 RocketMQ；失败会重试，消费端按事件标识幂等处理。
4. 消费端把点赞增量写入 Redis；文章服务定时批量投影到 MySQL，因此详情页计数与数据库计数短时不同是可预期的最终一致状态。

## 4. 本地运行

准备 JDK 17、Maven、Docker。先启动 MySQL、Redis、RocketMQ，并执行 `sql/init.sql` 及 `sql/migrations` 中的迁移脚本：

```powershell
docker compose -f docker/docker-compose-infra.yml up -d
mvn test

$env:TOKEN_SECRET = "replace-with-a-development-secret-at-least-32-bytes"
mvn -pl xplanet-user -am spring-boot:run
mvn -pl xplanet-article -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-gateway -am spring-boot:run
```

之后访问 `http://localhost:8080/actuator/health`，并通过 Gateway 调用用户、文章、评论、点赞接口。完整命令见根目录 `README.md`。

## 5. 面试时可以怎样概括

“我实现了一个开发者社区后端。读路径使用 Caffeine + Redis + MySQL 的 Cache Aside 两级缓存，并通过空值缓存、分布式锁、双重检查和随机 TTL 处理缓存穿透、击穿和雪崩；写路径将点赞和缓存失效做成事务内 Outbox，通过 RocketMQ 异步投递与消费幂等保证可恢复的最终一致性。Gateway 统一承接 JWT、限流和链路追踪。”
