# 云端实际验证记录

> 以下为交付包附带的历史记录，按原文保留；本轮 Windows 同步与复验见 [BOOKING-LOCAL-VERIFICATION.md](BOOKING-LOCAL-VERIFICATION.md)。

验证日期：2026-10-15。环境为隔离 Linux x86_64，OpenJDK 17.0.20、Maven 3.9.9、Spring Boot 3.5.7、H2 2.3、MariaDB 10.11.14。所有下面标记通过的项目均实际执行，记录保存在本目录 validation/ 中。

## 执行结果

| 验证 | 结果 | 原始记录 |
| --- | --- | --- |
| H2 数据库集成测试 | 35 项，0 失败、0 错误，15.068 秒 | [h2-tests.json](validation/h2-tests.json) |
| MariaDB 集成测试 | 35 项，0 失败、0 错误，15.82 秒 | [mariadb-tests.json](validation/mariadb-tests.json) |
| 两个真实 JVM 应用实例 | 32 用户同时间段，1 确认 / 31 候补 | [HTTP 记录](validation/http-two-instances.json) |
| 重复提交 | 32 次重放返回原预约 | 同上 |
| 候补与越权 | 按顺序补位、确认成功，非本人取消返回 403 | 同上 |
| 桌面与移动浏览器 | 登录、预约、取消、审计、创建设备、培训、审批通过 | [browser.json](validation/browser.json) |
| MariaDB 强制终止与恢复 | 真实等待 1 分钟审批期限，恢复后过期、补位、通知与 Token 正常 | [restart-recovery.json](validation/restart-recovery.json) |
| H2 文件模式强制终止与恢复 | 使用仓库恢复脚本实际执行，通过 | [h2-file-recovery.json](validation/h2-file-recovery.json) |
| Maven Wrapper | 下载并运行 Maven 3.9.9，通过 | wrapper 配置在模块内 |

双实例测试使用同一 MariaDB、两份应用进程、16 个 HTTP 提交线程。该轮提交批次耗时 0.514 秒，P50 201.25 ms，P95 365.34 ms。它是验证正确性的短时并发样本，**不是持续吞吐压测，也不能外推生产 QPS**。集成测试另含 50 个独立用户、20 个线程同时预约的用例。

恢复测试使用 1 分钟审批期限和较长扫描间隔，保存待审批、候补及未投递事件后强制终止进程，真实等待截止时间，再重新启动。不是手工修改数据库时间来伪造过期。MariaDB 最后核对：孤立时段 0、重复时段 0、未投递事件 0。

## 集成测试覆盖

- 相邻区间可预约，部分重叠进入候补，时段主键最终排他。
- 并发重放幂等、参数变更拒绝、同用户重叠预约拒绝。
- 取消、限时确认、审批拒绝、审批过期、FIFO 及重叠候补公平性。
- 培训门槛、资格撤销、设备停用、管理员版本冲突。
- 签到窗口、重复签到、主动结束、到期完成和未签到回收。
- 审批与取消并发，重复取消不能释放下一候补的时段。
- 业务 / 时段 / 审计 / 事件回滚；Inbox / 投递确认回滚与恢复。
- 多维护线程重复调度、会话哈希保存、Token 过期和撤销。
- HTTP 参数校验、注册不能提权、归属校验、分页限制、时段隐私和 UTC 时间序列化。

## 可复现命令

```bash
cd xplanet-booking
./mvnw clean verify
```

MariaDB/MySQL 设置 TEST_DB_URL、TEST_DB_USER、TEST_DB_PASSWORD 后运行相同命令；**仅使用独立测试库，测试会删除业务数据**。

真实退出恢复：从仓库根目录设置 ADMIN_PASSWORD 后执行下列命令，约需 1 分钟。默认使用临时 H2 文件；设置 DB_URL 等变量可测试独占的外部数据库。

```bash
python scripts/booking_recovery.py
```

双实例 HTTP 验证见 scripts/booking_e2e.py。可选浏览器验证需要 demo 数据、Python Playwright 和 Chromium：

```bash
python -m pip install -r scripts/requirements-booking-ui.txt
python -m playwright install chromium
# 设置 ADMIN_PASSWORD，BOOKING_URL 默认为 http://localhost:8080
python scripts/booking_ui.py
```

浏览器脚本会新增设备、用户操作记录和资格，请在独立验证环境执行。

## 本轮修复与限制

真实数据库测试发现并修复了建表默认字符集导致中文写入失败、JDBC Timestamp 在非 UTC JVM 下的时间偏移，以及参数校验错误被误报为 500。浏览器检查也覆盖了账号切换后的界面重置与用户输入按文本渲染。

MySQL 8.4、Docker Compose 和 Windows 批处理已提供配置，但本云端没有 Docker / Windows，未声称已实际执行这些环境。GitHub Actions 配置提供 MySQL 8.4 / MariaDB 10.11 的后续验证。尚未进行长时间稳定性、生产流量、安全审计或真实设备集成验证。
