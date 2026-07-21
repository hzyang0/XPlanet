# 3～5 分钟面试演示

## 演示前

```powershell
$env:TOKEN_SECRET="<至少32字节且与环境一致的密钥>"
.\scripts\start-docker.ps1
.\scripts\smoke-test.ps1
python -m http.server 4173 --directory xplanet-web
```

打开 `http://127.0.0.1:4173`，使用 `alice / password`。只展示 Gateway 8080，不直接访问内部服务。

## 演示节奏

1. **0:00～0:40 定位**：一句话说明“可追溯研究 Agent + 知识社区”，Java 负责可靠任务和业务副作用，Python/LangGraph 负责动态决策与证据质量。
2. **0:40～1:30 创建研究**：输入“Transactional Outbox 如何保证 Agent 长任务可靠？”；展开预算，说明工具次数、来源数、Token 和截止时间都是硬上限。
3. **1:30～2:20 看时间线**：指出 Planner、`DECIDE_ACTION`、`internal_search/web_search/web_fetch`、Evidence Builder、Writer 和 Critic；强调不是固定三步，下一工具由结构化 Action 决定。
4. **2:20～3:10 看报告**：展开来源与 Evidence，展示 `sourceRef/evidenceRef`、片段哈希、Claim 引用和 Critic 结果；说明“引用存在不等于事实正确”，当前离线评测只验证结构和词面支持。
5. **3:10～4:00 人工发布**：点击审核发布，再次点击仍返回同一 Article ID；说明 Human-in-the-loop 控制唯一写副作用，`ai_published_article` 保证幂等。
6. **4:00～4:40 知识回流**：创建相似问题，指出新文章被 `internal_search` 召回；当前使用 MySQL FULLTEXT，未来只替换 Provider 即可升级向量检索。
7. **4:40～5:00 可靠性证据**：展示 [`evaluation-results.md`](evaluation-results.md)，补充 Outbox/MQ 暂停和 checkpoint 强退测试均已自动化。

## 演示失败时

- 页面无任务：检查 Gateway `/actuator/health`，再看 `docker ps`；
- 任务一直 QUEUED：检查 `xp-rmq-broker` 与 `xp-ai` 日志；
- 没有实时进度：刷新任务详情，最终事实仍以 MySQL 状态为准；
- 不在现场执行真实 OpenAI：没有 Key 或成本授权时明确使用 `offline-demo`，不要冒险临时联网。

