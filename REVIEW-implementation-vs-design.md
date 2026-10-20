# Review — User Access Service (RBAC), implementation vs. design

| | |
|---|---|
| **Date** | 2026-10-16 |
| **Scope** | `access-service` (PDP), `rbac-client-spring-boot-starter`, `rbac-model`, `DESIGN.md`, `ADR-001` |
| **Method** | Every claim below was **verified against source** (file:line evidence in §2). Not taken on faith. |
| **Companion** | Decisions this review forces → **`ADR-002-security-audit-and-semantics.md`**. This page is the *evidence + plan*; the ADR is the *decision gate*. |

---

## 1. Overall verdict

The architecture holds up. A thin PDP, one cached `GrantSet`, a global version counter with push invalidation, and fail-closed client semantics is a reasonable, well-documented design for pure RBAC at 15k users. Choosing not to use OPA/Cerbos for v1 is defensible. The ADR is well written (every decision names its alternatives and its reconsider trigger), and the delivery discipline is good — integration tests run against real infrastructure and pitfalls are recorded as they're found.

The problems are in a few places where **the design contradicts itself** or **the code doesn't do what the docs claim**. Several weaken the security story, which is the reason this service exists. In priority order:

- 🔴 **Critical (break stated objectives):** #1 no auth on the PDP, #2 decision audit is empty, #3 scopes can fail open, #4 `visible:false` is unreachable + revocation is a no-op.
- 🟠 **High (availability & correctness):** #5 Redis is a hard SPOF, #6 fresh replicas trust stale L2, #7 the subscriber doesn't survive a broker restart, #8 a durable queue fills forever, #9 the starter reuses the host's Redis, #10 no timeouts / no stampede protection.
- 🟡 **Design improvements (state of the art):** profileSet-keyed cache, transactional outbox *now*, explicit permission catalog, split audit retention, schema-typed scope values, plus a list of small bugs.

What's solid (kept): the fail-closed semantics and the 403-vs-500 split, implemented consistently; bump+publish **after commit**; reading the version *before* loading from the DB (a race only causes an extra refetch, never stale data stamped fresh); shared contracts (`CacheKeys`, `Bus`, `GrantSet`) in a dependency-free module with a cross-module key test; `setScope` in-place upsert + authoring-time dimension validation; the starter's optional dependencies and auto-config ordering; and unusually good docs.

---

## 2. Verified findings (with evidence)

Legend: ✅ = reproduced in source · 🟥 critical · 🟧 high · 🟨 design/small.

### 🔴 Critical

**#1 — The PDP has no authentication; the admin API trusts anyone.** 🟥 ✅
No `spring-security` (or OAuth2) dependency anywhere (`grep` across `*.java`/`*.xml`: 0 matches). `AdminController` reads the actor from a header with a default:
- `access-service/.../api/AdminController.java:25` — `@RequestHeader(value = "X-Actor", defaultValue = "unknown")`.
- That value is passed straight to `audit.change(actor, …)` (`AdminService.java:66,80,…`).
ADR-001 D2's mitigation is *"the PDP sits behind service-to-service auth"* — **stated, not enforced**. Anyone who can reach the port can `POST /v1/admin/profiles/{name}/permissions` and grant themselves anything; the audit records whatever `X-Actor` they send (default `"unknown"`). This breaks "audit trail is mandatory" *and* the D2 trust model.
→ **Decision S1** (ADR-002): enforce S2S auth, separate admin authZ, actor from the verified principal.

**#2 — Decision audit is effectively empty: D8 and D9 contradict each other.** 🟥 ✅
- `DefaultAccessClient` only ever calls `/v1/grants` (`DefaultAccessClient.java:172`) and derives `check` locally (`DefaultAccessClient.java:63-77`). It **never** calls `/v1/check`.
- `audit.decision` — the **only** writer of `audit_decision` — is invoked **only** in `GrantService.check()` (`GrantService.java:77,82`), reachable solely via the raw `/v1/check` REST.
So on the production (starter) path, `audit_decision` never fills. D8 says "every check is audited"; D9 says the client computes the check locally. Direct contradiction.
→ **Decision A1** (ADR-002): move decision audit to the PEP (batched), or explicitly descoped.

**#3 — Scope API + the documented pattern can fail open.** 🟥 ✅
- `rbac-model/.../dto/Scope.java:16` — a missing **or misspelled** dimension returns `List.of()`.
- `DESIGN.md:312-313` — `if (!orgIds.isEmpty()) b.and(order.orgId.in(orgIds));` → an empty list means **no filter = all rows**.
Three paths to "all rows": (a) the dimension authored as `[]` — also the *only* way to "revoke" a scope, since there is **no DELETE scope endpoint** (`AdminController` has only `PUT …/scopes/{rt}`); (b) the client misspells the dimension; (c) the schema later gains a dimension older profiles lack.
→ **Decision S2** (ADR-002): `values()` throws on unknown dim; `[]` = explicit wildcard; add `DELETE …/scopes/{rt}`; fix the `DESIGN §6` example.

**#4 — `visible:false` can never happen; revoking a permission is a silent no-op.** 🟥 ✅
- `GrantLoader.java:67-73` — `toGrants` adds **every template element's permission** to `permKeys`.
- `RbacResolver.java:87` — `computeUi` checks `visible = permissions.contains(def.permissionKey())` — **always true** because the loader just added it.
Consequences: `DELETE /v1/admin/profiles/{name}/permissions/{key}` silently does nothing if any attached template contains that key; and there is **no detach-template endpoint** (attach only). The design's own example (`DESIGN.md:325`, `{tab.payments:false}`) and the test `RbacResolverTest.uiElementIsVisibleOnlyIfItsGatingPermissionIsGranted` (`RbacResolverTest.java:83-95`) assume `visible:false` is possible — they only pass because the test hand-builds a `ProfileGrants` the loader can never produce.
→ **Decision T1** (ADR-002): template = layout catalog (attaching does *not* grant); add detach; fix the loader.

### 🟠 High

**#5 — A Redis outage takes down authorization, even though Redis is "just a cache."** 🟧 ✅
- PDP: `GrantService.resolveGrants` calls `version.current()` (`GrantService.java:136`, `VersionService.java:24`) and `cache.put()` (`GrantService.java:146`, `GrantCache.java:45`) — both **throw** on Redis down → `/v1/grants` 500s even with healthy Postgres.
- Client: `DefaultAccessClient.resolve` calls `l2.get()` **uncaught** (`DefaultAccessClient.java:149`; `L2Cache.java:26`). For `check` this is caught→deny; for `scope`/`ui`/`grants` it propagates→500.
Net: ~60s after Redis drops (once L1 expires), cold lookups deny/500. Redis is a hard SPOF.
→ **Code (not a decision):** bypass a cache tier on error (catch → metric → continue); PDP can serve from Postgres with a low version it doesn't cache long.

**#6 — Fresh or reconnected replicas trust stale L2 entries.** 🟧 ✅
- `DefaultAccessClient.java:47` — `localVersion = new AtomicLong(0)`. It is **never** seeded from `rbac:policy:version` or `/v1/version`. A new replica (or one that missed events) accepts **any** L2 entry until its TTL expires.
- `DESIGN.md:405` understates this: *"L1's 60s TTL is the worst-case bound when the bus is down."* The real bound is **L1 + L2 ≈ 11 min** (L2 default TTL `rbac.cache.l2-ttl:10m`, `GrantCache.java:25`).
→ **Code:** seed `localVersion` from `/v1/version` (or `rbac:policy:version`) at startup and after each bus reconnect; correct `DESIGN §10`.

**#7 — The invalidation subscriber probably doesn't survive a broker restart.** 🟧 ✅
- `InvalidationSubscriber.java:44` — a **local (non-bean)** `RabbitAdmin` declares the **auto-delete** queue **once**, in the constructor.
- `InvalidationSubscriber.java:52` — the `SimpleMessageListenerContainer` has **no `setAmqpAdmin`**.
Spring AMQP only re-declares on reconnect when an admin is attached. After a broker restart (auto-delete queue is gone the moment the consumer disconnects), the replica very likely **never receives invalidations again until it restarts** — and no metric reveals it.
→ **Code:** make the `RabbitAdmin` + container beans and call `setAmqpAdmin`; add a "lost the bus" metric; add a broker-restart test.

**#8 — A durable queue fills up forever.** 🟧 ✅
- `RabbitConfig.java:32-40` — `rbac.invalidation.management` is **durable** and bound to the exchange, but **nothing consumes it** (no `@RabbitListener` anywhere). Every invalidation ever published accumulates there.
→ **Code:** delete it, or give it a TTL + `x-max-length`.

**#9 — The starter reuses the host's Redis.** 🟧 ✅
- `RbacClientAutoConfiguration.java:66` — `L2Cache` is wired to **whatever** `StringRedisTemplate` the host app has. There is **no `rbac.client.redis.*`** config (`RbacClientProperties` has none).
If a microservice runs its own Redis for sessions/app data, the client reads L2 from the **wrong instance** — always misses, or (worse, shared instance + shared creds) another service can write forged `GrantSet`s for any user unless Redis ACLs restrict it to read-only on `rbac:grants:*`. The design doesn't mention this.
→ **Code (this turn):** dedicated `rbac.client.redis.*` connection (see §4, Track B) + document a read-only ACL as a deployment requirement.

**#10 — The client has no timeouts and no stampede protection.** 🟧 ✅
- `RbacClientAutoConfiguration.java:99` — `RestClient.builder().baseUrl(…).build()` sets **no connect/read timeout** → a hung PDP holds request threads indefinitely.
- `L1Cache.java:27-33` — `getIfPresent`/`put`, not `Caffeine.get(key, loader)` → N concurrent L1 misses for the same user produce **N PDP calls**; and the global bump clears all L1 at once, so every replica hammers the PDP together.
→ **Code:** timeouts; Caffeine loader to coalesce; optional Resilience4j breaker.

### 🟡 Design improvements (state of the art)

- **Key the cache on the profile set, not `(userId, profileSet)`.** `CacheKeys.java:22` includes `userId`. In pure RBAC the result depends only on the profiles. At 15k users that's ~15k entries → **a few dozen** distinct profile combos. Also kills most of the invalidation thundering herd and makes per-profile invalidation (D10's reconsider item) trivial. `userId` only matters for audit. → **Code (high leverage).**
- **Transactional outbox now, not "later."** `InvalidationOnCommit.java:51-57` already concedes a lost invalidation and advises "re-run the admin change or bump the version manually" — but **there is no endpoint for either**. Postgres + a small relay gives a guaranteed bump+publish (~50 lines). At minimum add `POST /v1/admin/invalidate`. → **Code.**
- **`requirePermission` auto-creates permissions.** `AdminService.java:172-175` — a typo like `orders:refnud` silently becomes a new catalog entry, undermining D7's "defined once in a central catalog." → Make the catalog explicit (`POST /v1/admin/permissions`); grants reject unknown keys. **Code.**
- **Validate scope values against the schema.** `AdminService.java:188-200` checks **keys only** (not value types, e.g. `{"type":"list","item":"string"}`); and the resolver's merge **doesn't dedupe** (`RbacResolver.java:98-107`; the test even asserts `["EU","US","EU"]`). → **Code.**
- **`pg_partman` + `pg_cron`** is the standard for managed Postgres (RDS/Cloud SQL/Azure) — optional upgrade over the hand-rolled `ops/rotate_audit_partitions.sql`.
- **Small bugs (all confirmed in source):**
  - Change-audit JSON built by **string concatenation** — `AdminService.java:80,93,144,159` (`"{\"key\":\"" + permissionKey + "\"}"`) — a `"` in a key breaks the `::jsonb` cast. Use the `ObjectMapper` that's already injected.
  - Not-found/duplicate thrown as **`IllegalStateException`** (→ 500) instead of 404/409 — `AdminService.java:63,139,169,179`.
  - `GrantService.check()` → `grantedBy()` → `loader.load()` (`GrantService.java:89-93`) is a **DB query on every check**, bypassing the cache.
  - `GrantService.scope()`/`.ui()` (`GrantService.java:96-127`) have **no controller caller** — dead code.
  - `@Async` audit uses **Boot's default unbounded-queue executor** (no custom `TaskExecutor` bean; `AccessServiceApplication` is just `@EnableAsync`) — the "backpressure" in ADR-001's risk table is not implemented.

### Observability

OTLP via Micrometer is the right choice. Next steps: **tracing (OTel) spans across client → PDP**, and a **gauge for the client's `localVersion` lag** behind the global version — which would directly surface #6 and #7.

---

## 3. Test gaps that would have caught the above

No test exists for: **Redis down** (#5), **broker restart** (#7), a **fresh replica reading stale L2** (#6), **revoking a permission a template also grants** (#4), a **scope with empty/misspelled dimensions** (#3), or an **unauthenticated admin call** (#1). Each maps to a §8 property in ADR-002 or a Track C/D item below.

---

## 4. The plan (four tracks)

Two rules shape the order (both from the repo's own discipline): (1) **ADR-gated items are decided before code** (AGENTS.md standing instruction; ADR-001 §11); (2) keep the build green and commit at milestones.

### Track A — Decide first (ADR-002), then code
**ADR-002 ACCEPTED 2026-10-16** (all §7 answered). Unblocked — implement with regression tests:
- **A1 = ADR-002 S1** (PDP auth + actor-from-principal; K8s mesh-mTLS primary, OAuth2 client-credentials fallback, OIDC admin) — enforces D2's stated mitigation.
- **A2 = ADR-002 T1** (template = catalog, not bundle) — root fix for #4; revoke becomes effective.
- **A3 = ADR-002 A1 — DESCOPED** (team chose option c): audit = grant issuance + changes only; **no PEP batch emitter**. Resolves the D8↔D9 contradiction (#2) by choosing D9 (local derivation) + dropping the per-check audit obligation.
- **A4 = ADR-002 S2** (scopes fail-closed + canonical **Option A** typed-predicate format + DELETE) — fixes the #3 fail-open.
- **A5 = ADR-002 R1** (`audit_change` retention ≥ 1 yr + `granted_at`/`granted_by`) — compliance.

### Track B — Security hardening (implements stated intent; B2 needs no decision)
- **B1 = ADR-002 S1** code: `spring-security` + OAuth2 resource server; separate authority for `/v1/admin/**`; actor from the verified principal. *Gated on Q1 (which IdP).*
- **B2 — Starter Redis isolation** (decision-agnostic; **implemented this turn**): dedicated `rbac.client.redis.*` connection so the client never reads L2 from the host's app Redis; document a read-only ACL on `rbac:grants:*` as a deployment requirement. Fixes #9.

### Track C — Availability & correctness (mostly code, no decision) — **DONE (2026-10-19)**
- **C1** bypass a cache tier on error; PDP serves from Postgres with a short-cached low version (#5). **Done 2026-10-16.**
- **C2** seed `localVersion` from `/v1/version` at startup + on bus reconnect (#6). **Done 2026-10-19.**
- **C3** subscriber survives bus loss: missing queue non-fatal + re-declared + lost-bus metric + loss/recovery test (#7). **Done 2026-10-19.**
- **C4** delete (or TTL + `x-max-length`) the durable `pdpManagementQueue` (#8). **Done 2026-10-16.**
- **C5** timeouts (C5a) + Caffeine `get(key, loader)` stampede protection (C5b) + optional breaker (#10). **C5a done 2026-10-16, C5b done 2026-10-19** (breaker deliberately not added — the coalesced fetch already bounds amplification).
- **C6** **transactional outbox now** (removes the "lost invalidation" foot-gun C1–C3 all touch); at minimum `POST /v1/admin/invalidate`. **Done 2026-10-16** — see §6.

### Track D — Correctness cleanups + the big cache win
- **D1** **key the cache on the profile set** (not `(userId, profileSet)`) — highest-leverage change (15k → dozens of entries; per-profile invalidation becomes trivial). **Done 2026-10-20.**
- **D2** scope-merge dedup; validate scope value **types**; explicit permission catalog (stop `requirePermission` auto-creating); `grantedBy` without a per-check DB hit; 404/409 instead of `IllegalStateException`; bounded `@Async` executor; drop unused `GrantService.scope()/ui()`; JSON-escape the change-audit fields.
- **D3** add the §3 test gaps.
- **D4 (optional)** `pg_partman` + `pg_cron`; OTel tracing spans; `localVersion`-lag gauge.

---

## 5. Open questions (mirror ADR-002 §7)

| # | Question | Blocks |
|---|----------|--------|
| Q1 | Which identity issuer / mesh do we validate PDP tokens against? (S1) | Track A1/B1 |
| Q2 | Confirm template = **catalog** (attaching ≠ granting)? (T1) | Track A2 |
| Q3 | Decision audit at the **PEP** (batched) vs. **descoped** vs. OTLP-first? (A1) | Track A3 |
| Q4 | Confirm scopes **fail closed** (breaking `Scope.values()` change) + DELETE? (S2) | Track A4 |
| Q5 | `audit_change` retention **floor** (compliance) + `granted_by` on M2M rows? (R1) | Track A5 |

**Suggested order** (review's, refined): **B2 (done) → C6 (done, `812b3a3`) → ADR-002 ACCEPTED → A4/S2 (fail-closed + Option A) → A2/T1 (template) → A5/R1 (retention) → B1/S1 (auth) → A3 (descoped, doc-only) → C-track → D1 (profileSet keying).** Points #2, #4, and change-audit retention change team-accepted decisions, so per the standing instruction they landed in **ADR-002 first (now Accepted)**, then code.

---

## 6. What was delivered this turn

- **ADR-002** (`ADR-002-security-audit-and-semantics.md`) — the decision gate for S1/T1/A1/S2/R1, **Proposed** pending §7.
- **This review page** (`REVIEW-implementation-vs-design.md`) — evidence + plan, ready to share.
- **Track B — B2 (starter Redis isolation, #9):** new `rbac.client.redis.{host,port,password,database}` config; when set, the starter uses a **dedicated, self-managed** connection (`cache/RbacL2Redis`) for L2 instead of reusing the host's `StringRedisTemplate`; the host-template path is unchanged when unset. `RbacClientRedisIsolationTest` proves the dedicated path is taken and the L2 template is **not** the host's.
- **Track C (availability hardening) — C1, C4, C5a:**
  - **C1 (Redis SPOF bypass, #5):** client falls through to the PDP when an L2 read fails (`rbac.client.cache{tier=l2,result=error}`); PDP `GrantService` degrades to Postgres-computed grants when Redis is down. `GrantServiceRedisDownTest` + a client test.
  - **C4 (durable-queue leak, #8):** the un-consumed `pdpManagementQueue` is now bounded (TTL + max-length).
  - **C5a (timeouts, #10):** the starter's PDP `RestClient` has connect (2s) + read (5s) timeouts. (C5b stampede protection still to do.)
- **Track C — C6 (transactional outbox, removes the "lost invalidation" foot-gun):** `V3__outbox.sql` (`outbox_invalidation`) + `OutboxService` (`append()` in-txn, atomic `tryClaim()` lease, `pending()`, `pendingIds()`) + `InvalidationRelay` (shared `process()` = claim→bump→publish→mark, `@Scheduled(fixedDelay=1s)` durable relay, `forceInvalidate()` escape hatch). `AdminService` writes the outbox intent in the same txn as the grant change; `InvalidationOnCommit` delegates to the relay; `GrantChangeEvent` carries the outbox id; `POST /v1/admin/invalidate` added. New meters `rbac.pdp.outbox.{pending gauge,published,failed}` (replacing `rbac.pdp.invalidation.{lost,published}`). Tests: `OutboxServiceTest` (5, real-Postgres atomic claim under concurrency) + `InvalidationRelayTest` (6, unit) + 2 new ITs. `mvn clean verify` GREEN (42 tests).
- **Track C remainder — C2, C3, C5b (client resilience, done 2026-10-19):**
  - **C2 (#6 fresh-replica L2 seed):** `DefaultAccessClient.syncVersion()` seeds the local policy version from `GET /v1/version` (fail-soft, monotonic); wired as the client bean's `initMethod` (startup, covers L1-only hosts) and on every bus restore via the new `InvalidationHandler.onBusConnected()` default. A stale L2 entry can no longer be served by a fresh replica. Tests: `DefaultAccessClientVersionSyncTest` (4, incl. a control test pinning the old hole).
  - **C3 (#7 subscriber durability):** the subscriber's listener container now runs with `missingQueuesFatal=false` + `setAmqpAdmin`, and a lightweight monitor thread re-declares the per-instance auto-delete queue + binding while the consumer is down (spring-rabbit's built-in re-declaration requires an `ApplicationContext`, which this standalone container has none — gotcha #32). Outage/restored transitions are alarmed via new `rbac.client.bus.up` gauge + `rbac.client.bus.lost` counter, and the handler is asked to re-sync on restore. Test: `InvalidationSubscriberReconnectTest` (real RabbitMQ — the queue is deleted out from under a live consumer, which is the exact end state of a broker restart, without Testcontainers' restart-port flake).
  - **C5b (#10 stampede):** `L1Cache.getOrLoad` (Caffeine per-key locking) coalesces concurrent L1 misses for the same key into a single PDP fetch; fail-closed preserved (a loader failure stores nothing). Test: `L1CacheCoalescingTest` (16 concurrent cold misses → exactly 1 fetch).
  `mvn clean verify` GREEN — **94 tests** (access-service 72 + starter 22).
- **Track D — D1 (profileSet-keyed cache, done 2026-10-20):** `CacheKeys.grantsKey(Set<String> profiles)` now derives `rbac:grants:{sha256(sorted profiles)}` — **no `userId`**. The `GrantSet` is a pure function of the profile set (pure RBAC), so every user holding the same profiles shares one L1/L2 entry (15k users → a few dozen distinct profile sets); `userId` stays in the `/v1/grants` request for **audit** only. Changed in lockstep (gotcha #3): PDP `GrantCache`/`GrantService`, starter `L1Cache`/`L2Cache`/`DefaultAccessClient`; old per-user entries orphan and expire by TTL (self-heal). Pinned by the cross-module contract test (`l2ReadUsesTheSharedCacheKeyScheme`) + the PDP IT's L2 assertions. `mvn clean verify` GREEN — **94 tests** (access-service 72 + starter 22).

**Not yet started:** Track D remainder (**D2** dedup/404-409/JSON-escape/bounded-`@Async`/explicit catalog/`grantedBy` without a per-check DB hit/drop dead `scope()`/`ui()`; **D3** the §3 test gaps; **D4** optional). Each is a self-contained, testable increment. (Track A done 2026-10-17; Track C done 2026-10-19; D1 done 2026-10-20.)
