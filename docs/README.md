# XPlanet 文档导航

本目录只维护与当前可运行系统一致的说明。功能、架构或验证方式发生变化时，应在同一次代码变更中同步更新对应文档，避免保留互相冲突的版本副本。

## 推荐阅读顺序

1. [`../README.md`](../README.md)：项目定位、模块、启动方式和主要能力；
2. [`BEGINNER-GUIDE.md`](BEGINNER-GUIDE.md)：从零启动项目，沿文章、点赞和研究任务三条主链路阅读重点代码，并通过分时学习路线与高频问答完成自测；
3. [`ARCHITECTURE.md`](ARCHITECTURE.md)：模块边界、数据流、缓存、可靠消息、Agent 和发布闭环；
4. [`CURRENT-SCOPE.md`](CURRENT-SCOPE.md)：当前系统范围、保留组件、明确不引入的组件和变更原则；
5. [`TECHNICAL-GUIDE.md`](TECHNICAL-GUIDE.md)：核心技术原理、设计取舍、故障场景和连续设计问题；
6. [`VERIFICATION-GUIDE.md`](VERIFICATION-GUIDE.md)：启动后快速巡检完整功能闭环。

## 验证与演进

| 文档 | 内容 |
|---|---|
| [`EXPERIMENTS.md`](EXPERIMENTS.md) | 当前可复现的单测、离线评测、恢复实验、Smoke 和浏览器 E2E |
| [`evaluation-results.md`](evaluation-results.md) | 人类可读的 Agent 评测摘要 |
| [`evaluation-results.json`](evaluation-results.json) | 机器可读的评测结果 |
| [`HA-AND-DEGRADE.md`](HA-AND-DEGRADE.md) | 当前高可用边界、已实现降级和按需扩展路径 |

## 维护规则

- `CURRENT-SCOPE.md` 定义当前范围，`ARCHITECTURE.md` 描述当前实现，两者不得写入尚未实现的能力；
- 实验结果必须能由仓库脚本或测试复现，并明确离线评测、联网质量和容量指标之间的边界；
- 不在仓库中维护内容重复的 Word/PDF 派生副本，避免与 Markdown 源文档产生版本漂移；
- 不保留旧实现说明或阶段草案；需要追溯时使用 Git 历史。
