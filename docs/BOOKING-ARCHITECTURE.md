# 架构与设计取舍

## 分层

```mermaid
flowchart TD
  UI[用户与管理员工作台] --> API[HTTP API 与身份校验]
  API --> TX[预约业务事务]
  TX --> LOCK[设备行锁]
  LOCK --> SLOTS[预约与唯一时段记录]
  LOCK --> EVENTS[状态审计与 Outbox]
  WORKER[定时维护] --> TX
  EVENTS --> DISPATCH[站内信投递事务]
  DISPATCH --> INBOX[Inbox 与投递确认]
```

`ApiController` 负责输入和身份；`BookingService` 管理事务与状态；`Store` 管理数据访问；`Maintenance` 和 `OutboxDispatcher` 执行后台任务；`AuthService` 管理账号及可撤销会话。同源 HTML/CSS/JS 页面无需 Node 构建。

## 状态机

```mermaid
stateDiagram-v2
  [*] --> CONFIRMED: 无冲突且无需审批
  [*] --> PENDING_APPROVAL: 无冲突且需审批
  [*] --> WAITLISTED: 时间冲突或前方有重叠候补
  WAITLISTED --> OFFERED: 可分配时段
  OFFERED --> PENDING_APPROVAL: 确认且需审批
  OFFERED --> CONFIRMED: 确认且无需审批
  PENDING_APPROVAL --> CONFIRMED: 批准
  PENDING_APPROVAL --> REJECTED: 拒绝
  CONFIRMED --> IN_USE: 签到
  IN_USE --> COMPLETED: 主动结束或到期
  CONFIRMED --> NO_SHOW: 签到超时
  WAITLISTED --> EXPIRED: 开始时间已到
  OFFERED --> EXPIRED: 确认超时
  PENDING_APPROVAL --> EXPIRED: 审批超时
```

有效预约在规则允许时可以取消。设备停用取消所有未使用的有效预约和候补；培训撤销只作用于该用户、该设备。终态不能通过迟到审批等请求重新激活。

## 冲突与事务

一次 2 小时预约占用 4 条半小时 `reservation_slot`，主键 `(equipment_id,slot_at)` 保证数据库中没有重复占用。所有资源写操作先锁设备行，再重新读取预约状态。使用 READ_COMMITTED，避免等待设备锁后使用旧一致性快照。

预约、时段、审计和事件同事务提交。唯一约束提供最终保护；设备行锁让冲突判断和候补选择一致。同设备写请求串行化，不同设备可并行。当前一次预约一台设备，不支持多设备原子预约。

## 候补公平性

候补按 ID 顺序处理。较早的长预约因部分区间被占而无法满足时，后来的重叠短预约也继续等待；不重叠的请求不受阻。这会牺牲部分利用率来减少长预约饥饿，是明确的产品取舍。

分配时先占时段再写 OFFERED，同事务提交。确认、取消、审批和超时都取得同一设备锁；重复调度不能重复分配或释放。候补起始时间已到就过期，不修改用户申请区间。

## 退出与恢复

数据库保存期限、状态和事件，不依赖进程内倒计时。启动后扫描即可继续处理。默认扫描间隔 5 秒，实际延迟还包含数据库等待与处理时间，不承诺严格 5 秒完成。

业务保存 Outbox；另一个事务锁定事件、写 Inbox 并记录投递时间。中途异常整体回滚，后续继续执行。Inbox 的 event_id 唯一。该机制目前只针对同库站内通知；改成外部 MQ / 邮件后必须重新考虑重试、死信及接收方幂等。

## 身份与时间

注册始终创建 USER；初始 ADMIN 由启动配置创建。密码使用 BCrypt，限制 UTF-8 72 字节。Token 为 256 位随机数，数据库只存 SHA-256 哈希，有效 12 小时，可退出撤销。

角色和预约归属由服务端检查。时段接口只返回占用时间，不暴露申请人、用途和预约 ID。API 与数据库连接统一 UTC，页面转换本地时区。

登录失败限制为实例内辅助限制，集群级防护需要网关。H2 文件模式仅供单实例。自动完成代表软件预约时段结束，不能证明物理设备已停止运行；硬件门禁与设备控制尚未集成。
