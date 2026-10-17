# AGENTS.md — User Access Service (RBAC) handoff

> **Purpose of this file:** a cold-start context pack for a *new* agent (or human)
> session so work can resume without re-deriving the design. Read this first, then
> `ADR-001-user-access-service.md` (the "why") and `DESIGN.md` (the "what").

---

## 0. STATUS — where things stand (2026-10-17, **GREEN — ADR-002 Accepted; Track A shipped (S1/T1/S2/R1, A1 descoped); 88 tests**)

**This session (2026-10-17) — Track A (ADR-002) implemented; `mvn clean verify` → 88 tests, 0 failures (access-service 72 + starter 16):**
- **T1 (template = catalog, `ed2d13e`):** `GrantLoader` no longer adds template-element
  permissions; new idempotent `DELETE /v1/admin/profiles/{name}/templates/{appId}/{name}`
  (detach, audited, invalidated). Pinned by `RbacResolverTest` + the `decisionFlow` IT
  round-trip (attach → listed-but-hidden; grant → visible; revoke → hidden again).
- **S2 (scopes fail-closed + canonical Option A, `2bd63ab`):** `Scope` =
  `{resourceType, filters:[{dimension, op, values}]}`, closed op set
  `IN/EQ/GT/GTE/LT/LTE/BETWEEN/ALL`, arity enforced in compact constructors (validated at
  Jackson (de)serialization → corrupt/stale values fail closed); `values(dim)`/`isAll(dim)`
  throw `UnknownDimensionException` (unknown dim can never mean "all rows"); `IN []` = deny,
  `ALL` = all-rows; `RbacResolver` OR-merges per dimension (mixed ops throw — loud 500,
  never cached); `AdminService.setScope` validates dim-set/ops against the schema (400) +
  `DELETE …/scopes/{rt}`. New `ScopeTest` (9, in access-service — rbac-model stays
  dependency-free). Gotchas #14/#26.
- **R1 + A1 (retention split + provenance; A1 descoped, `6f53a5b`):** `V4__grant_provenance.sql`
  adds `granted_at`/`granted_by` to `profile_permission`/`profile_scope`/`profile_template`
  (stamped by `AdminService` — explicit repo `flush()` before the update, for Hibernate join
  rows); `ops/rotate_audit_partitions.sql` + RUNBOOK split windows (decision 3 mo / change
  12 mo), verified on real PG16; A1 descoping recorded in DESIGN §8 (no PEP emitter, no new
  endpoint — raw `/v1/check` + inline `audit.decision` remain).
- **S1 (S2S auth + actor-from-principal, `12d9d8c`):** config-gated
  `rbac.pdp.auth.{enabled,mode,admin-authority,admin-principals}` (`enabled` defaults
  `false` + loud startup WARN — dev/tests unchanged). `mode=mesh`: `MeshAuthFilter` takes
  the SPIFFE ID from `Forwarded-Client-Cert`; `mode=jwt`: OAuth2 resource server (token
  `scope`/`scp` → **plain** authorities via prefix-less `JwtGrantedAuthoritiesConverter`).
  `/v1/admin/**` needs the admin authority (403 non-admin / 401 anonymous); actuator
  health/info + `/error` stay open. **Actor invariant:** `AuthPrincipal.current()` always
  uses the verified principal (SPIFFE ID / token `sub`) — `X-Actor` honored **only** in
  disabled/dev mode. Starter: `BearerTokenProvider` + `rbac.client.pdp-token` →
  `Authorization: Bearer …` on PDP calls. Pinned by `MeshAuthFilterTest` (5),
  `AuthPrincipalTest` (3), `AccessServiceAuthMeshTest` (4), `AccessServiceAuthJwtTest` (3,
  real RSA-signed tokens), `BearerTokenWiringTest` (5, incl. header-on-the-wire via a JDK
  `HttpServer` fake PDP). **New gotchas #28–#31** (security-chain wiring traps, the
  `/error` error-dispatch 401-overwrite, `JwtAuthenticationToken` principal = `Jwt` not
  `sub`, Boot `ManagementWebSecurityAutoConfiguration` vs excluded `SecurityAutoConfiguration`).
  DESIGN §4/§6/§10 + ADR-002 S1 note updated.
- **Next (per `REVIEW-implementation-vs-design.md` §4):** Track A is **done** → remaining
  **Track C** (fresh-replica L2 seed #6, subscriber durability #7, C5b stampede — Caffeine
  `get(key, loader)`) and **Track D** (profileSet-keyed cache, dedup, 404/409, JSON-escape,
  bounded `@Async`, catalog, `grantedBy` w/o per-check DB hit, test gaps). **(C6 outbox is
  DONE — `812b3a3`.)**

**Previously (2026-10-16) — implementation-vs-design review + hardening:**
- Ran a full **implementation-vs-design review**; every claim verified against source
  (file:line evidence) → `REVIEW-implementation-vs-design.md` (shareable).
- Drafted **`ADR-002-security-audit-and-semantics.md`** (Status: **Proposed**, pending team
  answers on §7 Q1–Q5). It records the 5 decisions the review forced: **S1** enforce S2S
  auth on the PDP + actor-from-principal (enforces D2's stated mitigation); **T1** template
  = layout catalog, not a grant-bundle (fixes `visible:false` unreachable + revoke no-op);
  **A1** decision audit moves to the PEP (resolves the D8↔D9 contradiction); **S2** scopes
  fail-closed (`values()` throws on unknown dim, `[]`=wildcard, add DELETE); **R1**
  change-audit retention ≥ 1 yr + `granted_at`. **These are gated — do not code until
  Accepted** (standing instruction: decision changes go to a new ADR first).
- **Shipped B2 (Track B, decision-agnostic, no ADR needed):** starter **L2 Redis isolation** —
  new `rbac.client.redis.{host,port,password,database}` config; when set, the starter uses a
  dedicated, self-managed connection (`cache/RbacL2Redis`, implements none of the Redis
  factory interfaces so it can't be confused with the host's); when unset, it falls back to the
  host's `StringRedisTemplate` (unchanged behavior). New `RbacClientRedisIsolationTest` proves
  the dedicated path is taken and the L2 template is **not** the host's, even when the host has
  its own Redis. DESIGN §7 documents the config + read-only ACL requirement.
- **Shipped Track C (availability hardening, all decision-agnostic):**
  - **C1 (Redis SPOF bypass, #5):** client `resolve()` now catches an L2 read failure and
    falls through to the PDP (records `rbac.client.cache{tier=l2,result=error}`) instead of
    failing the decision; PDP `GrantService.resolveGrants()` degrades to "compute from Postgres,
    uncached" when the version read / L2 read / L2 write fail (a floor version is stamped only
    when the real one is unreadable — the PDP is authoritative, so clients accept it). New
    `GrantServiceRedisDownTest` (2 tests, Mockito-simulated outage) + a client test.
  - **C4 (durable-queue leak, #8):** `RabbitConfig.pdpManagementQueue` is now bounded
    (`ttl=60s`, `maxLength=10k`) so the un-consumed queue can no longer grow without limit.
  - **C5a (timeouts, #10):** the starter's PDP `RestClient` now has configurable connect (2s) +
    read (5s) timeouts (`rbac.client.connect-timeout` / `.read-timeout`) so a hung PDP can't
    hold request threads. (C5's stampede protection — Caffeine `get(key, loader)` — still to do.)
  `mvn clean verify` → **BUILD SUCCESS** (access-service 18 tests, starter 11; total 29).
- **Track A (per `REVIEW-implementation-vs-design.md` §4):** **ADR-002 ACCEPTED** (all §7 answered) → **implemented 2026-10-17** (see the 2026-10-17 entry above): **S1** (mesh-mTLS primary / OAuth2 fallback / OIDC admin), **T1** (template = catalog), **S2** (scopes fail-closed + canonical **Option A**), **R1** (1-yr retention + `granted_at`/`granted_by`); **A1 DESCOPED** (audit = issuance + changes only — no PEP emitter). **(C6 outbox is DONE — `812b3a3`.)**
- **C6 transactional outbox — IMPLEMENTED (2026-10-16, committed `812b3a3`).** Built exactly
  to the design below; `mvn clean verify` → **BUILD SUCCESS** (access-service 31 tests,
  starter 11; total **42**):
  - **`V3__outbox.sql`** — `outbox_invalidation(id identity PK, created_at, published_at NULL,
    version, claimed_at)` + a partial index on the pending set.
  - **`OutboxService`** — `append()` (in-txn), `tryClaim()` (atomic `UPDATE…WHERE` lease, 30s
    expiry), `markPublished()`, `pending()` (the alarm), `pendingIds(limit)`.
  - **`InvalidationRelay`** — shared `process(rowId)` (claim→bump→publish→mark; swallows +
    records failures so a bad row can't wedge it) + `@Scheduled(fixedDelay=1s)` `poll()` (the
    durable fallback; body wrapped so a transient DB blip can't spam the log) +
    `forceInvalidate()` (ops escape hatch). Owns the new `rbac.pdp.outbox.*` metrics.
  - **Rewired** — `GrantChangeEvent` is now `record(long outboxId)` (no more `INSTANCE`);
    `AdminService` calls `publishInvalidation()` (→ `outbox.append()` in-txn + event) in all 7
    mutations; `InvalidationOnCommit` now just delegates to `relay.process(outboxId)`;
    `AccessServiceApplication` gains `@EnableScheduling`; `AdminController` gains
    `POST /v1/admin/invalidate`.
  - **Tests** — `OutboxServiceTest` (5, real-Postgres atomic claim under 8-way concurrency +
    lease expiry + pending tracking), `InvalidationRelayTest` (6, unit: happy path / lost claim /
    publish-failure & bump-failure leave the row pending / escape hatch), and 2 new ITs
    (`relayRecoversPendingRow_andPublishesOnBus`, `adminInvalidate_forcesBumpAndPublish`) +
    outbox assertions on the admin-change + metrics ITs.
  - **Design (as agreed, kept for reference):**
  1. **Core idea:** the outbox stores the **intent** ("a bump+publish is owed"), not the
     version — the version bump is a Redis `INCR` side-effect the **relay** performs. Atomicity
     comes from writing the outbox row in the **same Postgres txn** as the grant change, so
     "change committed ⇒ invalidation owed" survives Redis/RabbitMQ-down and crash-after-commit.
  2. **Table `V3__outbox.sql`:** `outbox_invalidation(id identity PK, created_at timestamptz
     default now(), published_at timestamptz NULL, version bigint NULL, claimed_at timestamptz)`.
     The `id` is the unit of work (no payload for v1 — global invalidation).
  3. **Write path:** each `AdminService.<mutation>` (already `@Transactional`, already writes the
     change-audit row in-txn) additionally calls `outbox.append()` **in-txn**, then
     `events.publishEvent(new GrantChangeEvent(outboxId))`. Change `GrantChangeEvent` from the
     stateless `INSTANCE` singleton to `GrantChangeEvent(long outboxId)`.
  4. **Shared `process(rowId)`** (used by fast path + relay), at-least-once safe:
     `if (!outbox.tryClaim(id)) return;  // UPDATE ... SET claimed_at=now() WHERE id=? AND
     published_at IS NULL AND (claimed_at IS NULL OR claimed_at < now()-'30s')`
     then `v = version.bump(); publisher.publish(InvalidationEvent.all(v)); outbox.markPublished(id, v);`
  5. **Fast path (keeps near-instant):** `InvalidationOnCommit`'s `AFTER_COMMIT` body becomes
     `relay.process(event.outboxId())` (best-effort). Healthy case behaves exactly as today.
  6. **Relay (durable fallback):** new `@Scheduled(fixedDelay=1s)` poller → claim a batch of
     unpublished rows, `process()` each. Add `@EnableScheduling` to `AccessServiceApplication`.
  - **Why at-least-once is OK (the subtle part):** a double-processed row double-bumps the
    version and double-publishes — both **harmless** (higher version ⇒ refetch, never
    stale-as-fresh; client `onEvent` takes `max(localVersion,v)` + clears L1, so dup = no-op).
    No exactly-once needed. Ordering preserved: version still bumped only **after** commit.
  - **Metric:** replace `rbac.pdp.invalidation.lost` (which admits loss) with a
    `rbac.pdp.outbox.pending` **gauge** (`count where published_at IS NULL` — the real alarm) +
    `outbox.published`/`outbox.failed` counters. Cross-replica: atomic claim ⇒ each row once;
    any replica can recover another's missed fast path.
  - **Ops escape hatch:** `POST /v1/admin/invalidate` → force an immediate global bump+publish
    (and/or re-drive the relay).
  - **Tests:** unit — `process()` claims+bumps+publishes+marks; 2nd `process(id)` no-ops; a
    publish-throw leaves `published_at NULL` (next poll retries). IT — commit a change with
    RabbitMQ **down** ⇒ row stays `published_at NULL`, `outbox.pending==1`; broker back/relay
    run ⇒ event lands, `pending`→0. IT — two relays race one row ⇒ version bumps once, event
    published once (harmless second time), row published.
  - **Deliberately NOT for v1:** Postgres `LISTEN/NOTIFY` (1s poll is fine — fast path covers
    the healthy case); Kafka transport (ADR D6 already chose RabbitMQ); exactly-once.
  - **Size:** `V3` + `OutboxService`/repo + `InvalidationRelay` + `process()` refactor +
    `/v1/admin/invalidate` ≈ 80–120 lines; touches `AdminService`, `InvalidationOnCommit`,
    new relay class, migration. Self-contained; no ADR-gated decision.
  - **Test note:** the design's "broker-down ⇒ row stays pending; broker back ⇒ relay
    recovers" is covered by (a) `InvalidationRelayTest.process_publishFailureLeavesRowPending`
    (unit, simulates the outage) + (b) `AccessServiceIntegrationTest.relayRecoversPendingRow`
    (real infra: a committed-but-unpublished row is recovered by the live relay and lands on the
    bus). The "two relays race one row" atomicity is pinned against a real Postgres by
    `OutboxServiceTest.tryClaim_concurrentCallers_exactlyOneWins` (8-way). A literal stop-the-
    broker-then-restart IT was deliberately avoided: Testcontainers re-maps ports on restart, so
    the `@ServiceConnection`-wired context would point at a dead port (gotcha-level flake).

**Previously (2026-10-15) — handoff items 1–5 done, committed:**
- `8bf6fa8` — observability metrics (former §11 item 4) with an
  **OpenTelemetry/OTLP backend** (team choice 2026-10-15: CNCF-adopted framework,
  obsolescence-proofing; Prometheus may stay a backend *behind* the collector),
  + tests + docs. `mvn clean verify` → **25 tests, 0 failures, BUILD SUCCESS**:

- `access-service` (16): `RbacResolverTest` 7 · `AdminServiceTest` 5 ·
  `AccessServiceIntegrationTest` 4 (incl. the new metrics test) — boots the full
  PDP against **real PostgreSQL 16 / Redis 7 / RabbitMQ 3.12**.
- `rbac-client-spring-boot-starter` (9): `DefaultAccessClientMetricsTest` 5 (new —
  L1/L2 hit-miss, decision latency by source, fetch success/failure, fail-closed,
  no-sink boot) + `InvalidationSubscriberIntegrationTest` 4 (incl. adapter
  auto-wired + event counter).

**What changed (metrics commit):**
- PDP: `micrometer-registry-otlp` dep; `GrantService` records `rbac.pdp.decision`
  (timer, per operation) + `rbac.pdp.cache` (L2 hit/miss); `InvalidationOnCommit`
  records `rbac.pdp.invalidation.published`/`.lost` (the "lost" counter = bus-outage
  alarm); `application.yml` has the `OTLP_METRICS_ENDPOINT` knob.
- Starter: new `RbacClientMetrics` sink interface (no Micrometer import, no-op
  default) + public `MicrometerRbacClientMetrics` adapter; auto-wired **only when a
  `MeterRegistry` bean exists** (`@ConditionalOnClass` + bean-factory lookup);
  `DefaultAccessClient` records `rbac.client.cache` (L1/L2 hit-miss),
  `rbac.client.decision` (timer tagged with serving source l1|l2|pdp|error),
  `rbac.client.pdp.fetch` (success/failure), `rbac.client.invalidation.events`.
  `micrometer-core` is **optional** — an L1-only host without an observability
  stack still boots (ADR D5).
**§11 item 5 done this session (committed `d4a6cb6`):** the
**partition roll-forward + 3-month retention ops runbook** (D8's "job + runbook"):
- `ops/rotate_audit_partitions.sql` — **idempotent** psql script: creates
  next month's `<parent>_<YYYY_MM>` partition for `audit_decision` +
  `audit_change` if missing, and drops monthly partitions older than the rolling
  3-month window (never touches `*_default`).
- `RUNBOOK-audit-partitions.md` — procedure, archive-before-drop option, cron
  example, monitoring/alert queries (default partition non-empty, missing
  next-month partition, inventory), failure-mode/recovery table.
- **Verified against real PostgreSQL 16.15** (ephemeral container): roll-forward
  + idempotent re-run + retention drop + out-of-range row routed to
  `audit_decision_default`. Note: the *first* retention "test pass" was a false
  positive — `docker exec` heredoc without `-i` feeds psql an empty stdin and
  exits 0 (gotcha #20); re-tested with guaranteed stdin (`docker exec -i`) and
  verified the stale partitions existed before the drop.
- `V2__audit.sql` header now points to the runbook + script (safe: V2 had no
  successful application anywhere — gotcha #18).
- **Next action:** §11 item 6 (D3 SpEL escape hatch — **gated on the team**
  wanting conditional grants; do not build unilaterally). Handoff items 1–5
  are complete and committed; the working tree is clean.

**Previously committed (HEAD `e09deae`):** Testcontainers integration tests
(green) + gotcha #22 fix, in two commits (`2ffc101`, `d1dca98`) + docs (`e09deae`).
Gotcha #22 was fixed in two places:
1. `VersionService.current()` reads the version **serializer-agnostic**
   (`Object` + `toString()`) — robust to whatever template a host context provides.
2. `RedisConfig` is now a **pure-String** `StringRedisTemplate` (the Jackson value
   serializer is gone). `GrantCache` already owned its Jackson serialization
   (`writeValueAsString`/`readValue`), so it becomes the L2 **wire format** —
   byte-identical to what the starter's `L2Cache` reads with Spring Boot's default
   (pure-String) template. Previously the Jackson value serializer **double-encoded**
   the value (a quoted JSON string), which would have broken every cross-module L2 read.

**Fixed & verified green across the three sessions** (each fix made the next layer
fail, in order):
1. `V2__audit.sql` partition syntax (gotcha #18) → Flyway applies V1+V2 on real PG 16.
2. `@JdbcTypeCode(SqlTypes.JSON)` on `ResourceType.dimensionSchema` /
   `ProfileScope.dimensions` (gotcha #19) → Hibernate validation passes, **context boots**.
3. `AuditService` drops the `ts` parameter — column `default now()` instead
   (gotcha #21) → admin API returns 201, `audit_change`/`audit_decision` rows land.
4. Gotcha #22 (version read + L2 wire format) → ITs green.
5. Metrics session: Boot-test-observability gotcha (#23) + Micrometer 1.15 API
   drift (#24) → **all 25 tests green**.

**Boot 3.5.16** (upgraded from 3.2.5) is deliberate — see gotchas #6 and #17.

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
├── pom.xml                                  # parent (Spring Boot 3.5.16, Java 21)
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

**Stack:** Java 21, Spring Boot 3.5.16, PostgreSQL (record + audit), Redis (shared
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
| **D8** | Audit in PostgreSQL, **monthly-partitioned**; decision writes **async**, change writes **synchronous**. **Retention (split, ADR-002 R1 — 2026-10-16):** `audit_decision` rolling **3 months** (the 2026-10-14 figure); `audit_change` rolling **12 months** (compliance floor). Live grant rows carry `granted_at`/`granted_by` (R1). |
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
- `PUT    /v1/admin/profiles/{name}/scopes/{resourceType}` — body = Option A filter list
- `DELETE /v1/admin/profiles/{name}/scopes/{resourceType}` — explicit scope revocation (ADR-002 S2)
- `POST   /v1/admin/templates`  ·  `POST /v1/admin/profiles/{name}/templates`
- `DELETE /v1/admin/profiles/{name}/templates/{appId}/{name}` — detach (ADR-002 T1)
- `POST   /v1/admin/resource-types`

Client codes against `AccessClient` (starter), **not** the raw REST endpoints:
```java
accessClient.has(user, "orders:refund");                 // feature gate
Scope s = accessClient.scope(user, "orders");            // Option A filters (ADR-002 S2)
for (Scope.Filter f : s.filters()) { /* apply per op: IN/EQ/GT/GTE/LT/LTE/BETWEEN/ALL */ }
// convenience: s.values("orgIds") (IN only; throws on unknown dim), s.isAll("regions")
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
> microservice declares its own dimensions via `resource_type.dimension_schema`
> (which may also allow-list ops per dimension: `"ops":["IN","ALL"]`).
> Since ADR-002 S2 it stores the canonical **Option A filter list**
> (`[{"dimension":"orgIds","op":"IN","values":[1,2]}, …]`); the PDP does not
> interpret the values — it returns them to the owning service, which applies them.

---

## 7. Build & run

```bash
cd rbac
mvn clean verify          # compiles all 4 modules, runs tests, packages (WORKS, see below)
# PDP:   mvn -pl access-service spring-boot:run   (needs Postgres, Redis, RabbitMQ)
```

- **Build status: GREEN (2026-10-17).** `mvn clean verify` → **88 tests, 0
  failures**: `access-service` 72 (`ScopeTest` 9 + `RbacResolverTest` 13 +
  `AdminServiceTest` 16 + `AccessServiceIntegrationTest` 6 + **S1 (2026-10-17):**
  `MeshAuthFilterTest` 5, `AuthPrincipalTest` 3, `AccessServiceAuthMeshTest` 4,
  `AccessServiceAuthJwtTest` 3** + `GrantServiceRedisDownTest` 2 + `OutboxServiceTest` 5
  + `InvalidationRelayTest` 6) and `rbac-client-spring-boot-starter` 16
  (`DefaultAccessClientMetricsTest` 6 + `RbacClientRedisIsolationTest` 1 +
  `InvalidationSubscriberIntegrationTest` 4 + **`BearerTokenWiringTest` 5 (S1)**).
- Integration tests boot the PDP against **real Postgres/Redis/RabbitMQ via
  Testcontainers** (`@Testcontainers(disabledWithoutDocker = true)` — they *skip*,
  not fail, without Docker; gotcha #17).

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
6. **Flyway is BOM-managed** — Boot 3.5.16 manages `flyway-core` +
   `flyway-database-postgresql` (11.7.2); no inline version pin needed. (On the old
   Boot 3.2.5 the postgres module was absent from the BOM and had to be pinned to
   `10.10.0` inline — that pin was removed with the upgrade.)
7. **Cascade**: `Template.elements` is `@OneToMany(cascade = PERSIST)` and
   `AdminService.createTemplate` builds the elements *before* the single `save()`.
   Don't "simplify" that ordering or the template elements silently won't persist.
8. **`@Async` audit writes** require `@EnableAsync` (already on `AccessServiceApplication`).
9. **Audit failure never breaks the decision path** (`AuditService.decision` swallows
   + logs). Change-audit writes are *synchronous* by design (the mutation is the event).
10. **Local-only git repo** — no remote configured. Commit locally after each green
    milestone; HEAD history is the only record of what changed.
11. **Invalidation fires AFTER commit, and is now durable (C6 outbox).** `AdminService`
    writes an `outbox_invalidation` row **in the same txn** as the grant change
    ("bump + publish is owed"); `InvalidationOnCommit` (AFTER_COMMIT) then runs the fast path
    `InvalidationRelay.process()` = version bump + `InvalidationEvent` publish. A client
    refetching in the publish→commit gap can never read uncommitted grants stamped with the
    new version — **do not reorder this (bump/publish must stay post-commit)**. If
    Redis/RabbitMQ is down at that moment the publish fails but the row stays **pending**
    (never lost): the `@Scheduled` relay re-drives it once the bus is reachable, and the
    `rbac.pdp.outbox.pending` gauge (should sit at 0) is the alarm. The claim is an atomic
    `UPDATE…WHERE` lease, so two replicas racing a row yield exactly one winner (at-least-once
    is safe: a duplicate is a no-op for clients). Ops escape hatch: `POST /v1/admin/invalidate`.
12. **403 vs 500** — a user who simply *lacks* a scope gets a 403 (`scope()` throws
    `AccessDeniedException`); `ui()` returns an empty `UiElements(app, List.of())`;
    `check()` denies per request. A genuine fault is a 500 — and is **never cached**
    (`GrantService.compute()` throws; the PDP does not cache errors or empty results).
13. **Broadcast, not competition** — each client binds its **own non-durable, auto-delete
    queue** (`QueueBuilder.nonDurable(name).autoDelete()`) to the durable topic exchange
    `Bus.EXCHANGE`. A single shared durable queue would be consumed competitively, and each
    event would reach only one replica.
14. **Scope-merge contract (Option A, ADR-002 S2)** — per dimension, profiles' filters are
    **OR-merged** (union of allowed sets: `IN` values union; `EQ`∪`EQ`=`EQ`/`IN`;
    `GT`/`GTE`=lowest bound; `LT`/`LTE`=highest; `BETWEEN`=covering interval; `ALL` wins);
    **mixed ops on one dimension fail closed** (throw at resolve — a loud 500, never cached).
    Cross-dimension merges apply as an AND and can *shrink* access — so authoring requires a
    shared dimension set per resource type (documented in `RbacResolver` Javadoc, pinned by
    tests, stated in ADR-001). Enforced at authoring: `setScope` rejects a dimension set, an
    op not allowed by the dimension's schema (`ops`, default `[IN, ALL]`), a duplicate
    dimension, or wrong arity (`IN` any / `EQ,GT,GTE,LT,LTE` one / `BETWEEN` two / `ALL` zero)
    — all 400; `registerResourceType` requires a non-empty schema.
15. **`setScope` upserts in place** — it must UPDATE the existing `profile_scope` row
    (mutate the managed entity), never delete+insert: Hibernate flushes INSERTs before
    DELETEs, so a fresh insert would violate `unique (profile_id, resource_type_id)`.
    Pinned by `AdminServiceTest`.
16. **Starter autoconfig ordering is load-bearing** — `RbacClientAutoConfiguration`
    uses `@ConditionalOnBean(StringRedisTemplate.class)` /
    `@ConditionalOnBean(ConnectionFactory.class)`, which are evaluated when ITS bean
    definitions are registered. It therefore declares
    `@AutoConfiguration(after = {RedisAutoConfiguration.class, RabbitAutoConfiguration.class})`.
    Remove that and the L2 cache / invalidation subscriber **silently vanish** depending
    on classpath order (the client runs L1-only / never gets invalidation). Pinned by
    `InvalidationSubscriberIntegrationTest` (asserts both beans exist with real infra).
17. **Testcontainers must speak the local Docker daemon's minimum API** — Docker
    Engine 29.x rejects API < 1.40 (older clients get a bare 400; Testcontainers then
    reports "Could not find a valid Docker environment" and *skips* the tests).
    Testcontainers 1.19.x (docker-java 3.3.x) sends API 1.32 → broken; **1.21.4+
    (docker-java 3.4.x) works**. Boot 3.5.16's BOM manages 1.21.4 natively — that is a
    key reason the stack was upgraded from Boot 3.2.5 (whose BOM managed 1.19.7).
    Integration tests use `@Testcontainers(disabledWithoutDocker = true)` so a
    no-Docker environment skips (not fails) them.
18. **PostgreSQL partition syntax** — `CREATE TABLE x (cols...) PARTITION OF parent`
    (including `... (LIKE parent INCLUDING IDENTITY) PARTITION OF parent`) is a
    **syntax error at "of"** in vanilla PostgreSQL (verified on 16.15). A partition
    inherits ALL columns, identity and constraints from the parent automatically; the
    only valid form is `create table <name> partition of <parent> [for values ... | default];`.
    `V2__audit.sql` was written with the invalid form and **never ran until the
    Testcontainers ITs booted a real DB** — it is now fixed in place (safe: V2 had no
    successful application anywhere, so no checksum concern).
19. **`jsonb` columns need `@JdbcTypeCode(SqlTypes.JSON)` on a `String` field** —
    plain `String` fields are validated as `varchar(255)` and Hibernate schema
    *validation* fails at boot: "wrong column type ... found [jsonb], but expecting
    [varchar(255)]". Fixed on `ResourceType.dimensionSchema` and
    `ProfileScope.dimensions` (the latter also dropped its dead `columnDefinition="jsonb"`,
    which is ignored in validate mode anyway). Only caught because the PDP had never
    booted against a real DB before the ITs. Audit writes use raw JDBC (`?::jsonb`)
    and are unaffected.
20. **Probing containers: `docker exec` without `-i` gives psql an EMPTY stdin** —
    it executes nothing and exits 0, which produced false "it works" results with
    heredocs. Always use `psql -c "..."` (single statement) or `docker exec -i`.
    Related zsh trap: `P="docker exec ..."; $P -c ...` fails ("command not found") —
    zsh does not word-split unquoted variables.
21. **PG JDBC cannot bind `java.time.Instant` via `setObject` without an explicit
    SQL type** — it throws "Can't infer the SQL type to use for an instance of
    java.time.Instant". A SQL-text cast (`?::timestamptz`) does **NOT** help: the
    check is client-side, driven by the Java object type, not the SQL. The raw JDBC
    audit inserts therefore **omit `ts` entirely** and rely on the column
    `default now()` — which is also the right design (the DB clock is the source of
    truth for the partition key). If you ever must bind a timestamp: use
    `java.sql.Timestamp`, never `Instant`. Note the `decision` insert is `@Async`
    and swallows errors — a bind failure there meant audit rows were **silently
    dropped** (log-only). Pinned by `AccessServiceIntegrationTest` (asserts both
    `audit_change` and `audit_decision` rows land).
22. **`RedisConfig` gives `StringRedisTemplate` a Jackson VALUE serializer** — so
    `opsForValue().get()` deserializes with Jackson: a value written by raw Redis
    commands (`INCR` for the policy version) comes back as whatever JSON type the
    bytes imply (`"1"` → `Integer`), and `String v = get()` → **ClassCastException**.
    `increment()` bypasses serializers entirely. Any code reading a key that may hold
    a non-JSON-object value (the version!) must be serializer-agnostic: read as
    `Object` + `toString()`. This broke `VersionService.current()` (→ 3 IT failures,
    incl. the PDP's `/v1/grants` 500, which stamps the version). **Fixed 2026-10-15**
    (see §0): `VersionService` reads serializer-agnostic, and `RedisConfig` is now a
    pure-String template so `GrantCache`'s own Jackson serialization is the L2 wire
    format the starter's `L2Cache` expects (the Jackson value serializer would have
    double-encoded every L2 value and broken cross-module reads).
23. **Boot's test policy disables metrics export in `@SpringBootTest`** — the
    `DisableObservabilityContextCustomizer` (spring-boot-test-autoconfigure) sets
    `management.defaults.metrics.export.enabled=false` via a `TestPropertyValues`
    source added with **higher priority than `@SpringBootTest(properties=...)`**,
    so tests get a `SimpleMeterRegistry` even with `micrometer-registry-otlp` on
    the classpath (verified via the condition report:
    `OnMetricsExportEnabledCondition => false`). Don't fight it: it's Boot
    deliberately keeping tests from pushing to a collector. Test the *meters* on
    whatever `MeterRegistry` is injected (backend-agnostic — that's what the app
    code does) and assert the OTLP registry class is on the classpath
    (`Class.forName`), not the test-environment registry type. Opting out via
    `spring.test.observability.auto-configure=false` inline did **not** work either
    (the customizer reads the flag before inline properties are applied).
24. **Micrometer 1.15 API drift (Boot 3.5 BOM)** — (a) `Timer.count()` returns
    **`long`**, not `double` (AssertJ `isEqualTo(1.0)` on a long fails at runtime —
    use `1L`); `Counter.count()` is still `double`. (b) `registry.get(name)` returns
    a `RequiredSearch` whose `.counter()`/`.timer()` **throw `MeterNotFoundException`**
    for absent meters — use the lenient `registry.find(name).tags(...).counters()` /
    `.timers()` (collections) when the meter may not exist yet (e.g. asserting a
    "miss" count that is zero). (c) Spring 6 `ClientHttpRequest`/`ClientHttpResponse`
    stubs must implement the abstract `getAttributes()`/`getHeaders()`/`close()`,
    and `HttpRequest.getAttributes()` must return a **mutable** map (RestClient
    mutates it — `Map.of()` throws `UnsupportedOperationException`). (d)
    `StringRedisTemplate.opsForValue()` in spring-data-redis 3.5 is **non-generic**
    (`ValueOperations<K,V>`), so overrides must match exactly.
25. **Never register a `LettuceConnectionFactory` (or any `RedisConnectionFactory`) as a
    bean from the starter when the host may have its own Redis** — a
    `LettuceConnectionFactory` also implements `ReactiveRedisConnectionFactory`, so a host's
    `RedisReactiveAutoConfiguration.reactiveRedisTemplate` (which expects a **single**
    `ReactiveRedisConnectionFactory`) breaks with `NoUniqueBeanDefinitionException:
    found 2: redisConnectionFactory, rbacL2ConnectionFactory`, and any host bean injecting
    `RedisConnectionFactory` by type becomes ambiguous. This is how B2 (L2 Redis isolation,
    2026-10-16) was first built and first failed. **Fix:** wrap the private connection in a
    distinct type (`cache/RbacL2Redis`) that implements **none** of the Redis factory
    interfaces (only `DisposableBean`), holds its own `LettuceConnectionFactory` +
    `StringRedisTemplate`, and is the only thing the `L2Cache` reads from. The L2 template is
    built **locally** (not a bean), so it can't be autowired by the host either. Pinned by
    `RbacClientRedisIsolationTest` (asserts the dedicated path is taken and the L2 template is
    not the host's `stringRedisTemplate`, even with the host's Redis auto-config active).
    Related trap while debugging: excluding `RedisAutoConfiguration` to remove the host's
    template also removed the `redisTemplate` bean that Spring Data Redis's
    `redisReferenceResolver` needs → context fails to load. Keep the host auto-config active
    in the test and prove isolation by *identity*, not by absence.
26. **The `Scope` wire format is Option A now (ADR-002 S2) — a deliberate, test-visible break.**
    `Scope` is `{resourceType, filters:[{dimension, op, values}]}` with the closed op set
    `IN/EQ/GT/GTE/LT/LTE/BETWEEN/ALL`. `IN []` = **deny** (not a wildcard); `ALL` = explicit
    all-rows; `values(dim)`/`isAll(dim)` **throw** `UnknownDimensionException` for a missing
    dimension (the old `[] = no filter = all rows` fail-open is gone). Op arity is enforced in
    the record constructors, which also **validate at Jackson (de)serialization** — so a corrupt
    PDP response or a stale L2 value fails closed (error, never a half-parsed scope) instead of
    reaching a query. `profile_scope.dimensions` (jsonb) now stores the filter list; no
    migration needed (service-defined blob). Old-format L2 entries written before the upgrade
    self-heal: the client's L2 read error falls through to the PDP (C1) and the TTL/version
    bump expires them. Do not reintroduce a map-shaped `filters` or a `values()` that returns
    `List.of()` for an unknown dimension.
27. **Templates are a catalog, not a grant-bundle (ADR-002 T1).** `GrantLoader` must NOT add
    template-element permissions to `permissionKeys` — that was the #4 bug (it made
    `visible:false` unreachable and revocation a silent no-op). Attach/detach
    (`POST`/`DELETE /v1/admin/profiles/{name}/templates/...`) only move UI elements; the
    element's permission is granted/revoked via the normal permission endpoints, and
    `RbacResolver.computeUi` stays `visible = permissions.contains(gating)`. Pinned by
    `RbacResolverTest` + the IT `decisionFlow` round-trip (attach → hidden; grant → visible;
    revoke → hidden again).
28. **S1 (ADR-002): never exclude `SecurityAutoConfiguration`.** The actuator's
    `ManagementWebSecurityAutoConfiguration` is `@ConditionalOnDefaultWebSecurity` (i.e. it
    activates exactly when **no** `SecurityFilterChain` bean exists) and needs the
    `HttpSecurity` bean that `SecurityAutoConfiguration` provides — excluding it makes the
    context fail to load ("No qualifying bean of type HttpSecurity") in disabled mode.
    The correct pattern: `SecurityAutoConfiguration` stays active, and the app **always**
    defines a `SecurityFilterChain` — the secure chain when `rbac.pdp.auth.enabled=true`,
    an explicit open (`permitAll`) chain when disabled — so both the default form-login
    chain and the management chain back off. Keep `UserDetailsServiceAutoConfiguration`
    excluded (kills the generated-user + "Using generated security password" log noise).
    Also: `AnonymousAuthenticationToken` **requires a non-empty authority list** — use
    `AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")` (Spring's own
    `AnonymousAuthenticationFilter` does the same); `List.of()` throws
    "authorities cannot be null or empty".
29. **S1 (ADR-002): `res.sendError(401/403)` triggers a servlet ERROR dispatch to `/error`
    that re-enters the security chain WITHOUT the original identity.** The synthetic
    `/error` request has no `Forwarded-Client-Cert`/`Authorization`, so the auth filter
    re-authenticates it as anonymous, and since `/error` wasn't `permitAll` the chain's own
    401 **overrode the intended 403** (diagnosed with a filter after
    `ExceptionTranslationFilter`: `POST /error ctxAuth=AnonymousAuthenticationToken`). Fix:
    permit `/error` (with actuator health/info) in the chain so the original error status
    renders unchanged. If you add new error statuses, keep `/error` open.
30. **S1 (ADR-002): `JwtAuthenticationToken.getPrincipal()` is the `Jwt` object, NOT the
    `sub` string** (unlike `UsernamePasswordAuthenticationToken` where the principal is the
    name). `AuthPrincipal.principalName()` must handle both: `String` (mesh SPIFFE ID) and
    `Jwt` → `jwt.getSubject()`. Related: Spring's default `JwtGrantedAuthoritiesConverter`
    maps `scope` claim values to `SCOPE_<value>` — the admin matcher wants the plain
    authority, so `RbacSecurityConfig` wires a converter with `setAuthorityPrefix("")`
    (`scope=rbac-admin` → authority `rbac-admin`). Both packages are
    `org.springframework.security.oauth2.server.resource.authentication` (converter) — NOT
    `org.springframework.security.oauth2.jwt`.
31. **S1 test/tooling API drift.** (a) nimbus-jose-jwt **9.x**: no static
    `JWTClaimsSet.builder()` (use `new JWTClaimsSet.Builder()`); it's
    `RSAKey.toRSAPrivateKey()`/`toRSAPublicKey()` (not `toPrivateRSAKey()`); and
    `RSAKeyGenerator.generate()`, `RSAKey.toRSA*()`, `SignedJWT.sign()` all throw the
    **checked** `JOSEException` (wrap in `IllegalStateException` in static init/helpers).
    (b) `RestClient.Builder` has **no `defaultRequest(RequestCustomizer)`** overload — to
    set a header on every request use `requestInterceptor(ClientHttpRequestInterceptor)`
    (3-arg: `(request, body, execution)`; `request.getHeaders().set(...)` then
    `execution.execute(request, body)`). (c) In `ApplicationContextRunner` tests the
    autoconfig needs `JacksonAutoConfiguration` added (no Boot web context to provide the
    `ObjectMapper` bean).

---

## 9. What's done vs. what's still open

**Done (compiles + tested):**
- All 3 modules build; PDP domain/core/cache/invalidation/audit/api/config are in place.
- Flyway migrations `V1` (schema) + `V2` (partitioned audit) — **V2 verified applying on
  real PostgreSQL 16** (was invalid syntax, gotcha #18; now fixed and run by the ITs).
- Full client starter: `AccessClient`, `DefaultAccessClient`, L1/L2, invalidation
  subscriber, `UserContextResolver`, autoconfig + `AutoConfiguration.imports`.
- `RbacResolverTest` (7 green tests, incl. the cross-dimension scope-merge contract and
  the all-apps UI resolution) pinning the resolution semantics.
- `AdminServiceTest` (5 green tests) pinning `setScope` in-place upsert (never delete+insert) and the 400 dimension-set validation.
- **Testcontainers integration tests GREEN (2026-10-15)** — `AccessServiceIntegrationTest`
  (boot/migrations/partitions · admin→bump→invalidation→change-audit · full decision
  flow + L2 key + revocation · metrics) and `InvalidationSubscriberIntegrationTest`
  (autoconfig beans · bus→L1 eviction · L2 shared-`CacheKeys` contract · metrics
  adapter + event counter). 25 tests pass; gotcha #22 fixed (serializer-agnostic
  version read + pure-String `StringRedisTemplate`, which also fixed the L2 wire
  format for the starter).
- **Observability metrics GREEN (2026-10-15, committed `8bf6fa8`)** — OTLP/OpenTelemetry
  backend (team choice over plain Prometheus registry for CNCF alignment). PDP:
  `rbac.pdp.decision` / `rbac.pdp.cache` / `rbac.pdp.invalidation.{published,lost}`.
  Starter: `rbac.client.{cache,decision,pdp.fetch,invalidation.events}` via the
  no-op-by-default `RbacClientMetrics` sink (Micrometer optional). Pinned by
  `AccessServiceIntegrationTest.metrics_…` + `DefaultAccessClientMetricsTest`
  (5 tests) + the starter IT adapter assertions.
- **Partition roll-forward + retention ops (2026-10-15, committed `d4a6cb6`; split
  windows per R1 on 2026-10-17)** —
  `ops/rotate_audit_partitions.sql` (idempotent: create next month's partition,
  drop per-table on the rolling window — `audit_decision` 3 months, `audit_change`
  12 months — never touches `*_default`)
  + `RUNBOOK-audit-partitions.md` (procedure, archive option, cron, monitoring
  queries, failure-mode recovery). Verified on real PostgreSQL 16.15
  (roll-forward, retention, idempotency, default-partition routing). Fulfills
  D8's "job + runbook"; `V2__audit.sql` header now points to them.
- **Track A (ADR-002) — DONE (2026-10-17):**
  - **T1 (committed `ed2d13e`)** — template = layout catalog: `GrantLoader` fixed,
    idempotent detach endpoint, `decisionFlow` IT pins the attach/grant/revoke round-trip.
  - **S2 (committed `2bd63ab`)** — scopes fail-closed, canonical Option A: new `Scope`
    wire format + `UnknownDimensionException`, op-set/arity validation (also at Jackson
    (de)serialization), OR-merge contract in `RbacResolver`, schema validation + `DELETE
    …/scopes/{rt}` in `AdminService`, new `ScopeTest` (9).
  - **R1 + A1 (committed `6f53a5b`)** — `V4__grant_provenance.sql`
    (`granted_at`/`granted_by` on the 3 live grant tables, stamped in-txn), split
    retention windows in the ops script + runbook; **A1 descoped** (no PEP emitter /
    no new endpoint; recorded in DESIGN §8).
  - **S1 (committed `12d9d8c`)** — S2S auth on the PDP:
    `security/{CallerIdentity, AuthPrincipal, MeshAuthFilter, RbacSecurityConfig,
    RbacAuthDisabledWarning}`; `rbac.pdp.auth.{enabled,mode,admin-authority,admin-principals}`;
    actor-always-from-verified-principal in `AdminController`/`DecisionController`;
    starter `security/BearerTokenProvider` + `rbac.client.pdp-token` + `requestInterceptor`
    wiring; **20 new tests** (access-service 15 = 8 unit + 7 IT incl. two full-PDP
    security ITs; starter 5 `BearerTokenWiringTest`). Gotchas #28–#31. 88 tests green.

**Intentionally NOT done (team review completed 2026-10-14 — see §10):**
- No admin *surface* beyond the JSON API (A5: API/config first — no UI in v1).
- No SpEL `condition` column (D3 escape hatch) — by design, not built.
- No SLO dashboards/alerts configured on the platform side (the *meters* exist —
  see the metrics bullet above; the collector→dashboard wiring is a platform job).
- No audit archiving by default (team decision: **drop, no archive**, 3-month
  rolling — the runbook documents the archive option if compliance asks).

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
2. **DONE (2026-10-15)** — Testcontainers integration tests green:
   `AccessServiceIntegrationTest` (3) + `InvalidationSubscriberIntegrationTest` (3);
   build fully green (18 tests), committed.
3. **DONE (2026-10-15)** — the `CacheKeys` contract test
   (`l2ReadUsesTheSharedCacheKeyScheme` in the starter IT) is green.
4. **DONE (2026-10-15, committed `8bf6fa8`)** — metrics for cache hit-rate +
   decision latency (SLO evidence), **OpenTelemetry/OTLP backend** (team choice
   2026-10-15 over a plain Prometheus registry: CNCF-adopted,
   obsolescence-proofing). PDP + starter instrumented, 9 new tests green (25
   total). See §0 for the exact meter names; gotchas #23/#24 capture the
   Boot-test + Micrometer 1.15 traps.
5. **DONE (2026-10-15, committed `d4a6cb6`; split windows per R1 2026-10-17)** —
   **partition roll-forward + retention** ops runbook: `ops/rotate_audit_partitions.sql`
   (idempotent script) + `RUNBOOK-audit-partitions.md`, verified on real PG 16 (see §0).
6. **DONE (2026-10-17)** — **Track A (ADR-002): S1/T1/S2/R1 implemented, A1 descoped**
   (`ed2d13e`, `2bd63ab`, `6f53a5b` + this session's S1 commit; see §0/§9).
7. **NEXT — remaining Track C:** fresh-replica L2 seed (#6), subscriber durability
   (#7, survive broker restart), C5b stampede protection (Caffeine `get(key, loader)`
   so concurrent misses fetch once).
8. **THEN — Track D** (polish, per `REVIEW-implementation-vs-design.md`): profileSet-keyed
   cache key, decision dedup, 404/409 semantics, JSON-escaping, bounded `@Async`,
   permission-key catalog/lint, `grantedBy` without a per-check DB hit, remaining test gaps.
9. Only then — and **only if the team wants it**: the D3 SpEL escape hatch
   (conditional grants). Gated on a team decision; do not build unilaterally.

---

*Last updated: 2026-10-17 (**TRACK A (ADR-002) SHIPPED** — S1/T1/S2/R1 implemented, A1 descoped;
`mvn clean verify` → **88 tests, 0 failures** (access-service 72 + starter 16). Commits: T1
`ed2d13e`, S2 `2bd63ab`, R1+A1 `6f53a5b`, S1 `12d9d8c`. New gotchas
#28–#31 (S1 security-chain wiring). ADR-002 marked implemented per decision; DESIGN §1/§2/§4/§6/
§10/§12 + this file in agreement. **Remaining:** Track C #6/#7/C5b, Track D. Prior day 2026-10-16:
**ADR-002 ACCEPTED** — team answered all §7: Q1 S1 = K8s mesh-mTLS primary / OAuth2
client-credentials fallback / OIDC admin; Q2 T1 = catalog; **Q3 A1 = DESCOPED** (audit = issuance
+ changes only, no PEP emitter — overrides the (a) recommendation); Q4 S2 = fail-closed + canonical
**Option A**; Q5 R1 = 1-yr `audit_change` retention + `granted_at`/`granted_by`.
Also same day (2026-10-16): **C6 transactional outbox IMPLEMENTED** (`V3__outbox.sql` + `OutboxService` +
`InvalidationRelay` (shared `process` + `@Scheduled` relay + `POST /v1/admin/invalidate`),
`GrantChangeEvent(long outboxId)`, `AdminService` append-in-txn, new `rbac.pdp.outbox.{pending
gauge,published,failed}` meters; `OutboxServiceTest` 5 + `InvalidationRelayTest` 6 + 2 new ITs;
`mvn clean verify` GREEN — access-service 31 + starter 11 = **42 tests, 0 failures**; DESIGN §7
consistency + metrics updated; committed `812b3a3`). Prior same day: **implementation-vs-design
review done** → `REVIEW-implementation-vs-design.md`; **shipped:** B2 L2 Redis isolation,
Track C — C1 Redis-SPOF bypass, C4 bounded `pdpManagementQueue`, C5a client timeouts; gotcha
#25 added (commits `689f28a`, `d7c0d85`). **Remaining:** Track A (S1/T1/S2/R1), Track C #6/#7/
C5b, Track D. Prior: 2026-10-15 (integration tests GREEN + committed; **OTel/OTLP metrics
committed `8bf6fa8`**; **partition roll-forward + 3-month retention runbook + idempotent script
committed `d4a6cb6`** (PG-16-verified); handoff items 1–5 complete + committed, item 6 (SpEL
escape hatch) gated on the team; team review incorporated 2026-10-14, ADR-001 marked Accepted;
metrics backend chosen 2026-10-15).
If you change a decision (D1–D10), update the ADR decision-log first, then this
file — keep the three artifacts in agreement.*
