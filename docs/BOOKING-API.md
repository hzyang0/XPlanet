# API 与代码学习路线

## 请求约定

基础地址 `/api`。登录 / 注册取得 token，其他请求使用 `Authorization: Bearer <token>`。JSON 时间响应统一带 UTC `Z`，例如 `2026-10-20T02:00:00Z`；无偏移输入按 UTC 解释以兼容历史客户端。错误使用真实 HTTP 状态码和 `{"status":409,"message":"原因"}`，包括 400 参数、403 权限、404 资源、405 方法、409 冲突及 415 Content-Type。

列表默认 50 条，使用 limit（1～100）和 offset 分页。预约与通知按 ID 倒序，设备按 ID 正序。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | username/password，始终创建 USER |
| POST | `/auth/login` | 登录 |
| POST | `/auth/logout` | 撤销 Token |
| GET | `/me` | 身份、培训资格、服务端 UTC 时间 |
| GET | `/equipment?q=示波器` | 检索设备 |
| GET | `/equipment/{id}/schedule?from=...&to=...` | 查询 7 天内占用的半小时时段 |
| POST | `/equipment` | 管理员新增设备 |
| PUT | `/equipment/{id}/enabled` | 管理员启停设备 |
| PUT | `/equipment/{id}/training` | 管理员授予 / 撤销资格 |
| POST | `/reservations` | 幂等创建预约 |
| GET | `/reservations` | 用户看自己的，管理员看全部 |
| GET | `/reservations/{id}` | 详情，验证归属 |
| POST | `/reservations/{id}/accept` | 确认候补 |
| POST | `/reservations/{id}/decision` | 管理员审批 |
| POST | `/reservations/{id}/cancel` | 开始前取消 |
| POST | `/reservations/{id}/check-in` | 签到 |
| POST | `/reservations/{id}/finish` | 结束使用 |
| GET | `/reservations/{id}/audit` | 状态审计 |
| GET | `/notifications` | 当前用户站内信 |
| POST | `/notifications/{id}/read` | 标记已读 |
| GET | `/admin/users?q=alice` | 管理员检索用户，最多 100 条 |
| GET | `/admin/summary` | 状态分布、占用时段、未送达事件数 |
| GET | `/admin/operations` | 管理员查看积压年龄、隔离事件、实例维护次数与耗时 |
| GET | `/admin/events` | 管理员查看最多100条隔离失败事件 |
| POST | `/admin/events/{id}/retry` | 管理员将事件重新加入队列，操作独立审计 |
| GET | `/equipment/{id}/blocks?from=...&to=...` | 查询最多31天的维修安排 |
| POST | `/equipment/{id}/blocks` | 管理员创建维修时段，同键重放核对参数 |
| DELETE | `/equipment/blocks/{blockId}` | 管理员幂等取消维修，不影响其他预约时隙 |
| POST | `/reservations/{id}/reschedule` | 本人将已确认且未开始的预约原子改期，返回关联的新预约 |

创建预约示例（替换成未来 30 天内时间）：

```json
{"equipmentId":1,"startAt":"2026-10-16T02:00:00","endAt":"2026-10-16T04:00:00","purpose":"电子测量实验","requestKey":"f646ba53-23e1-4931-b2d8-45ad224eae62"}
```

超时后用相同 requestKey 和参数重试，改变参数要换新标识。同一用户在同设备的重叠有效预约或候补会被拒绝。

设备创建、启停、培训和审批请求体依次为：

```json
{"name":"显微镜 A01","category":"成像","location":"506","description":"培训后使用","approvalRequired":true,"trainingRequired":true}
```

```json
{"enabled":false,"expectedVersion":0}
```

```json
{"userId":2,"granted":true}
```

```json
{"approve":false,"reason":"请补充实验方案后重新预约"}
```

## 学习顺序

1. 运行页面预约，在 ApiController.create 打断点，跟踪身份与时间。
2. 看 Models 和迁移 SQL，明确状态、主键和时段半开区间。
3. 看 Store，理解设备行锁与时段唯一约束各自的作用。
4. 看 BookingService.create，跟踪幂等、资格、冲突和事务。
5. 看 cancel / maintain，理解候补顺序和截止时间。
6. 看 accept / decide / checkIn / finish，对照状态审计验证。
7. 看 OutboxDispatcher，观察业务已提交但通知尚未送达的情况。
8. 单独运行 BookingIntegrationTest 中的一个用例，修改规则并检查哪些断言应改变。

9. 看 `addBlock/removeBlock`、V3 迁移，理解维修和预约共用时隙排他与到期清理。
10. 看 `reschedule`，跟踪取消原单、创建替代单与关联记录的事务回滚。
11. 看 `OutboxDelivery`，理解单事件事务、退避、隔离、管理员重试审计。

维修请求体：

```json
{"startAt":"2026-10-20T02:00:00Z","endAt":"2026-10-20T04:00:00Z","reason":"设备检修","requestKey":"maintenance-unique-key"}
```

改期请求体：

```json
{"startAt":"2026-10-21T02:00:00Z","endAt":"2026-10-21T04:00:00Z","requestKey":"reschedule-unique-key"}
```

时间请替换成未来30天内的半小时边界。维修最多7天；创建时有任何重叠有效预约、候补或停机都会返回409，不自动取消原预约。改期仅支持本人、同设备；目标不能绕过已有候补；失败保留原预约，需审批设备重新审批。同键同参数重放返回关联新单；改变参数或操作必须使用新键。
