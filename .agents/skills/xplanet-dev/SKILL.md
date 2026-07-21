---
name: xplanet-dev
description: "Run the complete XPlanet repository workflow: understand architecture and request flows, diagnose defects, modify or refactor Java/Spring code, assess concurrency and performance, set up the Java/Maven/Docker environment, build and test modules, start services, and verify APIs or the static web UI. Use for development work in the XPlanet repository, especially Spring Boot, Maven, MySQL, Redis, RocketMQ, caching, rate limiting, authentication, Docker Compose, smoke tests, and end-to-end verification. Do not use for unrelated repositories."
---

# XPlanet Development Workflow

Work from the repository root. Read `references/project-map.md` before the first material action in a thread.

## 1. Establish the baseline

1. Inspect `git status --short` and preserve all pre-existing changes.
2. Confirm the requested scope and classify it as read/report, diagnosis, implementation, environment setup, or run/verification.
3. Check required executables with `java -version`, `mvn -version`, and `docker version` only when the task needs them.
4. Do not start containers or services for a read-only architecture or review request.

## 2. Read and trace the relevant path

1. Start with the root `pom.xml`, then the target module `pom.xml` and `application.yml`.
2. Trace HTTP requests from controller through service, mapper, Redis/cache, RocketMQ, and database boundaries.
3. For shared behavior, inspect `xplanet-common`; for cross-service DTOs, inspect `xplanet-api`.
4. Read `sql/migrations/V004__baseline_schema.sql` and every later migration before changing persistence assumptions.
5. Use `docs/ARCHITECTURE.md` and `docs/HA-AND-DEGRADE.md` as design intent, but verify claims against current code.

## 3. Diagnose before changing

1. Reproduce or identify the failing invariant when practical.
2. Distinguish code defects from missing infrastructure, stale local data, port conflicts, and configuration errors.
3. For concurrency or performance work, state the protected invariant first:
   - cache and database consistency;
   - idempotent like processing;
   - durable Outbox delivery and idempotent like projection;
   - safe cache rebuild locking;
   - bounded connection, thread, and queue usage.
4. If the user requested diagnosis only, report the cause and evidence without implementing a fix.

## 4. Implement narrowly

1. Make the smallest coherent change that fixes the verified issue.
2. Preserve module boundaries and existing API contracts unless the request requires a contract change.
3. Add or update tests near changed behavior. Keep the full reactor suite green, but do not treat unit tests or a successful compile as infrastructure-level proof.
4. Avoid speculative infrastructure or framework additions.
5. Never embed new credentials. Treat compose credentials as local demo defaults.

## 5. Validate in increasing cost order

Run only the levels relevant to the change:

1. Targeted module test: `mvn -pl <module> -am test`.
2. Reactor test/build: `mvn test`, then `mvn -DskipTests clean install` when packaging or cross-module compatibility matters.
3. Compose validation: `docker compose -f docker/docker-compose-infra.yml config`.
4. Local infrastructure: `docker compose -f docker/docker-compose-infra.yml up -d`, then inspect `docker compose -f docker/docker-compose-infra.yml ps`.
5. Start affected services with Maven or `scripts/start-local.ps1`.
6. Poll actuator health endpoints before API checks.
7. Exercise the exact API flow changed, including login/token setup for protected writes.
8. For browser behavior, use `$playwright`; use `$screenshot` only when visual evidence is useful.
9. For an explicitly requested security review, use `$security-best-practices`; use `$security-threat-model` only for an explicit threat-model request.

Do not run `docker compose down -v` unless the user explicitly authorizes deleting local volumes.

## 6. Report the outcome

Summarize:

- what was learned or changed;
- which files and invariants were involved;
- commands run and their outcomes;
- remaining risks, skipped checks, or required infrastructure;
- exact next command when the user needs to continue manually.
