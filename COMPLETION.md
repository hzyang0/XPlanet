# ✅ XPlanet 项目启动 - 完整指南

🚀 启动指南

前置条件已完成：
✓ xplanet-user: config/CorsConfig.java 已创建
✓ xplanet-user: application.yml 已配置
✓ xplanet-interaction: application.yml 已配置

启动步骤：

1️⃣ 启动中间件
docker compose -f docker/docker-compose-infra.yml up -d

2️⃣ 编译项目
mvn -DskipTests clean install

3️⃣ 启动三个服务

Windows (推荐):
start-services.bat

其他平台 (三个终端):
mvn -pl xplanet-article -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-user -am spring-boot:run

4️⃣ 验证功能

Windows:
test-apis.bat

手动:
curl http://localhost:8083/api/user/1
curl http://localhost:8081/api/article/1
curl -X POST -H "X-User-Id: 100" http://localhost:8082/api/like/1

**预期结果**: 所有请求返回 200 状态码和 JSON 数据



## 立即启动三个服务

### 方法 1: 使用 Windows 脚本（推荐）

```bash
start-services.bat        # 启动所有三个服务
test-apis.bat             # 验证功能
```

### 方法 2: 手动启动（三个独立终端）

```bash
mvn -pl xplanet-article -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-user -am spring-boot:run
```

### 方法 3: IDE 运行

- `ArticleApplication.main()` (8081)
- `InteractionApplication.main()` (8082)
- `UserApplication.main()` (8083)


## 常见问题速查

**Q: 启动失败？**
```bash
# 检查中间件
docker compose -f docker/docker-compose-infra.yml ps

# 查看日志
docker compose -f docker/docker-compose-infra.yml logs
```

**Q: 连接超时？**
- 确保 MySQL、Redis、RocketMQ 都已启动
- 检查 `docker/broker.conf` 中 `brokerIP1 = 127.0.0.1`

**Q: CORS 错误？**
- 所有三个服务都已配置 CORS
- 检查浏览器控制台是否有具体错误信息

**Q: 消息不消费？**
- 检查 RocketMQ broker 是否正常运行
- 查看应用日志中是否有异常

