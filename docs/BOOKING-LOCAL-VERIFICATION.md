# 当前本机验证报告

日期：2026-10-19。环境为 Windows 11、Temurin Java17.0.19、Maven3.9.8、Docker Desktop，MySQL8.4容器运行于独立的临时测试环境。

## 最新结果

| 验证项 | 结果 |
| --- | --- |
| H2集成测试 | 47项，0失败、0错误 |
| MySQL8.4集成测试 | 相同47项，0失败、0错误；V1/V2/V3迁移通过 |
| 已有H2文件升级 | 原V1数据库启动后自动应用V2/V3，无需清空数据 |
| 真实双JVM共享MySQL | 32用户：1确认、31候补，32次幂等重放、FIFO递补与归属403通过 |
| 热点设备负载 | 两实例、8个客户端、1台设备，30秒创建/重放/取消循环；无错误、无残留时隙 |
| 分散设备负载 | 两实例、8个客户端、8台设备，同样30秒循环；无错误、无残留时隙 |
| 数据库核验 | 终态残留时隙0，错误时隙归属0，重复通知0，待投递事件最终0 |
| Playwright+Edge | 登录、改期、取消、维修安排、停机日历、取消维修、运行概览与390×844布局通过，JS错误0 |
| 强制终止与恢复 | 最新版本实际等待一分钟审批期限，恢复会话、审批过期、候补OFFERED、通知及幂等重放通过 |

原始负载数据：[热点设备](validation/load-hot-20261019.json)、[分散设备](validation/load-distributed-20261019.json)。两组测试还包含取消和部分重放，因此其中的提交请求速率不能当作接口极限QPS，也不是长时间稳定性结果。场景和环境不同，不与最初云端延迟数字直接比较。

结构化本轮结果见 [current-20261019.json](validation/current-20261019.json)。最初包内云端35项H2/MariaDB及恢复记录仍见 [历史云端验证](BOOKING-VALIDATION.md)；[首次导入的35项本机记录](validation/local-windows-20261019.json)仅为历史基线，不能当作当前测试总数。本轮没有重跑MariaDB。

## 可复现命令

```powershell
mvn clean verify
.\START-WINDOWS.bat
```

外部数据库测试只连接独立测试库，现有测试会删除业务表：

```powershell
$env:TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/booking_test?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:TEST_DB_USER='booking'
$env:TEST_DB_PASSWORD='<测试库密码>'
mvn -f xplanet-booking/pom.xml test
```

共享数据库的两个实例健康后：

```powershell
$env:ADMIN_PASSWORD='<管理员密码>'
python scripts/booking_e2e.py --base-url http://127.0.0.1:18082 --second-url http://127.0.0.1:18083 --users 32
python scripts/booking_load.py --base-url http://127.0.0.1:18082 --second-url http://127.0.0.1:18083 --seconds 30 --workers 8 --resources 1
python scripts/booking_load.py --base-url http://127.0.0.1:18082 --second-url http://127.0.0.1:18083 --seconds 30 --workers 8 --resources 8
python scripts/booking_recovery.py
```

负载脚本创建独立前缀的用户和设备，结束时取消预约并停用测试设备；请只在隔离验证库运行。

浏览器使用demo环境，默认账号只用于本地体验：

```powershell
npx --yes --package @playwright/cli playwright-cli -s=booking open http://127.0.0.1:8080 --browser msedge
npx --yes --package @playwright/cli playwright-cli -s=booking snapshot
npx --yes --package @playwright/cli playwright-cli -s=booking run-code --filename scripts/booking_browser_check.js
npx --yes --package @playwright/cli playwright-cli -s=booking close
```

## 代码与本地目录

main只包含预约平台。本地旧环境、临时浏览器记录与无关PDF已清理，本地和远端秒杀分支已删除。optimization分支保留。Git不提交数据文件、JAR、密码和浏览器运行缓存。

源码最初来自交付包，哈希来源见 [import-manifest.json](validation/import-manifest.json)。之后的代码修复、功能与迁移均已进入Git提交；不再声称当前源码与原始包逐字相同。
