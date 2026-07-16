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
- **Cache Aside + 缓存失效 Outbox + MQ 广播**:DB 变更与立即/延迟失效事件同事务提交，支持故障恢复和多实例 L1/L2 失效
- **三大问题防护**:空值缓存防穿透、分布式锁防击穿、TTL 随机防雪崩

**高并发写**
- **RocketMQ 削峰**:点赞写入异步化,消费端按文章聚合批量落库,削减 DB 写压力
- **Transactional Outbox**:点赞关系事件和文章缓存失效事件均与业务状态同事务提交，MQ 不可用时可恢复重试
- **持久化幂等投影**:eventId 唯一去重,按文章批量合并 delta,计数更新与事件完成标记同事务

**并发控制与容错**
- **Redisson 分布式锁**:缓存击穿时串行化重建,只放一个线程回源
- **轻量限流**:注解 + Redis Lua 固定窗口,防接口被刷(比 Sentinel 轻、原理透明)
- **服务降级**:user 服务故障返回兜底作者名、MQ 故障由 Outbox 退避重试、重建抢锁失败降级查库

**服务协作与业务**
- **服务间调用**:article 调 user 服务取作者名；interaction 点赞前轻量校验文章有效性，均配置显式超时
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
     │              │   │ 锁+限流) │   │ L1广播失效) │
     └──────────────┘   └──────────┘   └─────────────┘
```

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 模块说明

| 模块 | 端口 | 职责 | 关键特性 |
|---|---|---|---|
| `xplanet-common` | - | 公共响应、异常、常量 | 全局异常处理、缓存 key 规范 |
| `xplanet-api` | - | DTO / VO | 跨服务数据契约 |
| `xplanet-article` | 8081 | 文章服务 | **二级缓存、延迟双删、批量消费点赞落库、列表分页、评论、限流、调用 user 服务** |
| `xplanet-interaction` | 8082 | 点赞服务 | **文章有效性校验、关系状态机、Transactional Outbox、可恢复 MQ relay** |
| `xplanet-user` | 8083 | 用户服务 | 用户查询、bcrypt 登录与 JWT 签发 |

## 快速开始

### 方式一：本地混合模式（开发推荐）

推荐:中间件用 Docker,3 个服务用 IDE / 命令行跑(便于断点调试)。

先设置所有服务共享的 JWT 签名密钥（至少32字符，实际使用请生成随机值）：

```powershell
$env:TOKEN_SECRET="replace-with-a-random-secret-at-least-32-bytes"
```

Docker Compose 用户可复制 `.env.example` 为 `.env` 后替换其中的示例值。

#### 1. 启动中间件并迁移数据库

```powershell
.\scripts\setup-infra.ps1
```

脚本会启动 MySQL(3306)、Redis(6379)、RocketMQ(namesrv 9876 + broker 10911)，
选择供宿主机 JVM 使用的 broker 广播地址，等待 MySQL 就绪并执行 Flyway。
当前完整表结构以 V004 为 baseline，后续新增 V005 及以上迁移会自动按顺序执行，无需手工修改数据库。

#### 2. 编译

```bash
mvn -DskipTests clean install
```

#### 3. 启动 3 个服务

IDEA 直接 Run:`ArticleApplication`(8081)、`InteractionApplication`(8082)、`UserApplication`(8083)。

或命令行(Windows 可用 `scripts/start-local.ps1` 一键起):
```bash
mvn -pl xplanet-article     -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-user        -am spring-boot:run
```

#### 4. 验证

```bash
# 文章详情(走二级缓存,读操作免登录)
curl http://localhost:8081/api/article/1

# 文章列表(分页)
curl "http://localhost:8081/api/article/list?pageNum=1&pageSize=10"

# 登录拿 token(写操作需要)
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password"}' http://localhost:8083/api/user/login \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 点赞(带 token,异步落库)
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/like/1

# 发评论(带 token)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"articleId":1,"content":"不错"}' http://localhost:8081/api/comment
```

或直接打开 `xplanet-web/index.html`:先在顶部用用户名(alice/bob)登录,再操作。
本地初始化账号 `alice`、`bob`、`demo` 的演示密码均为 `password`，数据库中只保存 bcrypt 哈希。

三个服务与中间件都启动后，可执行可重复的端到端冒烟测试：

```powershell
.\scripts\smoke-test.ps1
```

脚本会验证健康检查、登录、文章查询、可靠缓存失效 Outbox、重复点赞幂等、MQ 消费、持久化计数投影和未登录拦截，
并在结束时恢复原点赞状态和文章计数。RocketMQ 冷启动首次建立消费者订阅可能需要几十秒，脚本默认等待上限为 90 秒。

### 方式二：全 Docker 模式（演示推荐）

先设置密钥，然后一条命令完成基础设施启动、数据库迁移、应用镜像构建和健康检查：

```powershell
$env:TOKEN_SECRET="replace-with-a-random-secret-at-least-32-bytes"
.\scripts\start-docker.ps1
.\scripts\smoke-test.ps1
```

若所在网络无法访问 Docker Hub，可临时通过参数或 `DOCKER_BASE_REGISTRY` 指定兼容镜像前缀；
仓库默认值仍为官方 `docker.io/library`，避免把特定镜像站写死到项目中。

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
│   ├── broker-host.conf           # 宿主机 JVM 使用的 broker 广播地址
│   └── broker-docker.conf         # 容器应用使用的 broker 广播地址
├── sql/
│   ├── init.sql                   # 新数据卷完整结构
│   └── migrations/                # Flyway 增量迁移
├── scripts/                 # 构建、迁移、两种模式启动、端到端冒烟测试
├── .github/workflows/ci.yml # 单测、Compose 与脚本语法检查
├── benchmark/               # wrk 压测脚本
└── docs/
    ├── ARCHITECTURE.md
    └── benchmark-results.md
```

## 已知取舍(面试可主动展开)

- 中间件单机(Redis/MySQL/RocketMQ),未做集群/哨兵/主从——这是**高可用**演进项,
  与「应用可水平扩展」是两回事(应用层已按无状态多实例设计)。见 `docs/HA-AND-DEGRADE.md`
- Token 使用标准 JWT/JWS 库签发和校验，签名密钥通过 `TOKEN_SECRET` 外部注入
- 登录使用 Spring PasswordEncoder 校验 bcrypt 哈希；演示账号共用初始密码，仅用于本地数据
- 点赞以 `like_relation` 为事实源,通过 Outbox 至少一次投递；消费端唯一事件表和事务批量投影吸收重复并支持崩溃恢复

这些是有意识的取舍,不是不知道,面试时可展开聊改造方案。

## 设计立场:可水平扩展 ≠ 高可用

- **应用层**按「可水平扩展」设计:无状态(token 无状态、缓冲在 Redis)、L1 本地缓存 + MQ 广播
  保证多实例部署时本地缓存一致——这些是为水平扩展服务的,不是过度设计
- **中间件高可用**(集群/哨兵)按需演进,当前单机够用
- 二者是不同维度,本项目明确选择「应用可扩展 + 中间件暂单机」,演示跑单实例

## License

[MIT](LICENSE)
