# AGENTS.md — User Access Service (RBAC) handoff

> **Purpose of this file:** a cold-start context pack for a *new* agent (or human)
> session so work can resume without re-deriving the design. Read this first, then
> `ADR-001-user-access-service.md` (the "why") and `DESIGN.md` (the "what").

---

## 1. What this is

A **central thin Policy Decision Point (PDP)** plus a **shared Spring Boot client
starter** that let stateless microservices:

1. **Check** whether a user (identified by a *set of profiles*) may do something —
   a permission key like `orders:refund`.
2. **Scope** data queries per resource type — e.g. `orders` → `orgIds=[1,2,3]` —
   the service returns the *filter dimensions*, the client owns applying them.
3. **Show** the right UI (which tabs/elements are visible) per front-end app.

**v1 is pure RBAC**: decisions are made on the profile set alone. There is no
user-attribute (ABAC) evaluation. The design keeps an escape hatch (a SpEL
`condition` column, ADR D3) for later without changing the engine.

### The non-negotiable constraints (from the stakeholder)
- The **client owns identity + all IDM calls + enforcement**. The PDP is stateless
  about identity: it is *handed* the profile set and trusts the (authenticated)
  caller. It never calls the identity manager.
- **Stateless clients must not hit a REST call on every check** → aggressive caching.
- **Audit trail is mandatory.**
- **Developers keep ownership** of the code that *enforces* policy (the PEP stays
  in each microservice; we only provide a PDP + a helper SDK).
- ~15,000 users at peak; flexible and scalable; API/config-first (no admin UI in v1).

---

## 2. Repo layout

```
rbac/
├── pom.xml                                  # parent (Spring Boot 3.2.5, Java 21)
├── DESIGN.md                                # the "what" — full design, review artifact
├── ADR-001-user-access-service.md           # the "why" — assumptions, decisions, review artifact
├── AGENTS.md                                # THIS FILE
├── rbac-model/                              # dependency-free contracts (records) — shared by both sides
│   └── .../model/
│       ├── UserContext.java                 # (userId, profiles) — the identity handed over
│       ├── CacheKeys.java                   # shared Redis key scheme + POLICY_VERSION key
│       ├── Bus.java                         # shared RabbitMQ exchange/routing-key/queue names
│       └── dto/
│           ├── CheckRequest / CheckResult   # check API shapes
│           ├── GrantSet                     # ★ the single cached unit (perms+scopes+ui+version)
│           ├── Scope                        # (resourceType, filterDimensions)
│           ├── UiElements                   # (app, List<Element(key,visible)>)
│           └── InvalidationEvent            # bus payload (userId?, profile?, version, ts)
├── access-service/                          # the PDP (Spring Boot app)
│   └── .../service/
│       ├── AccessServiceApplication.java    # @SpringBootApplication @EnableAsync
│       ├── domain/                          # JPA entities + Spring Data repos
│       │   ├── Profile, Permission, ResourceType
│       │   ├── ProfileScope, Template, TemplateElement
│       │   └── *Repository.java
│       ├── rbac/                            # ★ pure, dependency-free core
│       │   ├── RbacResolver.java            # union/OR/visibility semantics (unit-tested)
│       │   ├── GrantLoader.java             # DB → ProfileGrants (one query, no N+1)
│       │   ├── ProfileGrants.java           # expanded grants for one profile
│       │   └── UiElementDef.java
│       ├── cache/                           # VersionService (Redis INCR), GrantCache (Redis L2)
│       ├── invalidation/                    # InvalidationPublisher (RabbitMQ)
│       ├── audit/                           # AuditService (async decisions, sync changes)
│       ├── api/                             # GrantService, AdminService, Decision+AdminController
│       ├── config/                          # RedisConfig, RabbitConfig
│       └── resources/
│           ├── application.yml
│           └── db/migration/                # V1__init.sql, V2__audit.sql (Flyway)
└── rbac-client-spring-boot-starter/         # the reusable "wheel" (PEP helper)
    └── .../client/
        ├── AccessClient.java                # ★ public interface a microservice codes against
        ├── DefaultAccessClient.java         # L1→L2→PDP + fail-closed + InvalidationHandler
        ├── RbacClientProperties.java        # @ConfigurationProperties "rbac.client"
        ├── cache/L1Cache.java               # Caffeine
        ├── cache/L2Cache.java               # Redis read-only
        ├── invalidation/InvalidationSubscriber.java  # RabbitMQ consumer (SmartLifecycle)
        ├── invalidation/InvalidationHandler.java
        ├── security/UserContextResolver.java        # host app resolves the current user
        ├── autoconfigure/RbacClientAutoConfiguration.java
        └── resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

**Stack:** Java 21, Spring Boot 3.2.5, PostgreSQL (record + audit), Redis (shared
L2 + version), RabbitMQ (invalidation bus), Caffeine (L1), Flyway, JUnit 5.

---

## 3. The accepted decisions (team-confirmed 2026-10-14 — see ADR-001 for the full rationale + trade-offs)

| # | Decision |
|---|----------|
| **D1** | Central **thin PDP** + shared Spring starter. **Rejected** a policy engine (OPA / Cerbos / Casbin / Keycloak / Oso / OpenFGA / Cedar / Palisade) for v1 — overkill for pure RBAC. |
| **D2** | **Client passes the profile set**; PDP is stateless about identity and trusts the (authenticated) caller. |
| **D3** | **Pure RBAC** v1. Escape hatch: add a SpEL `condition` column — no engine change. |
| **D4** | Templates = named bundles of UI-element **permission keys**; one permission-key mechanism for both features and UI; front-end owns rendering. |
| **D5** | **3-tier cache** — L1 Caffeine (60s) → L2 Redis (5–15min, PDP-owned, clients read directly) → L3 PostgreSQL. |
| **D6** | **RabbitMQ** invalidation bus (over Kafka, for easier ops) → near-instant revocation. Each client binds its **own non-durable, auto-delete queue** to the exchange (a shared queue would be consumed competitively). **Confirmed 2026-10-14** (Kafka's replay/durability/throughput not decisive for an ephemeral invalidation signal; reuse Kafka only if the platform already runs it). |
| **D7** | Permission keys: `<area>:<action>` (service) and `<area>.<element>` (UI); defined once in a central `permission` catalog. **Confirmed 2026-10-14:** hard contract = cacheable key → JSON payload; naming is a soft convention; **format enforcement deferred** (optional catalog lint/test later). |
| **D8** | Audit in PostgreSQL, **monthly-partitioned**; decision writes **async**, change writes **synchronous**. **Retention: rolling 3 months** (confirmed 2026-10-14); drop/archive partitions older than 3 months. |
| **D9** | One cached **`GrantSet`** (all perms + scopes + ui) keyed by `(userId, profileSet)`; the client derives check/scope/ui **locally** from it. |
| **D10** | Global **policy version** (Redis INCR) + push invalidation, for cheap client-side staleness checks. Bump + publish run **after the admin transaction commits**. **Confirmed 2026-10-14:** near-instant revocation while the bus is healthy; **TTL-bounded graceful degradation when the bus is down** (deliberate — don't amplify load during an outage). |

---

## 4. The runtime flow (what actually happens)

```
                          ┌─────────────────────────────────────────────┐
                          │                CLIENT (microservice)        │
   request ──► AccessClient.check/scope/ui(user, key)
                          │  1. L1 Caffeine  (60s TTL)                  │
                          │  2. L2 Redis     (read-only, PDP-owned)     │
                          │  3. PDP /v1/grants (REST, on miss)  ────────┼──► PDP
                          │  derive check/scope/ui LOCALLY             │      │
                          │  fail-closed: fault→deny/500; none→403/empty│      │
                          │  RabbitMQ subscriber: bump version, clear L1│◄─────┤ (publish InvalidationEvent on change)
                          └─────────────────────────────────────────────┘      │
   PDP (access-service):  /v1/grants ─► L2 hit? return : load(DB) → RbacResolver → L2 put ─► return GrantSet
                          admin write ─► save ─► audit.change(sync) ─► [AFTER COMMIT] version.bump() ─► publish(InvalidationEvent)
```

- **Decision hot path** (`check`/`scope`/`ui`) is *read-only* and cached; the PDP
  computes the full `GrantSet` once and caches it; the client does the final
  `contains`/lookup locally. That is what makes it cheap at 15k users.
- **Fail-closed is a hard invariant** (ADR §9.3): on a genuine fault, `check` → deny,
  `scope`/`ui` → **throw** (→ 500) so an unscoped query can never run. Lacking a grant
  is a **403, not a 500**: `scope` → `AccessDeniedException`; `ui` → empty bundle.
- **Staleness guard**: every `GrantSet` carries the policy `version` it was written
  with; the client tracks a local version (raised on invalidation events) and
  refetches when `entry.version < localVersion`. TTL is the safety net if the bus is down.

---

## 5. Public API (PDP)

Decision (called by clients / SDK):
- `POST /v1/check` — `{user:{userId,profiles}, key}` → `CheckResult{allowed, grantedBy, version}`
- `POST /v1/grants` — `{userId, profiles}` → full `GrantSet` (**the cached unit**)
- `GET  /v1/version` → `{version}` (cheap probe)

Admin (audited authoring; every one bumps version + publishes invalidation **after commit**):
- `POST   /v1/admin/profiles`
- `POST   /v1/admin/profiles/{name}/permissions`  ·  `DELETE .../permissions/{key}`
- `PUT    /v1/admin/profiles/{name}/scopes/{resourceType}`
- `POST   /v1/admin/templates`  ·  `POST /v1/admin/profiles/{name}/templates`
- `POST   /v1/admin/resource-types`

Client codes against `AccessClient` (starter), **not** the raw REST endpoints:
```java
accessClient.has(user, "orders:refund");                 // feature gate
Scope s = accessClient.scope(user, "orders");            // s.values("orgIds") -> List
UiElements ui = accessClient.ui(user, "customers-web");  // which tabs are visible
```

---

## 6. Data model (PostgreSQL, Flyway `V1`/`V2`)

`profile` · `permission` · `profile_permission` (M2M) · `resource_type` (owns the
dimension schema, JSONB) · `profile_scope` (profile × resourceType × **dimension
values as JSONB**) · `template` (app × name) · `template_element` (elementKey ×
permission) · `profile_template` (M2M).

`audit_decision` + `audit_change` — **RANGE-partitioned by month on `ts`** with a
`DEFAULT` partition. **Ops must roll forward partitions** before month-end (see the
placeholder `*_2026_10` partitions + the runbook note in V2).

> Note: `profile_scope.dimensions` is JSONB and **service-defined** — each
> microservice declares its own dimensions via `resource_type.dimension_schema`.
> The PDP does not interpret them; it only returns them to the owning service.

---

## 7. Build & run

```bash
cd rbac
mvn clean verify          # compiles all 4 modules, runs tests, packages (WORKS, see below)
# PDP:   mvn -pl access-service spring-boot:run   (needs Postgres, Redis, RabbitMQ)
```

- **Build status: GREEN.** `mvn clean verify` → BUILD SUCCESS;
  `Tests run: 11, Failures: 0, Errors: 0` (`RbacResolverTest` 6 + `AdminServiceTest` 5).
- The PDP won't *boot* without live Postgres/Redis/RabbitMQ (it validates schema,
  connects to the bus, etc.). That is expected; there is no embedded/fake setup yet.

---

## 8. Gotchas & conventions (learned the hard way — read before editing)

1. **`GrantSet` is the contract.** It's a plain record in dependency-free
   `rbac-model`. Both the PDP (writer) and the starter (reader) serialize/deserialize
   the *same* type. Don't fork it per side.
2. **Dimension values are `Object`** (in practice a `List`). `RbacResolver` merges
   them with unchecked casts (`@SuppressWarnings("unchecked")`) — that is *intended*
   (service-defined types). The compiler `-Xlint:unchecked` warning on
   `RbacResolver` is expected, not a bug.
3. **Shared names live in `rbac-model`**: `CacheKeys` (Redis key = `rbac:grants:{userId}:{sha256(sorted profiles)}`)
   and `Bus` (exchange `rbac.invalidation`, routing key `rbac.invalidated`).
   The PDP and starter **must** derive identical keys — never re-hardcode them in a module.
4. **`ObjectProvider` is `org.springframework.beans.factory.ObjectProvider`**, *not*
   `org.springframework.core`. (Cost a build failure once.)
5. **Starter optional-deps**: Redis and RabbitMQ are `<optional>` in the starter pom
   so a host app can run **L1-only**. The autoconfig guards each with
   `@ConditionalOnClass`/`@ConditionalOnBean`. If you touch the autoconfig, preserve
   that so a non-web / no-broker client still boots.
6. **`flyway-database-postgresql` is NOT in the Boot 3.2.5 BOM** → it is pinned to
   `10.10.0` inline in `access-service/pom.xml`. Keep it in sync with `flyway-core`.
7. **Cascade**: `Template.elements` is `@OneToMany(cascade = PERSIST)` and
   `AdminService.createTemplate` builds the elements *before* the single `save()`.
   Don't "simplify" that ordering or the template elements silently won't persist.
8. **`@Async` audit writes** require `@EnableAsync` (already on `AccessServiceApplication`).
9. **Audit failure never breaks the decision path** (`AuditService.decision` swallows
   + logs). Change-audit writes are *synchronous* by design (the mutation is the event).
10. **Not a git repo** — files on disk only. No commit/push state to preserve.
11. **Invalidation fires AFTER commit** — `InvalidationOnCommit` runs the version bump +
    `InvalidationEvent` publish via `@TransactionalEventListener(AFTER_COMMIT)`. A client
    refetching in the publish→commit gap can never read uncommitted grants stamped with the
    new version. Do not reorder this (bump/publish must stay post-commit). If Redis/RabbitMQ
    is down at that moment the invalidation is **lost** (the change IS committed): logged at
    ERROR, clients degrade to TTL-bounded staleness; the durable fix is a transactional outbox.
12. **403 vs 500** — a user who simply *lacks* a scope gets a 403 (`scope()` throws
    `AccessDeniedException`); `ui()` returns an empty `UiElements(app, List.of())`;
    `check()` denies per request. A genuine fault is a 500 — and is **never cached**
    (`GrantService.compute()` throws; the PDP does not cache errors or empty results).
13. **Broadcast, not competition** — each client binds its **own non-durable, auto-delete
    queue** (`QueueBuilder.nonDurable(name).autoDelete()`) to the durable topic exchange
    `Bus.EXCHANGE`. A single shared durable queue would be consumed competitively, and each
    event would reach only one replica.
14. **Scope-merge contract** — the per-dimension union only *widens* when every profile
    scoping a resource type declares the **same dimension set**; cross-dimension merges
    apply as an AND and can *shrink* access (documented in `RbacResolver` Javadoc, pinned by
    a test, and stated in ADR-001 under the assumptions). Enforced at authoring: `setScope`
    rejects dimensions that don't exactly match `resource_type.dimension_schema` (400);
    `registerResourceType` requires a non-empty schema.
15. **`setScope` upserts in place** — it must UPDATE the existing `profile_scope` row
    (mutate the managed entity), never delete+insert: Hibernate flushes INSERTs before
    DELETEs, so a fresh insert would violate `unique (profile_id, resource_type_id)`.
    Pinned by `AdminServiceTest`.

---

## 9. What's done vs. what's still open

**Done (compiles + tested):**
- All 3 modules build; PDP domain/core/cache/invalidation/audit/api/config are in place.
- Flyway migrations `V1` (schema) + `V2` (partitioned audit).
- Full client starter: `AccessClient`, `DefaultAccessClient`, L1/L2, invalidation
  subscriber, `UserContextResolver`, autoconfig + `AutoConfiguration.imports`.
- `RbacResolverTest` (6 green tests, incl. the cross-dimension scope-merge contract) pinning the resolution semantics.
- `AdminServiceTest` (5 green tests) pinning `setScope` in-place upsert (never delete+insert) and the 400 dimension-set validation.

**Intentionally NOT done (team review completed 2026-10-14 — see §10):**
- No live-infrastructure boot test (needs real Postgres/Redis/Rabbit or Testcontainers).
- No integration / contract tests between PDP and starter (L2 round-trip, invalidation round-trip).
- No admin *surface* beyond the JSON API (A5: API/config first — no UI in v1).
- No SpEL `condition` column (D3 escape hatch) — by design, not built.
- No metrics/SLO wiring beyond actuator defaults.
- Partition roll-forward runbook + retention jobs (ops; **3-month retention** confirmed).

---

## 10. Team questions (from ADR-001 §8) — **all answered 2026-10-14; ADR-001 marked Accepted**

1. **Trust model (D2)** — **Yes.** "Trust the authenticated caller" is acceptable; no
   IDM re-verification in the PDP.
2. **Revocation SLO (D6/D10)** — Near-instant while the bus is healthy; when the bus is
   **down**, **deliberately keep TTL-bounded staleness** (L1 60s / L2 ≤ 15 min) — do *not*
   accelerate invalidation (it would amplify load on the PDP during the outage). Stale-but-bounded
   = graceful degradation.
3. **Broker choice (D6)** — **Keep RabbitMQ.** Kafka's replay/durability/throughput benefits
   are not decisive for an ephemeral invalidation signal; reuse Kafka only if the platform
   already runs it (ops consolidation).
4. **Key convention (D7)** — **Not a technical issue.** Hard contract = cacheable
   **key → JSON payload** (unchanged); naming is a soft convention; **format/schema enforcement
   deferred** (optional catalog lint/test later).
5. **Scope model (A3)** — **Yes, with constraints** (one shared dimension set per resource
   type). **Where the schema lives:** authored as a **YAML manifest in the owning service's
   repo**, registered into the PDP (idempotent upsert); **Postgres stays the runtime source
   of truth** (the PDP does not read git directly).
6. **Audit retention (D8)** — **3 months** (rolling). Drop/archive partitions older than
   3 months (job + runbook).
7. **Admin surface (A5)** — **API-only for v1** (no admin UI).

**Standing instruction:** team feedback is incorporated and the design is adapted. ADR-001,
DESIGN.md, and this file are in agreement (all dated 2026-10-14). Proceed to the remaining
implementation items in §11.

---

## 11. Suggested next moves (in order)

1. ~~Resolve the 7 questions above with the team~~ — **done 2026-10-14** (ADR-001 + DESIGN.md updated, ADR marked Accepted).
2. Add **Testcontainers** integration tests: PDP boots against real Postgres/Redis,
   admin → version-bump → invalidation → client L1-eviction round-trip.
3. Add a **contract test** that the starter's L2 read + the PDP's L2 write use the
   same `CacheKeys` byte-for-byte (regression guard on gotcha #3).
4. Wire **Prometheus metrics** for cache hit-rate + decision latency (SLO evidence).
5. Write the **partition roll-forward + retention (3 months)** ops runbook.
6. Only then: the D3 SpEL escape hatch, if the team wants conditional grants.

---

*Last updated: 2026-10-14 (team review incorporated; ADR-001 marked Accepted). If you change
a decision (D1–D10), update the ADR decision-log first, then this file — keep the three
artifacts in agreement.*
