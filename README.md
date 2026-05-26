# XPlanet

> 开发者社区平台 —— 聚焦「读多写多」高并发场景下的**二级缓存、缓存一致性、点赞削峰**三个核心问题的工程实践。

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 项目定位

社区类应用的真实瓶颈在于:**文章详情读多写少(热点读)** 和 **点赞瞬时高并发(热点写)**。
本项目不堆砌技术,只围绕这两个真实诉求做深:

- **Caffeine + Redis 二级缓存**:本地缓存挡热点读、降 Redis 网络开销;分布式缓存兜住多实例
- **Cache Aside + 延迟双删 + MQ 广播**:保证缓存与 DB 最终一致,并解决多实例本地缓存同步失效
- **RocketMQ 削峰**:点赞写入异步化,消费端按文章聚合批量落库,削减 DB 写压力
- **Redisson 分布式锁**:缓存击穿时串行化重建,只放一个线程回源

> 刻意没有引入网关、注册中心、分布式事务、监控全家桶等——在这个业务规模下属于过度设计。
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
| `xplanet-article` | 8081 | 文章服务 | **二级缓存、延迟双删、批量消费点赞落库** |
| `xplanet-interaction` | 8082 | 点赞服务 | **MQ 顺序消息、幂等、削峰** |
| `xplanet-user` | 8083 | 用户服务 | CRUD |

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
# 文章详情(走二级缓存)
curl http://localhost:8081/api/article/1

# 点赞(异步落库)
curl -X POST -H "X-User-Id: 100" http://localhost:8082/api/like/1

# 更新文章(触发延迟双删)
curl -X PUT -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"title":"new","content":"updated","tags":"demo"}' \
  http://localhost:8081/api/article/1
```

或直接打开 `xplanet-web/index.html`(article 默认指向 8081,like 指向 8082)。

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

## 取舍

- 点赞消费用内存缓冲合并落库,实例崩溃会丢失未 flush 的增量;生产可改 Redis Stream 共享缓冲
- 未接注册中心 / 配置中心,服务地址写在配置里
- 单机 Redis,未做哨兵 / Cluster
- authorName 在 article 内简化拼接,未真正走 user 服务


## License

[MIT](LICENSE)
