# 当前可复现实验

> 只保留最终架构的验收记录。所有数字都是 2026-07-21 本机离线快照，不代表生产 SLA 或真实联网事实正确率。

## 1. Agent 离线质量评测

```powershell
.\.venv\Scripts\python.exe -m xplanet_agent.evaluation `
  --dataset xplanet-agent\eval\golden_dataset.jsonl `
  --output docs\evaluation-results.json
```

| 指标 | 结果 |
|---|---:|
| Dataset Size | 30 |
| Task Success | 100% |
| Citation Index Validity | 100% |
| Deterministic Lexical Claim Support | 100% |
| Average Latency | 17.851 ms |
| P95 Latency | 21.539 ms |
| External Input/Output Token | 0 / 0 |
| External Cost | 0 |

离线 Provider 使用固定语料，不调用外部模型。该实验验证工作流、预算、Evidence 身份和 Citation 结构回归，不验证实时网页质量或语义事实正确率。完整逐题结果见 [`evaluation-results.json`](evaluation-results.json)。

## 2. 站内知识召回

```powershell
.\scripts\test-internal-recall.ps1
```

条件：MySQL V009 ngram FULLTEXT、5 条带期望文章 ID 的标注查询、TopK=5。结果为 Recall@5 100%（5/5），门槛为 80%。

该结果只证明当前中文/技术词法查询能够召回种子文章，不等于向量语义检索。新文章无需复制到第二个事实库，发布后即可进入索引。

## 3. checkpoint 强退恢复

```powershell
$env:TOKEN_SECRET="<与容器一致>"
$env:AGENT_INTERNAL_TOKEN="<与容器一致>"
.\scripts\test-agent-recovery.ps1
```

在首个 `EXECUTE_TOOL` schema v4 checkpoint 持久化后执行真实进程退出。RocketMQ 重投后第二次 attempt 从下一节点继续，最终进入 `WAITING_REVIEW`；最近一次复测包含 15 个 checkpoint、3 个工具步骤，没有额外重复。

最终代码只恢复 schema v4。数据库检查时没有 `QUEUED/RUNNING` 的 v1～v3 任务，因此已删除开发阶段兼容分支。

## 4. RocketMQ 暂停恢复

```powershell
.\scripts\test-ai-mq-pause.ps1
```

Broker 停止时，AI Outbox 保持待发送并记录失败次数；Broker 恢复后 Relay 自动补发，Outbox 变为已发送，任务进入 `WAITING_REVIEW`。脚本使用 `finally` 保证 Broker 被恢复。

## 5. Docker 全链路 smoke

```powershell
.\scripts\smoke-test.ps1
```

最终记录：

- Gateway health、CORS、TraceId 和未登录拦截通过；
- 相同 Idempotency-Key 创建任务返回同一 Task；
- Source/Evidence/Citation、片段哈希、Critic 和模型用量契约通过；
- 同一报告审核并重复发布返回同一 Article，证明发布幂等；
- 后续任务只用一次 `internal_search` 召回刚发布的 Article；
- 不存在文章点赞被拒绝；重复点赞、MQ 消费和持久化计数投影幂等；
- 文章变更的立即/延迟缓存失效 Outbox 均发送；
- 测试结束后原点赞关系与文章计数恢复。

## 6. 浏览器 E2E

使用 Playwright 驱动 Edge 访问 `http://127.0.0.1:4173`：

- Alice 登录并进入私有研究空间；
- 任务列表、节点时间线、质量分、来源、Evidence、Citation 和 Critic 正常显示；
- 站内文章显示为 `published internal article` Evidence；
- 人工审核发布入口可见；
- 浏览器控制台 0 error / 0 warning。

## 7. 未验收项

- 没有真实 OpenAI API Key 和成本授权，因此 `openai-tools` 只完成 MockTransport 契约测试；
- 没有当前 Outbox/MQ/投影全链路的多用户容量压测，因此不宣称 QPS、削峰倍数或生产 SLA；
- MySQL、Redis、RocketMQ 为单机演示环境，不代表中间件高可用；
- Claim Support 是确定性词面门禁，不替代人工或模型语义 Judge。
