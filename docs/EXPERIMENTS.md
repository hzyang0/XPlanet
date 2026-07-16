# 可复现实验记录

本文只记录实际执行过的验证及其边界。所有耗时均为本地快照，不代表生产容量或线上 SLA。

## 2026-07-16：离线 Agent 结构评测

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
| Claim Support Rate | 未测量 | 不能据此声称证据在语义或事实上支持结论 |
| Average Latency | 8.520 ms | 当前机器上的离线图执行快照 |
| P95 Latency | 10.564 ms | 当前机器上的离线图执行快照 |

限制：数据集规模小且资料固定，只用于防止编排、预算和引用 ID 闭包回归。真实网页质量、事实正确率、模型成本、网络延迟和 Prompt 注入未被这组数字覆盖。

## 2026-07-16：Agent checkpoint 崩溃恢复

### 条件

- 全 Docker 环境：MySQL、Redis、RocketMQ、4 个 Java 服务和 Python Agent；
- 故障点：`PARALLEL_RESEARCH` checkpoint 已经成功写入 MySQL 后，Agent 进程执行 `os._exit(17)`；
- 恢复机制：容器自动重启，RocketMQ 未确认消息重投，工作流读取 checkpoint 后从 `EVIDENCE_BUILDER` 继续；
- 命令：

```powershell
.\scripts\test-agent-recovery.ps1
```

### 结果

| 证据 | 本次结果 |
|---|---:|
| Task ID | 17 |
| Completed Checkpoint Steps | 7 |
| Run Attempts | 2 |
| Task Version | 4 |
| Final State | `WAITING_REVIEW` |

结论只覆盖一次确定性故障注入：已经完成的 Validate、Planner 和 Research 节点没有重新执行，后续节点完成并进入人工审核。它不是高并发恢复率或长期稳定性测试。

## 可选 OpenAI Web Search Provider

`openai-web` 已通过 `httpx.MockTransport` 覆盖请求体、内部鉴权头、Web Search 工具、URL citation 解析、来源/工具/Token 边界以及用量返回。当前没有在仓库验收中使用真实 API Key，也没有产生外部模型费用，因此不记录真实质量、成本或延迟数字。

启用前应单独建立经批准的在线数据集，记录 Provider/模型、Prompt 版本、完整费用、限流与错误分布，并增加 Claim—Evidence 语义支持验证。
