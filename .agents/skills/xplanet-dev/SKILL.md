---
name: xplanet-dev
description: "Develop and verify XPlanet's equipment reservation platform: Java 17, Spring Boot, JDBC transactions, slot exclusion, waitlist state machines, durable notifications, H2/MySQL/MariaDB and browser flows. Use only for XPlanet repositories; inspect the checked-out branch before assuming architecture."
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
2. On main, trace ApiController -> BookingService -> Store -> database transactions; inspect Models, AuthService, Maintenance and OutboxDispatcher for related behavior.
3. Read `xplanet-booking/src/main/resources/db/migration` before changing persistence assumptions. Add new Flyway migrations; do not rewrite applied migrations.
4. Use `docs/BOOKING-ARCHITECTURE.md`, `docs/BOOKING-API.md` and `docs/BOOKING-VALIDATION.md` as design intent and historical evidence, but verify claims against current code.
5. Main is a single deployable booking application. The optimization branch has a different architecture; do not introduce its modules or infrastructure into main automatically.

## 3. Diagnose before changing

1. Reproduce or identify the failing invariant when practical.
2. Distinguish code defects from missing infrastructure, stale local data, port conflicts, and configuration errors.
3. For concurrency or performance work, state the protected invariant first:
   - one holder per equipment and half-hour slot;
   - idempotent request-key replay and payload consistency;
   - valid reservation state transitions, ownership and qualification checks;
   - fair waitlist allocation, expiry and crash recovery;
   - atomic booking/slot/audit/outbox changes and idempotent local inbox delivery;
   - bounded connection, thread, queue and scan usage.
4. If the user requested diagnosis only, report the cause and evidence without implementing a fix.

## 4. Implement narrowly

1. Make the smallest coherent change that fixes the verified issue.
2. Preserve module boundaries and existing API contracts unless the request requires a contract change.
3. Add or update tests near changed behavior. Keep the full reactor suite green, but do not treat unit tests or a successful compile as infrastructure-level proof.
4. Avoid speculative infrastructure or framework additions.
5. Never embed new credentials. Treat compose credentials as local demo defaults.

## 5. Validate in increasing cost order

Run only the levels relevant to the change:

1. On main run `mvn -f xplanet-booking/pom.xml verify`, or `mvn clean verify` at the root to build the reactor.
2. External database tests use TEST_DB_URL/TEST_DB_USER/TEST_DB_PASSWORD and clear their business tables: only use a dedicated test database.
3. Demo mode uses a local H2 file; do not start multiple processes against the same H2 file. Multi-instance verification requires MySQL/MariaDB.
4. Validate Compose with `docker compose --env-file docker/booking.env -f docker/compose-booking.yml config --quiet` after preparing local credentials. Do not print secrets.
5. Start with `START-WINDOWS.bat` or the built JAR; poll `/actuator/health` before exercising APIs.
6. Use `scripts/booking_e2e.py` for HTTP concurrency and `scripts/booking_recovery.py` for forced process exit/restart in an isolated database.
7. For browser behavior, use `$playwright`; preserve historical reports and write fresh local evidence separately.
8. Report skipped environments explicitly; a successful H2 run does not prove MySQL or MariaDB behavior.

Do not run `docker compose down -v` unless the user explicitly authorizes deleting local volumes.

## 6. Report the outcome

Summarize:

- what was learned or changed;
- which files and invariants were involved;
- commands run and their outcomes;
- remaining risks, skipped checks, or required infrastructure;
- exact next command when the user needs to continue manually.
