# XPlanet 后端项目地图

XPlanet 是一个开发者社区后端，采用 Gateway + 用户、文章、互动三个 Spring Boot 服务。仓库不包含 AI、Agent 或 Python 运行时。

## 模块与职责

| 模块 | 默认端口 | 职责 |
| --- | ---: | --- |
| `xplanet-gateway` | 8080 | JWT 校验、Trace ID、CORS 和 `/api/**` 路由 |
| `xplanet-user` | 8083 | 注册、登录、密码哈希与用户资料 |
| `xplanet-article` | 8081 | 文章、评论、二级缓存、热榜、缓存失效 Outbox、点赞计数投影 |
| `xplanet-interaction` | 8082 | 点赞关系、幂等、点赞 Outbox 与 RocketMQ 投递 |
| `xplanet-common` | - | 鉴权、限流、统一响应、Redis/Lua 等公共能力 |
| `xplanet-api` | - | 服务间共享 DTO、请求和 VO |

依赖组件：MySQL 为事实源；Redis 负责缓存、限流、热榜和点赞增量；RocketMQ 负责点赞和缓存失效事件的异步可靠传递。

## 关键调用链

### 文章详情

`Gateway -> ArticleController -> ArticleServiceImpl -> ArticleCacheManager -> Caffeine -> Redis -> MySQL`

缓存重建采用 Redisson 锁与 Double Check；空值缓存、随机 TTL 和数据库降级分别应对穿透、雪崩和 Redis 故障。

### 点赞

`Gateway -> LikeService -> like_relation/like_outbox -> LikeOutboxPublisher -> RocketMQ -> LikeMessageConsumer -> Redis delta -> LikeCountProjectionJob -> MySQL`

数据库唯一约束、幂等键和消费端事件状态共同保证重复投递不会重复计数。Outbox 将业务状态变化和待投递事件写在同一个事务中，失败后由调度任务重试。

### 文章变更

`ArticleServiceImpl -> article_change_outbox -> RocketMQ -> ArticleCacheInvalidator -> Redis + Caffeine`

该链路用于多实例下同步清理文章详情缓存，避免只删除本地缓存造成旧值长期存在。

## 常用验证命令

```powershell
docker compose -f docker/docker-compose-infra.yml up -d
mvn test

$env:TOKEN_SECRET = "replace-with-a-development-secret-at-least-32-bytes"
mvn -pl xplanet-user -am spring-boot:run
mvn -pl xplanet-article -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-gateway -am spring-boot:run
```

通过 `http://localhost:8080/actuator/health` 查看网关健康状态；根路径返回可用路由与前端入口提示。
