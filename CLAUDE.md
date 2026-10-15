# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

`AGENTS.md` (imported above) is the detailed handoff: repo layout, locked decisions D1–D10, the public API, gotchas, and open team questions. `ADR-001-user-access-service.md` explains *why* each decision was made and `DESIGN.md` is the full design. Keep all three in agreement: if a locked decision changes, update the ADR decision log first, then `DESIGN.md`/`AGENTS.md`.

**Standing instruction:** the open questions in AGENTS.md §10 are awaiting team review. Fold in team feedback and adapt the design *before* extending the implementation.

## Commands

Maven multi-module build (Java 21, Spring Boot 3.5.16). Run from the repo root.

```bash
mvn clean verify                                   # build all modules + run tests
mvn -pl access-service -am test                    # tests for the PDP only (-am builds rbac-model first)
# single class / single method (the flag stops surefire failing in rbac-model, which has no matching test)
mvn -pl access-service -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RbacResolverTest
mvn -pl access-service -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RbacResolverTest#scopesAreOrMergedPerDimension'
mvn -pl access-service -am spring-boot:run         # run the PDP
```

- The PDP only boots against live PostgreSQL, Redis and RabbitMQ (Hibernate `ddl-auto: validate` against the Flyway schema, plus a broker connection). There's no embedded or fake setup yet. Connection settings are env vars with localhost defaults in `access-service/src/main/resources/application.yml` (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `RABBIT_HOST`, …).
- The unit tests are `RbacResolverTest` (7), `AdminServiceTest` (5), and `DefaultAccessClientMetricsTest` (5, starter — client metrics wiring, no infra needed). They run on any JVM.
- Integration tests (Testcontainers, need Docker — they skip without it): `AccessServiceIntegrationTest` (access-service) and `InvalidationSubscriberIntegrationTest` (starter). **Green as of 2026-10-15** — see AGENTS.md §0.
- Observability: metrics are recorded via the **Micrometer API** and exported as **OTLP** (OpenTelemetry). PDP uses `micrometer-registry-otlp`; the starter's Micrometer is an **optional** dependency behind a no-op-by-default `RbacClientMetrics` sink. Meter names and the OTLP endpoint knob are in AGENTS.md §0/§9.
- There is no linter or formatter configured. The `-Xlint:unchecked` warning on `RbacResolver` is expected.
- This is a local-only git repository (no remote). Commit after each green milestone.

## Architecture in brief

There are three modules, and the dependency direction matters:

- **`rbac-model`**: dependency-free records shared by both sides. `GrantSet` is the single cached unit and the wire contract. `CacheKeys` and `Bus` hold the Redis key scheme and the RabbitMQ names. Anything the PDP writes and the client reads must be defined here once, never re-hardcoded in a module.
- **`access-service`** (the PDP): `rbac/RbacResolver` is the pure core with no Spring and no I/O. It unions permissions, OR-merges scope dimension lists per resource type, and makes a UI element visible iff its gating permission is granted. `GrantLoader` loads profile grants from the DB in a single query. `GrantService` puts the L2 Redis cache in front of loader + resolver. Every admin write in `AdminService` follows a fixed sequence: save → synchronous change audit → `VersionService.bump()` (Redis INCR) → `InvalidationPublisher` publishes an `InvalidationEvent`.
- **`rbac-client-spring-boot-starter`** (PEP helper): host services code against `AccessClient` (`has` / `scope` / `ui`). `DefaultAccessClient` looks up L1 Caffeine → L2 Redis (read-only) → PDP `POST /v1/grants`, then derives the answer locally from the `GrantSet`. It refetches when an entry's `version` is older than the local version, which `InvalidationSubscriber` raises.

Invariants to preserve when editing:
- **Fail-closed**: on error, `has` must deny, while `scope` and `ui` must throw so that an unscoped query can never run.
- **Audit**: decision audits are `@Async` and their failures are swallowed. Change audits are synchronous.
- **Starter optional deps**: Redis and AMQP are `<optional>` and guarded by `@ConditionalOnClass`/`@ConditionalOnBean`, so a host app can run L1-only.
- **Schema**: Flyway owns the schema (`db/migration/V*.sql`), so add a new versioned migration rather than editing an existing one. The audit tables are range-partitioned by month.
