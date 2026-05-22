# XPlanet

> High-concurrency developer community platform — 二级缓存 / 缓存一致性 / 削峰 / 限流降级 的工程实践。

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 项目简介

XPlanet 是一个面向技术内容分享的社区平台,核心是**读多写多的高并发文章 / 互动场景**。
项目工程化实现了:

- **Caffeine + Redis 二级缓存**: 本地缓存挡住热点流量,Redis 兜住分布式
- **Cache Aside + 延迟双删 + Canal**: 三层防线保证缓存与 DB 的最终一致
- **RocketMQ 削峰**: 点赞写入异步化,消费端按文章聚合批量落库,削减 DB 写压力
- **Sentinel 热点参数限流 + 降级**: 单文章独立流控,Redis 故障时降级返回
- **Spring Cloud Gateway**: 入口层 IP 令牌桶 + TraceId 注入
- **Prometheus + Grafana**: 端到端可观测性

## 架构

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

```
Gateway (8080) ──┬── Article    (8081)  ←── Canal Client ←── MySQL binlog
                 ├── Interaction(8082)  
                 └── User       (8083)
                        │
              Redis / RocketMQ / MySQL
```

## 模块说明

| 模块 | 职责 | 关键特性 |
|---|---|---|
| `xplanet-common` | 公共响应、异常、常量 | 全局异常处理、缓存 key 规范 |
| `xplanet-api` | DTO / VO | 跨服务数据契约 |
| `xplanet-article` | 文章服务 | **二级缓存、双删、Sentinel、批量消费** |
| `xplanet-interaction` | 点赞服务 | **MQ 顺序消息、幂等、削峰** |
| `xplanet-user` | 用户服务 | CRUD |
| `xplanet-gateway` | 网关 | **Redis 令牌桶、TraceId** |
| `xplanet-canal-client` | binlog 监听 | **缓存一致性兜底** |
| `xplanet-web` | 演示前端 | 单 HTML,验证全流程 |

## 快速开始

### 前置

- Docker / Docker Compose
- (本机跑) JDK 17 + Maven 3.9+

### Windows（PowerShell 一键）

在仓库根目录 `xplanet/` 下依次执行（需已安装 **Docker Desktop**、**JDK 17**、**Maven 3.9+** 且均在 PATH）：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned -Force   # 若脚本被禁止执行，仅需一次
cd D:\User\Desktop\project\xplanet   # 按你的实际路径修改
.\scripts\setup-infra.ps1            # MySQL / Redis / RocketMQ / Canal / Prometheus / Grafana
.\scripts\build.ps1                  # mvn clean package -DskipTests
.\scripts\start-local.ps1            # 5 个 Spring Boot 各开新窗口
```

Grafana 本机地址为 **http://localhost:3100**（避免占用常见前端 3000 端口）。

### 一键启动基础设施

```bash
docker compose -f docker/docker-compose-infra.yml up -d
# 等待 MySQL + Canal 健康 ~ 30s
docker compose -f docker/docker-compose-infra.yml ps
```

会启动:
- MySQL 8.0 (3306) — 自动建库 + 测试数据
- Redis 7        (6379)
- RocketMQ      (9876 namesrv + 10911 broker)
- Canal Server  (11111)
- Prometheus    (9090)
- Grafana       (3100→容器 3000, admin/admin)

### 启动应用

```bash
docker compose -f docker/docker-compose-app.yml up -d --build
```

或本机直接跑(开 5 个终端):
```bash
mvn -pl xplanet-article -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-user -am spring-boot:run
mvn -pl xplanet-gateway -am spring-boot:run
mvn -pl xplanet-canal-client -am spring-boot:run
```

### 验证

打开 `xplanet-web/index.html`,默认 Gateway 指向 `http://localhost:8080`。

```bash
# 获取文章详情(经过二级缓存)
curl http://localhost:8080/api/article/1

# 点赞(异步落库)
curl -X POST -H "X-User-Id: 100" http://localhost:8080/api/like/1

# 更新文章(触发双删 + Canal 兜底)
curl -X PUT -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"title":"new","content":"updated","tags":"demo"}' \
  http://localhost:8080/api/article/1
```

## 性能测试

详见 [`benchmark/README.md`](benchmark/README.md) 与 [`docs/benchmark-results.md`](docs/benchmark-results.md)。

请使用提供的 wrk 脚本自行压测并填入结果。**严禁在简历上引用未经自测的数字。**

## 监控

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3100 (admin / admin)
- 应用 metrics 端点: `http://localhost:<port>/actuator/prometheus`

自定义指标:
- `xplanet_article_cache_l1_hit_rate` — L1 命中率
- `xplanet_article_cache_l1_size`      — L1 当前条目数
- `http_server_requests_seconds`        — HTTP 接口耗时 P99 等

## 项目结构

```
xplanet/
├── pom.xml                          # 父 POM
├── xplanet-common/                  # 公共
├── xplanet-api/                     # 契约
├── xplanet-article/                 # 文章服务 ★ 核心
├── xplanet-interaction/             # 点赞服务 ★ 核心
├── xplanet-user/                    # 用户服务
├── xplanet-gateway/                 # 网关
├── xplanet-canal-client/            # Canal 客户端
├── xplanet-web/                     # 演示前端
├── docker/                          # 部署
│   ├── docker-compose-infra.yml
│   ├── docker-compose-app.yml
│   ├── Dockerfile.app
│   ├── mysql.cnf                    # binlog 开启
│   ├── canal-instance.properties
│   └── prometheus.yml
├── sql/
│   ├── init.sql
│   └── canal-user.sql
├── benchmark/
│   ├── article_detail.lua           # wrk 脚本
│   ├── like.lua
│   └── README.md
└── docs/
    ├── ARCHITECTURE.md              # 深度架构文档
    └── benchmark-results.md         # 压测结果(自填)
```

## 路线图

- [ ] 接入 Nacos 配置中心,Sentinel 规则动态推送
- [ ] Redis 哨兵 / Cluster 模式
- [ ] LikeMessageConsumer buffer 持久化(Redis Stream)
- [ ] 接入 SkyWalking 分布式追踪
- [ ] e2e 集成测试

## License

[MIT](LICENSE)
