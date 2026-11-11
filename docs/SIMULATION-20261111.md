# 模拟用户与故障恢复验证（2026-11-11）

本轮未发现需要改动 XPlanet 业务代码的问题。以下结果属于当前版本的本地模拟验证，不代表真实实验室已完成试用。

| 场景 | 本轮结果 |
| --- | --- |
| H2 集成测试 | 50 项通过 |
| MySQL 8.4 独立测试库 | 同套 50 项通过、0 跳过 |
| 单实例 32 位模拟用户竞争 | 1 已确认、31 候补，32 次幂等重放、越权检查、FIFO 补位通过 |
| 双实例共享 MySQL，32 人竞争 | 同样只有 1 已确认、31 候补，幂等与补位通过 |
| 双实例、2 设备、8 worker、5 秒有界负载 | 217 次创建／取消循环，24 次重放、0 错误，槽位释放 |
| 实际杀进程、等待持久化审批截止后重启 | 会话、幂等、超时审批、候补补位、通知恢复通过 |
| 小程序公共逻辑 | 7 项通过 |
| 模拟 wx.request 调真实 HTTP | 注册、权限、重试、候补、补位确认、改期和取消通过 |
| Chromium 手机视口 390×844 | 登录、改期、取消、维护日历、运营信息通过；无 JS 异常及横向溢出 |

报告：`docs/validation/users-single-20261111.json`、`users-dual-20261111.json`、`bounded-load-20261111.json`、`recovery-20261111.json`。

复现命令（仓库根，先启动对应模拟后端，端口可修改）：

```sh
ADMIN_PASSWORD=admin12345 python scripts/booking_e2e.py --users 32
ADMIN_PASSWORD=admin12345 python scripts/booking_e2e.py --base-url http://127.0.0.1:8082 --second-url http://127.0.0.1:8083 --users 32
ADMIN_PASSWORD=admin12345 python scripts/booking_load.py --base-url http://127.0.0.1:8082 --second-url http://127.0.0.1:8083 --seconds 5 --workers 8 --resources 2
ADMIN_PASSWORD=simulation-pass python scripts/booking_recovery.py
node --test miniprogram/tests/client.test.cjs
RUN_MINI_LIVE=true node miniprogram/tests/live-flow.cjs
```

双实例必须连接同一模拟 MySQL 库。恢复脚本自己建立临时 H2 文件库并启动／终止进程。本轮数据库测试使用单独测试库，不能把清表测试指向实际业务库。模拟账号和审计数据可能保留，适合隔离演示环境。

5 秒负载用于检查竞争和槽位释放，不是长时间稳定性、最大吞吐或真实网络测试。自动测试覆盖审批、训练资格、停用、维护、时间边界和调度状态；微信开发者工具／真机、物理设备占用和真实用户反馈仍未验证。
