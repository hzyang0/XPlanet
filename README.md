# XPlanet

> 开发者社区平台 —— 聚焦「读多写多」高并发场景下的**二级缓存、缓存一致性、点赞削峰**三个核心问题的工程实践。

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 项目定位

社区类应用的真实瓶颈在于:**文章详情读多写少(热点读)** 和 **点赞瞬时高并发(热点写)**。
本项目围绕这两个真实诉求做深,覆盖以下能力:

**缓存与一致性**
- **Caffeine + Redis 二级缓存**:本地缓存挡热点读、降 Redis 网络开销;分布式缓存兜住多实例
- **Cache Aside + 延迟双删 + MQ 广播**:保证缓存与 DB 最终一致,并解决多实例本地缓存同步失效
- **三大问题防护**:空值缓存防穿透、分布式锁防击穿、TTL 随机防雪崩

**高并发写**
- **RocketMQ 削峰**:点赞写入异步化,消费端按文章聚合批量落库,削减 DB 写压力
- **Redis 共享缓冲**:点赞缓冲用 Redis Hash(HINCRBY 原子累加),实例崩溃不丢未落库增量
- **幂等 + 顺序**:actionId 去重防重投,按 userId 分区保证同用户操作有序

**并发控制与容错**
- **Redisson 分布式锁**:缓存击穿时串行化重建,只放一个线程回源
- **轻量限流**:注解 + Redis Lua 固定窗口,防接口被刷(比 Sentinel 轻、原理透明)
- **服务降级**:user 服务故障返回兜底作者名、Redis 异常友好提示、重建抢锁失败降级查库

**服务协作与业务**
- **服务间调用**:article 调 user 服务取作者名,带调用缓存 + 降级
- **文章列表分页 + 评论(两级嵌套)**:完整的社区业务闭环

> 刻意没有引入网关、注册中心、分布式事务、监控全家桶等——在这个业务规模下属于过度设计。
> 高可用(集群/哨兵/多实例)作为演进方向写在 [`docs/HA-AND-DEGRADE.md`](docs/HA-AND-DEGRADE.md),按需扩展。
> 工程的价值在于「按场景选型」,而不是技术数量。

## 架构

```
              ┌──────────────┐   ┌──────────────┐   ┌──────────┐
   前端演示页 ─┤ Article 8081 │   │Interaction   │   │ User     │
              │ 文章+二级缓存 │   │  8082 点赞    │   │ 8083     │
              └──────┬───────┘   └──────┬───────┘   └────┬─────┘
                     │                  │                │
            ┌────────┴──────────────────┴────────────────┘
            │
     ┌──────┴───────┐   ┌──────────┐   ┌─────────────┐
     │ MySQL        │   │  Redis   │   │  RocketMQ   │
     │ (主数据)      │   │(L2缓存+  │   │(点赞削峰 +   │
     │              │   │ 锁+计数) │   │ L1广播失效) │
     └──────────────┘   └──────────┘   └─────────────┘
```

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 模块说明

| 模块 | 端口 | 职责 | 关键特性 |
|---|---|---|---|
| `xplanet-common` | - | 公共响应、异常、常量 | 全局异常处理、缓存 key 规范 |
| `xplanet-api` | - | DTO / VO | 跨服务数据契约 |
| `xplanet-article` | 8081 | 文章服务 | **二级缓存、延迟双删、批量消费点赞落库、列表分页、评论、限流、调用 user 服务** |
| `xplanet-interaction` | 8082 | 点赞服务 | **MQ 顺序消息、幂等、削峰、Redis 降级** |
| `xplanet-user` | 8083 | 用户服务 | 用户信息 CRUD(被 article 调用) |

## 快速开始(本地混合模式)

推荐:中间件用 Docker,3 个服务用 IDE / 命令行跑(便于断点调试)。

### 1. 启动中间件

```bash
docker compose -f docker/docker-compose-infra.yml up -d
# 等待 MySQL healthy(约 20-30s)
docker compose -f docker/docker-compose-infra.yml ps
```

启动 MySQL(3306,自动建表+测试数据)、Redis(6379)、RocketMQ(namesrv 9876 + broker 10911)。

### 2. 编译

```bash
mvn -DskipTests clean install
```

### 3. 启动 3 个服务

IDEA 直接 Run:`ArticleApplication`(8081)、`InteractionApplication`(8082)、`UserApplication`(8083)。

或命令行(Windows 可用 `scripts/start-local.ps1` 一键起):
```bash
mvn -pl xplanet-article     -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-user        -am spring-boot:run
```

### 4. 验证

```bash
# 文章详情(走二级缓存,读操作免登录)
curl http://localhost:8081/api/article/1

# 文章列表(分页)
curl "http://localhost:8081/api/article/list?pageNum=1&pageSize=10"

# 登录拿 token(写操作需要)
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"username":"alice"}' http://localhost:8083/api/user/login \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 点赞(带 token,异步落库)
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/like/1

# 发评论(带 token)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"articleId":1,"content":"不错"}' http://localhost:8081/api/comment
```

或直接打开 `xplanet-web/index.html`:先在顶部用用户名(alice/bob)登录,再操作。

## 性能测试

见 [`benchmark/README.md`](benchmark/README.md) 与 [`docs/benchmark-results.md`](docs/benchmark-results.md)。
请用 wrk 脚本自测并填入真实数据,不要引用未经验证的数字。

## 项目结构

```
xplanet/
├── pom.xml
├── xplanet-common/          # 公共
├── xplanet-api/             # 契约
├── xplanet-article/         # 文章服务 ★ 核心
├── xplanet-interaction/     # 点赞服务 ★ 核心
├── xplanet-user/            # 用户服务
├── xplanet-web/             # 演示前端
├── docker/
│   ├── docker-compose-infra.yml   # 中间件(本地混合模式用这个)
│   ├── docker-compose-app.yml     # 全 Docker 模式(可选)
│   ├── Dockerfile.app
│   └── broker.conf                # RocketMQ broker IP 配置
├── sql/init.sql
├── benchmark/               # wrk 压测脚本
└── docs/
    ├── ARCHITECTURE.md
    └── benchmark-results.md
```

## 已知取舍(面试可主动展开)

- 中间件单机(Redis/MySQL/RocketMQ),未做集群/哨兵/主从——这是**高可用**演进项,
  与「应用可水平扩展」是两回事(应用层已按无状态多实例设计)。见 `docs/HA-AND-DEGRADE.md`
- Token 鉴权是简化的自实现 HMAC 签名(JWT 简化版),生产应用成熟 JWT 库 + 密钥管理
- 登录未校验密码(demo 聚焦鉴权链路),生产需 BCrypt 校验
- 点赞计数批量合并落库,极端情况(flush 前实例崩)靠 Redis 共享缓冲 + 明细表唯一约束兜底

这些是有意识的取舍,不是不知道,面试时可展开聊改造方案。

## 设计立场:可水平扩展 ≠ 高可用

- **应用层**按「可水平扩展」设计:无状态(token 无状态、缓冲在 Redis)、L1 本地缓存 + MQ 广播
  保证多实例部署时本地缓存一致——这些是为水平扩展服务的,不是过度设计
- **中间件高可用**(集群/哨兵)按需演进,当前单机够用
- 二者是不同维度,本项目明确选择「应用可扩展 + 中间件暂单机」,演示跑单实例

## License

[MIT](LICENSE)
