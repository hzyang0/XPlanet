# DeepSeek 在线模式学习与试用

## 先理解边界

在线模式的任务 Provider 是 `deepseek-tools`。浏览器只选择执行模式和预算；DeepSeek API Key 只由 `xplanet-agent` 容器从本地 `.env` 读取，绝不能填写到网页、提交到 Git 或复制进学习笔记。

离线模式适合快速演示和单元测试：资料、规划和结果可复现。在线模式适合演示真实模型的规划、工具选择、写作和 Critic 审核，但会产生 API 成本，也必须经过人工审核才可发布。

## 配置与启动

在仓库根目录的 Git 忽略文件 `.env` 中配置：

```dotenv
DEEPSEEK_API_KEY=你的密钥
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_BASE_URL=https://api.deepseek.com
```

随后只重建 Agent：

```powershell
docker compose --env-file .env -f docker/docker-compose-infra.yml -f docker/docker-compose-app.yml up -d --build agent
docker exec xp-agent python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/health').read().decode())"
```

健康响应中 `providers.deepseek-tools: true` 表示在线模式可用。打开 `http://127.0.0.1:4173`、登录 `alice/password`，在研究工作台选择“在线模式 · DeepSeek + 工具”。

## 第一次任务建议

使用中文问题，例如：

> Transactional Outbox 如何提升消息可靠性？它的适用边界是什么？

第一次建议使用较小预算：来源 2～3、工具调用 3～5、Token 6000、截止时间 180 秒。任务进入 `WAITING_REVIEW` 后，依次看报告正文、来源文档、Evidence 片段和 Citation 绑定，确认后再发布。

## 语言与容错规则

- 中文问题：报告标题、正文、结论和 Critic 字段使用简体中文；技术专名、代码标识、Evidence ID、来源标题与 URL 可以保留原文。
- 英文问题：报告使用英文；中文只允许出现在不可避免的原始来源标题或 URL 中。
- DeepSeek 偶发返回非 JSON 时，Agent 只会再发起一次格式修复请求，重试 Token 会合并计入任务总预算。
- 外部网页拒绝抓取、超时、内容类型不允许或安全检查拒绝时，Agent 将该 URL 标记为已尝试并继续选择其他来源，不会因一个网页失败终止任务。

## 面试时怎么解释

“在线模式并非把 API Key 放进前端。Java 控制面把 Provider、预算和任务状态固化后通过 Outbox 与 RocketMQ 下发命令；Python Agent 在服务端调用 DeepSeek、执行有界工具循环、将 Claim 与 Evidence 绑定，最后由 Java 事务校验并进入人工审核。模型格式异常和单网页抓取失败都做了有界降级，避免一次外部波动让异步任务失控。”

## 不能夸大的结论

一次在线验证只说明链路可用，不能说明模型事实永远正确，也不能说明系统已具备生产级 SLA、成本基线或多用户吞吐能力。最终报告仍需人工审核。
