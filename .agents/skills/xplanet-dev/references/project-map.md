# XPlanet main：实验设备预约与资源调度

## 当前边界

Java 17、Spring Boot 3.5、Spring JDBC、Spring Security、Flyway；前端为同源 HTML/CSS/JavaScript。
单体服务默认监听 8080。H2 文件用于单实例体验，MySQL/MariaDB 用于共享数据库、多实例部署。
main 不依赖旧社区、秒杀、AI、Redis、RocketMQ 或独立 Gateway 模块。

## 代码阅读路径

- `xplanet-booking/src/main/java/com/xplanet/booking/Models.java`：请求、记录、角色和预约状态。
- `ApiController`：接口、参数校验和归属检查。
- `BookingService`：设备行锁、预约状态、候补、公平性、资格、审批、签到、到期维护。
- `Store`：JDBC、半小时时隙分配与释放、审计和 Outbox。
- `OutboxDispatcher`：同库 Outbox -> Inbox，投递和确认同事务。
- `Maintenance`：逐设备独立事务维护、通知投递、会话清理。
- `AuthService` / `SecurityConfig`：BCrypt、随机 Bearer Token、Token 哈希、角色权限。

## 关键不变量

1. `(equipment_id, slot_at)` 主键保证同一时隙只有一条占用。
2. 资源写操作先锁设备行；READ_COMMITTED 下锁后重读预约，状态条件更新防止旧状态覆盖。
3. `(user_id, request_key)` 唯一且重放核对参数。
4. 预约、时隙、审计、Outbox 同事务；Inbox 与投递确认同事务，`event_id` 唯一。
5. 期限持久化，进程重启后扫描恢复；H2 不能替代真实共享数据库验证。

## 命令与文档

```powershell
mvn clean verify
.\START-WINDOWS.bat
# Built JAR is ignored by Git:
java -jar xplanet-booking/target/xplanet-booking-2.0.0.jar --spring.profiles.active=demo
```

外部数据库测试只连接独立测试库；现有测试会删除测试库业务表。
阅读 `docs/BOOKING-ARCHITECTURE.md`、`BOOKING-API.md`、`BOOKING-PROJECT-REVIEW.md`。
云端交付记录见 `BOOKING-VALIDATION.md`，本次本机复验见 `BOOKING-LOCAL-VERIFICATION.md`。
