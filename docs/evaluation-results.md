# Agent 质量评测结果

> 执行日期：2026-07-21。机器可读原始结果见 [`evaluation-results.json`](evaluation-results.json)。

## 结果

| 指标 | 结果 | 含义 |
|---|---:|---|
| 数据集 | 30 题 | 覆盖 Agent、证据、检索、消息、缓存和服务治理 |
| 任务完成率 | 100% | 全部离线工作流在预算内生成报告 |
| Citation 索引有效率 | 100% | 所有引用均指向已保存 Evidence |
| 词面 Claim Support | 100% | Claim 至少有一个支持分不低于 0.55 的引用 |
| Internal Recall@5 | 100%（5/5） | 标注文章均出现在站内 Top5 |
| 平均延迟 | 17.851 ms | 本机单进程 `offline-demo` 最终快照 |
| P95 延迟 | 21.539 ms | 本机单进程 `offline-demo` 最终快照 |
| Input / Output Token | 0 / 0 | 离线 Provider 不调用外部模型 |
| 估算成本 | 0 | 离线 Provider 不产生 API 费用 |

复现命令：

```powershell
.\.venv\Scripts\python.exe -m xplanet_agent.evaluation `
  --dataset xplanet-agent\eval\golden_dataset.jsonl `
  --output docs\evaluation-results.json
.\scripts\test-internal-recall.ps1
```

## 正确解读

这些数字证明结构化编排、预算、Evidence 身份和引用关系可重复回归。它们不证明回答事实正确，也不代表真实 OpenAI/Web Search 的延迟和成本。离线 Writer 从确定性语料构造 Claim，所以 100% 词面支持率主要用于防止引用丢失、错误 ID 和编排回归。真实联网质量需要在明确提供 API Key 和成本授权后单独执行人工/模型语义抽检。
