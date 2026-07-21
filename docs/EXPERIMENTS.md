# 可复现实验记录

本文只记录实际执行过的验证及其边界。所有耗时均为本地快照，不代表生产容量或线上 SLA。

## 2026-07-20：动态 Agent 与 Claim Support 离线评测

### 条件

- Provider：`offline-demo`，不调用外部模型或搜索；
- 数据集：`xplanet-agent/eval/golden_dataset.jsonl`，10 条固定技术问题；
- 环境：Windows、Python 3.12、单进程本地执行；
- 命令：

```powershell
.\.venv\Scripts\python.exe -m xplanet_agent.evaluation --dataset xplanet-agent/eval/golden_dataset.jsonl
```

### 结果

| 指标 | 本次结果 | 能说明什么 |
|---|---:|---|
| Dataset Size | 10 | 本轮固定案例数量 |
| Task Success Rate | 100% | 工作流完成，来源/证据数量未越界 |
| Citation Index Validity Rate | 100% | citation 引用的 `evidenceRef` 均存在 |
| Claim Support Rate | 100% | 每个 Claim 至少有一个 citation 的确定性词面支持分不低于 0.55 |
| Average Latency | 16.511 ms | 当前机器上含动态循环、Writer 和 Critic 的离线图执行快照 |
| P95 Latency | 19.951 ms | 当前机器上含动态循环、Writer 和 Critic 的离线图执行快照 |

限制：数据集规模小，离线 Writer 直接使用固定证据内容形成 Claim，因此 100% 只用于防止编排、预算、错误引用和明显词面不支持回归，不是语义 Judge 分数或事实正确率。真实网页质量、模型成本、网络延迟和 Prompt 注入未被这组数字覆盖。

Python 共 25 项测试，新增覆盖 Evidence 片段 SHA-256、Claim 缺证据、未知 Evidence 引用、结构化冲突披露，以及 Critic 最多一次定向补研究后强制收敛。Java 结果落库测试同时验证片段哈希契约、引用写入失败不会推进任务状态，`complete` 方法的事务边界保证真实数据库写入整体回滚。

## 2026-07-20：工具 checkpoint 崩溃恢复

### 条件

- 全 Docker 环境：MySQL、Redis、RocketMQ、Gateway、4 个业务 Java 服务和 Python Agent；
- 故障点：首个 `EXECUTE_TOOL` 结果 checkpoint 已经成功写入 MySQL 后，Agent 进程执行 `os._exit(17)`；
- 故障开关：检测到首次退出后立即关闭，只注入一次故障，避免人为耗尽 3 次业务重试预算；
- 恢复机制：容器自动重启，RocketMQ 未确认消息重投，工作流读取 checkpoint 后从 `EVIDENCE_BUILDER` 继续；
- 命令：

```powershell
.\scripts\test-agent-recovery.ps1
```

### 结果

| 证据 | 本次结果 |
|---|---:|
| Task ID | 47 |
| Completed Checkpoint Steps | 15 |
| Completed Tool Steps | 3（等于任务预算，无额外重复） |
| Run Attempts | 2 |
| Task Version | 4 |
| Final State | `WAITING_REVIEW` |

结论只覆盖一次确定性故障注入：已完成的 Search 工具结果没有在恢复时重跑，后续 2 个工具和报告节点完成并进入人工审核。它不是高并发恢复率或长期稳定性测试。

## 2026-07-20：Gateway 全链路验收

### 条件

- Docker 模式只向宿主机发布 Gateway 8080，业务服务 8081～8084 和 Agent 8000 只在容器网络可达；
- 所有登录、文章、点赞和 AI 请求均通过 Gateway；
- 使用 `offline-demo` Agent，不调用外部模型；
- 命令：`./scripts/smoke-test.ps1`。

### 结果

| 验证项 | 本次结果 |
|---|---|
| Gateway 健康和 4 条路由 | 通过 |
| CORS OPTIONS 预检 | HTTP 200，允许配置的 Origin |
| 无 Token 写请求 | HTTP 401 / 业务码 2001 |
| 请求与响应 `X-Trace-Id` | 通过 |
| 文章、缓存 Outbox、点赞幂等和持久化计数 | 通过 |
| AI 动态循环 | 5 次工具、6 次决策、21 个 schema v3 checkpoint、26 条 SSE 进度 |
| Claim/Evidence/Critic、片段哈希、审核和幂等发布 | 通过，Task ID 48，发布 Article ID 120 |

此结果证明本机这套 Compose 中组件可以协同工作，不代表生产高可用、安全攻防或容量结论。Gateway 当前完成入口级 TraceId；服务日志 MDC、Feign 和 MQ 的完整 Trace 上下文仍是后续项。

## 可选 OpenAI Tools Provider

`openai-tools`（兼容旧环境值 `openai-web`）已通过 `httpx.MockTransport` 覆盖结构化 Planner、动态 Decision、Writer、Critic、单次 Hosted Web Search、内部鉴权头、来源/工具/Token 边界以及用量返回。`HttpDocumentFetcher` 单测覆盖私网 DNS、私网重定向、危险端口、二进制内容和超大响应。当前环境没有真实 API Key，也没有产生外部模型费用，因此不记录真实联网质量、成本或延迟数字。

启用前应单独建立经批准的在线数据集，记录 Provider/模型、Prompt 版本、完整费用、限流与错误分布，并增加 Claim—Evidence 语义支持验证。

## 2026-07-21：站内知识回流验收

### 条件

- MySQL 从 Flyway V008 迁移到 V009，`article(title, content)` 使用 ngram FULLTEXT；
- 固定数据集 `xplanet-agent/eval/internal_recall.jsonl` 共 5 条，每条标注期望文章 ID；
- Agent 通过内部 Token 调用 Article，而不是直接连接 Article 数据库；
- 命令：`./scripts/test-internal-recall.ps1` 与 `./scripts/smoke-test.ps1`。

### 结果

| 指标 | 结果 |
|---|---:|
| Internal Recall@5 | 100%（5/5，门槛 80%） |
| checkpoint schema | v4 |
| 发布后再召回 | Task 50 发布 Article 121；Task 51 召回成功 |
| 工具预算 | 第二个任务 `maxToolCalls=1`，仅使用一次 `internal_search` |

该结果证明已发布文章能够在同一系统内回流成后续研究证据。数据集很小且 FULLTEXT 以词法匹配为主，因此不能宣称具备向量语义召回能力；将来可以替换 `InternalSearchProvider` 的实现而不改变 Agent Action 契约。
