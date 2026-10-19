# 本地同步与复验记录

日期：2026-10-19。此文件只记录本轮 Windows 本机操作；交付包原有的云端 H2/MariaDB、双实例及浏览器报告仍在 [BOOKING-VALIDATION.md](BOOKING-VALIDATION.md)。

## 同步范围

- 从桌面 `XPlanet-Booking-Final.zip` 导入独立 `xplanet-booking` 模块、预约相关文档、验证脚本和 Docker 配置。
- 模块导入后逐文件 SHA-256 比对无差异。归档中的预编译 JAR 哈希与 RELEASE.json 一致；Git 仅提交源码，运行 JAR 在本地重新构建。
- `main` 只保留预约项目；移除该分支的旧社区代码与旧文档。秒杀和 optimization 分支不变，旧社区仍可从 Git 历史查看。
- 新增根 Maven 聚合入口、Windows/Linux 启动入口、环境变量样例及项目评估文档，更新项目开发地图。
- 本轮未批量改变预约业务规则；检查发现的缺口与后续方案见 [项目评估](BOOKING-PROJECT-REVIEW.md)。
- 来源与哈希记录在 [import-manifest.json](validation/import-manifest.json)。

## 本机执行结果

环境：Windows 11 amd64，Temurin Java 17.0.19，Maven 3.9.8；模块 Maven Wrapper 可正常下载并运行 Maven 3.9.9。

| 项目 | 本轮结果 |
| --- | --- |
| 根目录 `mvn -B clean verify` | 35 项 H2 集成测试，0 失败、0 错误、0 跳过；JAR 打包成功 |
| 根目录 `START-WINDOWS.bat` | 正常启动，同源工作台和 `/actuator/health` 可访问 |
| `booking_e2e.py --users 32` | 单实例 32 用户：1 确认、31 候补、32 次重放复用原预约；FIFO 补位与越权 403 通过 |
| `booking_recovery.py` | 使用独立临时 H2 文件，强制终止进程并实际等待 1 分钟审批期限；重启后审批过期、候补 OFFERED、通知、会话和幂等重放通过 |
| Playwright + Edge | 真实浏览器登录、选择时段、提交预约、查看确认记录、审计、取消成功；390×844 视口检查没有横向溢出。favicon 500 单独记录为待修问题 |
| Compose 配置 | 使用占位环境变量执行 `config --quiet` 通过；未启动容器 |

结构化本机记录：[local-windows-20261019.json](validation/local-windows-20261019.json)。

HTTP 批次耗时 0.237 秒，P50 71.26 ms，P95 131.79 ms。这是本机单实例短时正确性样本，不能与包内云端双实例样本直接对比性能，也不是持续吞吐指标。

## 未在本机重跑的范围

Docker Desktop 引擎未运行，本轮没有重跑 MySQL/MariaDB、共享外部数据库的双 JVM 并发验证；不能把历史云端报告当成本机结果。长期稳定性、容量压测、设备硬件联动和真实用户试用也未执行。

## 已复现的待修问题

缺少 from/to 调用时段查询接口得到 500，预期应为参数错误；缺失的 `/favicon.ico` 也得到 500。原模块 `ApiErrors` 的通用异常映射需要完善。本轮保留交付模块源码并记录问题，没有将它们算作“所有功能完全无误”。优化建议见项目评估文档。

## 使用方式

双击仓库根目录 `START-WINDOWS.bat`，首次构建需要联网。访问 `http://localhost:8080`。
演示用户为 `alice / password123`，管理员为 `admin / admin12345`。
数据保存于启动目录 `data/`，已排除在 Git 外。修改源码后执行 `mvn clean verify` 更新 JAR，再重启应用。

独立测试库中的集成测试会清空业务表；不要给 TEST_DB_URL 配置实际使用的数据库。
