# XPlanet Project Map

## Stack

- Java 17, Maven multi-module reactor, Spring Boot 2.7.18.
- MyBatis-Plus and MySQL 8 for persistence.
- Redis 7, Redisson, and Caffeine for shared/local caching, locks, and rate limiting.
- RocketMQ 4.9.7 for reliable like-event delivery, cache invalidation, and AI commands.
- Docker Compose for local MySQL, Redis, RocketMQ nameserver, and broker.
- `xplanet-web/index.html` is a static single-file demo UI; there is no Node build.
- Spring Cloud Gateway is the unified external entrypoint. In full Docker mode only port 8080 is published.

## Modules and ports

| Module | Port | Responsibility |
| --- | ---: | --- |
| `xplanet-common` | - | Shared result types, exceptions, authentication, and rate limiting |
| `xplanet-api` | - | Cross-service DTO and VO contracts |
| `xplanet-gateway` | 8080 | Routing, CORS, request TraceId, and first-layer JWT validation |
| `xplanet-user` | 8083 | User lookup, bcrypt password verification, and JWT/JWS issuing |
| `xplanet-article` | 8081 | Articles, comments, two-level cache, durable cache-invalidation Outbox, user lookup, durable like-count projection |
| `xplanet-interaction` | 8082 | Article validation, like state machine, Transactional Outbox, and recoverable RocketMQ relay |
| `xplanet-ai` | 8084 | AI task control plane, reliable command Outbox, progress SSE, reports, review, and publishing |
| `xplanet-agent` | 8000 (internal) | Python/LangGraph bounded execution, checkpoints, evidence, writing, and evaluation |

Important entrypoints:

- `xplanet-gateway/.../GatewayApplication.java`
- `xplanet-user/.../UserApplication.java`
- `xplanet-article/.../ArticleApplication.java`
- `xplanet-interaction/.../InteractionApplication.java`
- `xplanet-ai/.../AiApplication.java`
- `xplanet-agent/src/xplanet_agent/api.py`

## Local prerequisites

Expected commands:

```powershell
java -version
mvn -version
docker version
docker compose version
```

Expected local versions are Java 17 and a Maven version compatible with the root reactor. Docker must be able to reach its engine.

Environment variables have local defaults:

```text
MYSQL_HOST=localhost
MYSQL_USER=root
MYSQL_PWD=root123
REDIS_HOST=localhost
REDIS_PWD=
ROCKETMQ_NS=localhost:9876
TOKEN_SECRET=<at least 32 UTF-8 bytes>
```

## Build and run

Validate and start infrastructure:

```powershell
docker compose -f docker/docker-compose-infra.yml config
docker compose -f docker/docker-compose-infra.yml up -d
docker compose -f docker/docker-compose-infra.yml ps
```

Build the full reactor:

```powershell
mvn test
mvn -DskipTests clean install
```

Start all services in separate PowerShell windows:

```powershell
.\scripts\start-local.ps1
```

For a single foreground service:

```powershell
mvn -pl xplanet-user -am spring-boot:run
mvn -pl xplanet-article -am spring-boot:run
mvn -pl xplanet-interaction -am spring-boot:run
mvn -pl xplanet-ai -am spring-boot:run
mvn -pl xplanet-gateway -am spring-boot:run
```

## Health and smoke checks

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/article/1
Invoke-RestMethod 'http://localhost:8080/api/article/list?pageNum=1&pageSize=10'
./scripts/smoke-test.ps1
./scripts/test-agent-recovery.ps1
```

Protected write flows require a token from `POST /api/user/login`. Verify the controller and authentication filter before constructing the request because demo scripts and current code may drift.

## Reading order by task

- v3 implementation target: `docs/XPlanet-Agent-First整体重构方案.md` is authoritative; the older research optimization plan is historical v1/v2 evidence.
- Beginner onboarding: `docs/BEGINNER-GUIDE.md` -> Gateway config/filters -> one controller-to-database request flow.
- Architecture: root `pom.xml` -> module POMs -> `docs/ARCHITECTURE.md` -> application entrypoints and configs.
- Persistence: `sql/init.sql` -> entity -> mapper/XML -> service transaction boundary.
- Cache: article update/delete transaction -> cache-invalidation Outbox relay -> MQ invalidation consumer -> L1/L2 cache manager.
- Likes: interaction validates the active article through typed OpenFeign -> state transition + Outbox transaction -> leased relay -> RocketMQ -> unique delta inbox -> transactional batch projection.
- Authentication: Gateway first-layer validation -> user login -> token utility/interceptor in common -> protected controller and resource ownership check.
- AI: task transaction + Outbox -> RocketMQ -> Java consumer -> Python Agent graph -> checkpoint/progress/result -> review -> OpenFeign article publish.
- Performance: benchmark scripts and claims -> implementation/configuration -> fresh measurements. Never repeat benchmark numbers without reproducing or clearly labeling their source.

## Current verification caveats

- The reactor has tests across common, gateway, user, article, interaction, and AI; keep them green and add focused tests for changed invariants.
- A green Maven build still does not prove Redis/RocketMQ/MySQL behavior; run the authenticated API and eventual-consistency smoke flow when infrastructure is available.
- Preserve Docker volumes by default; `down -v` deletes local database and queue state.
