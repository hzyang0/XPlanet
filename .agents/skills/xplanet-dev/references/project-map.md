# XPlanet Project Map

## Stack

- Java 17, Maven multi-module reactor, Spring Boot 2.7.18.
- MyBatis-Plus and MySQL 8 for persistence.
- Redis 7, Redisson, and Caffeine for shared/local caching, locks, and rate limiting.
- RocketMQ 4.9.7 for reliable like-event delivery and cache invalidation.
- Docker Compose for local MySQL, Redis, RocketMQ nameserver, and broker.
- `xplanet-web/index.html` is a static single-file demo UI; there is no Node build.

## Modules and ports

| Module | Port | Responsibility |
| --- | ---: | --- |
| `xplanet-common` | - | Shared result types, exceptions, authentication, and rate limiting |
| `xplanet-api` | - | Cross-service DTO and VO contracts |
| `xplanet-user` | 8083 | User lookup, bcrypt password verification, and JWT/JWS issuing |
| `xplanet-article` | 8081 | Articles, comments, two-level cache, durable cache-invalidation Outbox, user lookup, durable like-count projection |
| `xplanet-interaction` | 8082 | Article validation, like state machine, Transactional Outbox, and recoverable RocketMQ relay |

Important entrypoints:

- `xplanet-user/.../UserApplication.java`
- `xplanet-article/.../ArticleApplication.java`
- `xplanet-interaction/.../InteractionApplication.java`

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
```

## Health and smoke checks

```powershell
Invoke-RestMethod http://localhost:8083/actuator/health
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
Invoke-RestMethod http://localhost:8081/api/article/1
Invoke-RestMethod 'http://localhost:8081/api/article/list?pageNum=1&pageSize=10'
```

Protected write flows require a token from `POST /api/user/login`. Verify the controller and authentication filter before constructing the request because demo scripts and current code may drift.

## Reading order by task

- Architecture: root `pom.xml` -> module POMs -> `docs/ARCHITECTURE.md` -> application entrypoints and configs.
- Persistence: `sql/init.sql` -> entity -> mapper/XML -> service transaction boundary.
- Cache: article update/delete transaction -> cache-invalidation Outbox relay -> MQ invalidation consumer -> L1/L2 cache manager.
- Likes: interaction validates the active article through typed OpenFeign -> state transition + Outbox transaction -> leased relay -> RocketMQ -> unique delta inbox -> transactional batch projection.
- Authentication: user login -> token utility/filter in common -> protected controller endpoint.
- Performance: benchmark scripts and claims -> implementation/configuration -> fresh measurements. Never repeat benchmark numbers without reproducing or clearly labeling their source.

## Current verification caveats

- The reactor currently has unit tests in common, user, article, and interaction; keep them green and add focused tests for changed invariants.
- A green Maven build still does not prove Redis/RocketMQ/MySQL behavior; run the authenticated API and eventual-consistency smoke flow when infrastructure is available.
- Preserve Docker volumes by default; `down -v` deletes local database and queue state.
