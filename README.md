# XPlanet

> 面向开发者的可追溯研究与社区平台：高并发社区底座、Agent 工作流、人工审核和幂等发布已形成首个可运行闭环。

> **演进说明（2026-07-20）**：当前代码是可运行的 v2 基线；后续将按 [Agent-first v3 总体重构方案](docs/XPlanet-Agent-First整体重构方案.md) 转向以 Research Thread、真实工具循环、并行 Researcher、Evidence Graph、Artifact 和站内知识反馈为中心。目标能力在完成验收前不会描述为已实现。

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

**AI 研究控制面（当前已实现）**
- **任务状态与请求幂等**：`xplanet-ai` 管理私有研究任务、运行实例、预算上限和版本条件状态迁移
- **可靠长任务命令**：任务/运行/Outbox 同事务提交，带租约 relay 向 RocketMQ 投递请求与取消命令
- **可追溯 Agent 图**：`xplanet-agent` 用 LangGraph 执行输入校验、规划、研究、证据整理、写作和 Critic，工具次数、来源数和总超时均有上限
- **可恢复长任务**：每个节点把版本化 checkpoint 写入 MySQL，Agent 崩溃后由 RocketMQ 重投并从下一节点恢复，失败最多尝试 3 次后进入明确 `FAILED`
- **证据与进度闭环**：来源、证据、引用和报告在同一事务校验落库；步骤进度写 Redis Stream，并由 `xplanet-ai` 通过 SSE 输出
- **评测与指标**：固定 JSONL 数据集离线评测结构成功率与引用索引有效性；Micrometer 暴露执行结果、节点耗时和 checkpoint 指标
- **Human-in-the-loop**：报告必须由任务所有者确认，随后通过内部 OpenFeign 调用幂等发布为文章；重复确认返回同一文章

> 默认 `offline-demo` 用于零成本、可复现验收；另提供显式启用的 `openai-web` Responses API + Web Search 适配器。真实路径需要 API Key，目前只完成模拟契约测试，不能把离线评测结果描述为联网回答质量或事实正确率。

> 已引入轻量 Spring Cloud Gateway 作为统一外部入口；注册中心、分布式事务和监控全家桶仍按业务规模暂不引入。
> 高可用(集群/哨兵/多实例)作为演进方向写在 [`docs/HA-AND-DEGRADE.md`](docs/HA-AND-DEGRADE.md),按需扩展。
> 工程的价值在于「按场景选型」,而不是技术数量。

## 架构

```
 用户/演示页 ──→ Gateway 8080 ─┬─→ Article 8081
                               ├─→ Interaction 8082
                               ├─→ User 8083
                               └─→ AI 8084 ──HTTP──→ Agent 8000
                                      │                  │
 MySQL ←── 业务事实/Outbox ────────────┤                  │
 Redis ←── 缓存/限流/进度流 ───────────┤                  │
 RocketMQ ←── 可靠异步命令 ────────────┴──────────────────┘
```

第一次接触项目请先读 [`docs/BEGINNER-GUIDE.md`](docs/BEGINNER-GUIDE.md)，架构细节见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 模块说明

| 模块 | 端口 | 职责 | 关键特性 |
|---|---|---|---|
| `xplanet-gateway` | 8080 | 统一外部入口 | **路由、CORS、TraceId、JWT 前置校验；Docker 模式唯一暴露端口** |
| `xplanet-common` | - | 公共响应、异常、常量 | 全局异常处理、缓存 key 规范 |
| `xplanet-api` | - | DTO / VO | 跨服务数据契约 |
| `xplanet-article` | 8081 | 文章服务 | **二级缓存、延迟双删、批量消费点赞落库、列表分页、评论、限流、调用 user 服务** |
| `xplanet-interaction` | 8082 | 点赞服务 | **文章有效性校验、关系状态机、Transactional Outbox、可恢复 MQ relay** |
| `xplanet-user` | 8083 | 用户服务 | 用户查询、bcrypt 登录与 JWT 签发 |
 | `xplanet-ai` | 8084 | AI 控制面 | **私有任务、请求幂等、预算、可靠命令、checkpoint、模型用量、指标、SSE、审核发布** |
 | `xplanet-agent` | 8000（仅内部） | Python 执行面 | **LangGraph 有界工作流、断点恢复、离线/联网 Provider、来源/证据/引用生成** |

## 快速开始

### 方式一：本地混合模式（开发推荐）

推荐:中间件用 Docker，Python Agent 和 5 个 Java 服务用 IDE / 命令行跑（便于断点调试）。

先设置所有服务共享的 JWT 签名密钥（至少32字符，实际使用请生成随机值）：

```powershell
$env:TOKEN_SECRET="replace-with-a-random-secret-at-least-32-bytes"
$env:AGENT_INTERNAL_TOKEN=$env:TOKEN_SECRET
```

Docker Compose 用户可复制 `.env.example` 为 `.env` 后替换其中的示例值。

#### 1. 启动中间件并迁移数据库

```powershell
.\scripts\setup-infra.ps1
```

脚本会启动 MySQL(3306)、Redis(6379)、RocketMQ(namesrv 9876 + broker 10911)，
选择供宿主机 JVM 使用的 broker 广播地址，等待 MySQL 就绪并执行 Flyway。
当前完整表结构以 V004 为 baseline，V005～V007 增加 AI 控制面、幂等发布和持久化 checkpoint；后续迁移会自动按顺序执行，无需手工修改数据库。

#### 2. 编译

```bash
mvn -DskipTests clean install
```

#### 3. 启动 Python Agent 和 5 个 Java 服务

先在一个终端启动内部 Agent：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".\xplanet-agent[test]"
$env:AGENT_INTERNAL_TOKEN=$env:TOKEN_SECRET
$env:AI_CONTROL_URL="http://localhost:8084"
.\.venv\Scripts\python.exe -m uvicorn xplanet_agent.api:app --host 0.0.0.0 --port 8000
```

默认离线模式无需模型密钥。只有明确需要联网研究并接受外部 API 成本时才设置：

```powershell
$env:AGENT_PROVIDER="openai-web"
$env:OPENAI_API_KEY="replace-with-your-key"
$env:OPENAI_MODEL="gpt-5.6-terra"
```

IDEA 直接 Run:`ArticleApplication`(8081)、`InteractionApplication`(8082)、`UserApplication`(8083)、`AiApplication`(8084)、`GatewayApplication`(8080)。先启动下游服务，最后启动 Gateway。

或命令行(Windows 可用 `scripts/start-local.ps1` 一键起):
```bash
mvn -pl xplanet-article     -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-user        -am spring-boot:run
mvn -pl xplanet-ai          -am spring-boot:run
mvn -pl xplanet-gateway     -am spring-boot:run
```

#### 4. 验证

```bash
# 文章详情(走二级缓存,读操作免登录)
curl http://localhost:8080/api/article/1

# 文章列表(分页)
curl "http://localhost:8080/api/article/list?pageNum=1&pageSize=10"

# 登录拿 token(写操作需要)
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password"}' http://localhost:8080/api/user/login \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 点赞(带 token,异步落库)
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/like/1

# 发评论(带 token)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"articleId":1,"content":"不错"}' http://localhost:8080/api/comment
```

或直接打开 `xplanet-web/index.html`:先在顶部用用户名(alice/bob)登录,再操作。
本地初始化账号 `alice`、`bob`、`demo` 的演示密码均为 `password`，数据库中只保存 bcrypt 哈希。

全部服务与中间件都启动后，可执行可重复的端到端冒烟测试：

```powershell
.\scripts\smoke-test.ps1
```

脚本会验证健康检查、登录、AI 任务私有读取/幂等/跨用户隔离、Agent 执行、7 个进度与 checkpoint、Prometheus 执行/恢复指标、来源—证据—引用闭包、人工审核、重复发布幂等和取消，
同时覆盖文章查询、可靠缓存失效 Outbox、重复点赞幂等、MQ 消费、持久化计数投影和未登录拦截。脚本会恢复原点赞状态和文章计数；研究任务和发布文章作为验收记录保留。

验证 Agent 在节点提交后进程崩溃仍能恢复：

```powershell
.\scripts\test-agent-recovery.ps1
```

运行不调用外部模型的固定数据集评测：

```powershell
.\.venv\Scripts\python.exe -m xplanet_agent.evaluation --dataset xplanet-agent/eval/golden_dataset.jsonl
```

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
├── xplanet-gateway/         # 统一外部入口、路由、CORS、TraceId、前置鉴权
├── xplanet-article/         # 文章服务 ★ 核心
├── xplanet-interaction/     # 点赞服务 ★ 核心
├── xplanet-user/            # 用户服务
├── xplanet-ai/              # AI 任务控制面、SSE、审核和发布编排
├── xplanet-agent/           # Python LangGraph Agent 执行面
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
├── scripts/                 # 构建、迁移、启动、端到端与故障恢复测试
├── .github/workflows/ci.yml # 单测、Compose 与脚本语法检查
├── benchmark/               # wrk 压测脚本
└── docs/
    ├── ARCHITECTURE.md
    ├── BEGINNER-GUIDE.md
    ├── EXPERIMENTS.md
    └── benchmark-results.md
```

## 已知取舍(面试可主动展开)

- 中间件单机(Redis/MySQL/RocketMQ),未做集群/哨兵/主从——这是**高可用**演进项,
  与「应用可水平扩展」是两回事(应用层已按无状态多实例设计)。见 `docs/HA-AND-DEGRADE.md`
- Token 使用标准 JWT/JWS 库签发和校验，签名密钥通过 `TOKEN_SECRET` 外部注入
- 登录使用 Spring PasswordEncoder 校验 bcrypt 哈希；演示账号共用初始密码，仅用于本地数据
- 点赞以 `like_relation` 为事实源,通过 Outbox 至少一次投递；消费端唯一事件表和事务批量投影吸收重复并支持崩溃恢复
- AI 已完成离线确定性闭环、持久化 checkpoint、崩溃恢复、有限重试、基础评测与指标；联网 Provider 尚未用真实密钥验收，语义引用核验、RAG 和完整可观测平台仍是后续项

这些是有意识的取舍,不是不知道,面试时可展开聊改造方案。

## 设计立场:可水平扩展 ≠ 高可用

- **应用层**按「可水平扩展」设计:无状态(token 无状态、缓冲在 Redis)、L1 本地缓存 + MQ 广播
  保证多实例部署时本地缓存一致——这些是为水平扩展服务的,不是过度设计
- **中间件高可用**(集群/哨兵)按需演进,当前单机够用
- 二者是不同维度,本项目明确选择「应用可扩展 + 中间件暂单机」,演示跑单实例

## License

[MIT](LICENSE)
