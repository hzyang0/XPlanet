# XPlanet · 实验设备预约与资源调度平台

围绕实验室单件设备，实现培训资格、时段预约、审批、候补限时确认、签到使用、超时回收和站内通知。包含可操作的用户 / 管理员工作台与数据库集成测试。

主模块是 **xplanet-booking**，采用 Java 17、Spring Boot 3.5、Spring JDBC、Spring Security 和 Flyway。当前 `main` 仅维护预约平台；旧秒杀系统保留在 `xplanet-seckill` 分支，研究平台保留在 `xplanet-optimization` 分支。预约服务独立运行。

## 快速体验

需要 JDK 17+。Maven Wrapper 首次运行会下载 Maven 和依赖，需要网络。

```bash
cd xplanet-booking
./mvnw clean verify
java -jar target/xplanet-booking-2.0.0.jar --spring.profiles.active=demo
```

Windows PowerShell 将 ./mvnw 替换为 .\mvnw.cmd；也可双击根目录 `START-WINDOWS.bat`（首次自动编译，需联网）启动 demo。已有 Maven 可在仓库根目录执行 `mvn clean verify`。访问 **http://localhost:8080**：

| 角色 | 账号 | demo 密码 |
| --- | --- | --- |
| 用户 | alice | password123 |
| 管理员 | admin | admin12345 |

页面支持注册普通用户。demo 模式创建三台样例设备，分别为直接预约、需要审批、需要培训且审批。管理员可在工作台新增设备、授予培训资格、处理预约、停用设备。

默认使用 ./data/booking.mv.db 文件数据库，重启保留数据。请在相同工作目录启动；**H2 文件模式用于单实例体验**，多实例使用 MySQL / MariaDB。

## MySQL / MariaDB

创建独立数据库 xplanet_booking 和拥有该库 DDL/DML 权限的应用账号。不要复用旧商城的库。Flyway 自动创建并校验表结构。

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/xplanet_booking?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export DB_USER=booking
export DB_PASSWORD='<数据库密码>'
export ADMIN_PASSWORD='<至少8位的管理员密码>'
java -jar xplanet-booking/target/xplanet-booking-2.0.0.jar
```

PowerShell 使用 $env:变量名='值'。ADMIN_PASSWORD 只在 admin 尚不存在时创建管理员，之后修改环境变量不会重置已有密码。普通模式不创建 demo 数据。

Docker Compose：

```bash
cp docker/booking.env.example docker/booking.env
# 编辑 booking.env，替换数据库、root 和管理员密码
docker compose --env-file docker/booking.env -f docker/compose-booking.yml up --build -d
```

Compose 使用 MySQL 8.4，数据库不映射到宿主端口。云端验证使用 H2 和 MariaDB；Docker Compose 和 MySQL CI 需在对应环境执行，详见[验证记录](docs/BOOKING-VALIDATION.md)。

## 业务规则

- 单件资源按 **[开始,结束)** 预约；起止对齐整点或半点，0.5～8 小时，未来 30 天内。设备目前支持全天预约，没有营业日历。
- 需培训的设备先取得资格；待审批预约持有时段。审批期限为提交后 30 分钟与开始时间中的较早者。
- 冲突进入候补。重叠的早期候补不能被后来的短预约绕过，不重叠的时段可以分配。
- 候补获得 OFFERED 资格后持有时段，默认 10 分钟内确认，最迟在开始前确认。需审批的设备确认后仍须审批。
- 候补开始时间已到则过期，不自动改变申请区间。签到窗口为开始前 10 分钟至开始后 15 分钟；超时未签到记为 NO_SHOW。
- 设备停用取消未使用的有效预约及候补。设备正在使用时不能停用，也不能撤销使用者的培训资格。
- API 时间是 UTC 无偏移 ISO 日期时间，例如 2026-10-16T02:00:00；页面与浏览器本地时区互转。

## 关键实现

| 能力 | 实现 |
| --- | --- |
| 时段冲突 | 设备行锁 + (equipment_id,slot_at) 数据库主键 |
| 重复提交 | (user_id,request_key) 唯一约束，重放核对参数 |
| 状态竞争 | 统一加锁顺序，状态条件更新、时段释放与审计同事务 |
| 超时恢复 | 数据库持久化状态和期限，多实例重复调度 |
| 通知恢复 | 业务与 Outbox 同事务，Inbox 与投递确认同事务 |
| 权限 | BCrypt、随机 Bearer Token、数据库只保存 Token 哈希、服务端归属校验 |

同设备写操作串行化，优先保障正确性，不宣称互联网级吞吐。站内通知采用同库持久化投递，未接入外部 MQ、邮件或短信。

## 验证

```bash
cd xplanet-booking
./mvnw clean verify
```

连接独立测试数据库运行同一套测试。**测试会清空该测试库的业务表**：

```bash
export TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/booking_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export TEST_DB_USER=booking
export TEST_DB_PASSWORD='<测试库密码>'
./mvnw verify
```

双实例 HTTP 验证：两台应用连接同库，分别监听 8080 / 8081，随后从仓库根目录运行：

```bash
ADMIN_PASSWORD='<管理员密码>' python scripts/booking_e2e.py \
  --base-url http://localhost:8080 --second-url http://localhost:8081 --users 32
```

脚本创建随机前缀的测试用户和设备，结束时停用测试设备。请在独立验证环境运行。

- [架构、状态机与一致性边界](docs/BOOKING-ARCHITECTURE.md)
- [API 与代码学习路线](docs/BOOKING-API.md)
- [实际验证记录](docs/BOOKING-VALIDATION.md)
- [数据库 CI 配置](.github/workflows/booking.yml)
- [当前项目评估、优化优先级与面试表述](docs/BOOKING-PROJECT-REVIEW.md)
- [本次本地同步和验证记录](docs/BOOKING-LOCAL-VERIFICATION.md)

## 项目边界

设备状态由软件签到和任务维护，未接入硬件控制或学校认证系统。公开部署前需配置 HTTPS、备份、网关限流与正式账号管理。demo 密码只用于本地体验。
