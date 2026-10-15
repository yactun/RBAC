# User Access Service — Design Document

Status: **v1 (Accepted)** · Scope: RBAC (role/profile-based) · Clients: Java/Spring, stateless · **Decisions accepted by the team 2026-10-14** (ADR-001, status *Accepted*)

**Stack (confirmed):** Spring Boot 3 (Java 21) · PostgreSQL (system of record + audit) · Redis (shared L2 cache) · RabbitMQ (invalidation bus) · Caffeine (L1) · Flyway.

---

## 1. Context & goals

We have an **identity manager (IDM)** that attaches **profiles** to users. Clients (microservices + front-ends) already fetch a user's profiles from the IDM and then need a central place to ask two things:

1. **Functionality** — "may this user do `X`?" (feature gates in services, tab/element visibility in front-ends).
2. **Data scope** — "which records may this user see in resource type `R`?" (returned as a filter the client applies to its query).

Non-goals (v1): authentication, user-attribute (ABAC) evaluation, object-graph (ReBAC) checks, multi-tenancy isolation. These are documented as escape hatches in §11.

**Design stance:** do *not* reinvent a policy engine. RBAC is a small, well-understood catalog + resolver. We build a thin central **Policy Decision Point (PDP)** and a shared **client starter (PEP helper)**, and we use Spring's built-ins (SpEL only if we later add conditions). We deliberately avoid OPA/Cerbos/Casbin for v1 — that adds engine ops for a requirement that is "a role grants a set of things."

---

## 2. Key assumptions (confirm)

- **A1 — Profile set travels with the request.** The client validates the user token against the IDM, extracts the profile set, and passes it to this service. This service does **not** call the IDM per decision. The client owns all IDM interaction and all enforcement; the PDP stays stateless about identity and therefore does not scale for IDM lookups. (Rejected A1': PDP calling the IDM.) **Team-confirmed 2026-10-14.**
- **A2 — Pure RBAC.** Grants depend only on the profile set, not on user attributes or request context. Multiple profiles → **union** of grants.
- **A3 — Per-resource-type scopes (confirmed with constraints).** Each microservice registers its own `resourceType` + the **dimension schema** (the keys it will filter on). The service stores the granted values; the client translates them into its query. **Constraint:** one shared dimension set per resource type (enforced at authoring; see §5.2). **Where the schema lives (team Q, 2026-10-14):** authored as a **YAML manifest in the owning service's repo** and registered into the PDP via the admin API (idempotent upsert); **Postgres stays the runtime source of truth** — the PDP does not read git directly.
- **A4 — Templates are UI bundles.** A template = a named set of UI-element permission keys for one app. Attaching a template to a profile grants all its element permissions in one move. The front-end owns the actual UI; it only asks "which elements are visible."
- **A5 — API/config first (confirmed).** No admin UI in v1. An audited admin API is enough to author grants. **Team-confirmed 2026-10-14: API-only for v1.**
- **A6 — Scale.** ~15,000 users peak, N stateless client replicas. This is small; a single Spring service + PostgreSQL + Caffeine handles it. The only scale concern is **audit write throughput** (handled async).
- **A7 — Stack.** Spring Boot 3 / Java 21. **PostgreSQL** = system of record + audit. **Redis** = shared L2 cache (from day 1). **RabbitMQ** = invalidation fan-out bus (chosen over Kafka for easier setup; **team-confirmed 2026-10-14** — reuse Kafka only if the platform already runs it). **Caffeine** = L1 in-process cache. **Flyway** for migrations.
- **A8 — Permission keys.** No existing convention — use the simplest: `<area>:<action>` for service features (`orders:create`) and `<area>.<element>` for UI (`customers-web.tab.invoices`). Two shapes, both self-explanatory. **Team-confirmed 2026-10-14:** the **hard contract is a cacheable key → JSON payload** (the `GrantSet`); key *naming* is a soft convention and **format enforcement is deferred** (optional catalog lint/test later).

---

## 3. Architecture (PDP / PEP)

```
                        ┌────────────────────────────────────────────┐
                        │            USER ACCESS SERVICE             │
 Identity Manager       │   (Spring Boot = Policy Decision Point)    │
 (users↔profiles)       │                                            │
   │   token/claims     │  • Admin API  (audited authoring)          │
   ▼                    │  • Grant API (profile set → GrantSet)      │
┌──────────────┐        │  • RBAC core (union across profiles)       │
│  Client app  │        │  • Redis (L2, owned) + version counter     │
│ (PEP, Spring)│        │  • Audit writer (async)                    │
│              │        └───────▲──────────────▲────────────┬────────┘
│ starter SDK  │                │              │            │
└──────┬───────┘                │  REST        │  events    │
       │  feature gate / scope / UI            │            ▼
       │            (validate token,           │      ┌───────────┐
       │             extract profiles)         │      │ RabbitMQ  │
       └───────────────────────────────────────┘      │ (invalid.)│
                                                       │ Redis (L2)│
                                                       └───────────┘
```

**Ownership split (keeps developers in control, per requirement):**
- **Central service owns:** the grants model, the check/grants API, RBAC resolution, audit, admin, cache invalidation.
- **Each client owns:** registering its `resourceType` + dimension schema, translating returned filters into its own query, and its feature-gate code. The starter removes the repeated fetching/caching plumbing.

**Trust model:** a caller may only pass a profile set that it derived from a **verified user token**. The starter resolves the profile set from the host app's verified identity (the `rbac.userId` / `rbac.profiles` request attributes the host sets after validating the token, or a host-provided resolver) — never from an untrusted request body.

---

## 4. Decision contract (API)

Base: `/v1`. All decision endpoints are **idempotent GETs/POSTs** and carry a `version` for cache validation.

### 4.1 Check (fast path, single permission)

```
POST /v1/check
{
  "user": { "userId": "u-123", "profiles": ["finance", "regional-ops"] },
  "key":  "orders:create"
}
→ 200 {
  "allowed":   true,
  "grantedBy": ["finance"],
  "version":   123
}
```

### 4.2 Grant set (the cached unit: permissions + UI + scopes in one call)

```
POST /v1/grants
{
  "userId":   "u-123",
  "profiles": ["finance", "regional-ops"]
}
→ 200 {
  "permissions": ["orders:create","orders:read","customers-web.tab.invoices","customers-web.tab.orders"],
  "scopes": {
    "orders":   {"resourceType":"orders",   "filters":{"orgIds":[1,2,3],"regions":["EU"]}},
    "invoices": {"resourceType":"invoices", "filters":{"orgIds":[1,2,3]}}
  },
  "ui": { "customers-web": {
            "app":"customers-web",
            "elements":[
              {"key":"tab.orders",   "visible": true},
              {"key":"tab.invoices", "visible": true},
              {"key":"tab.payments", "visible": false}
            ] } },
  "version": 123
}
```

Notes:
- This is the **single cached unit** (`GrantSet`, ADR D9). The client derives `check` / `scope` / `ui` **locally** from it — there is no separate `/scope` or `/ui` endpoint.
- `version` is a monotonically increasing service-wide policy version. Clients compare it to detect stale local cache cheaply (§7).

### 4.3 Admin (all writes are audited, §8; every one bumps the version and publishes invalidation **after commit**)

All admin endpoints take an `X-Actor` header (the admin identity, recorded in the change audit).

```
POST   /v1/admin/profiles                             { "name":"finance", "description":"..." }
POST   /v1/admin/profiles/{name}/permissions          { "key":"orders:create" }
DELETE /v1/admin/profiles/{name}/permissions/{key}
PUT    /v1/admin/profiles/{name}/scopes/{resourceType}{ "orgIds":[1,2,3], "regions":["EU"] }

POST   /v1/admin/templates                            { "appId":"customers-web", "name":"standard",
                                                        "elements":["customers-web.tab.invoices","customers-web.tab.orders"] }
POST   /v1/admin/profiles/{name}/templates            { "appId":"customers-web", "name":"standard" }
POST   /v1/admin/resource-types                       { "name":"orders", "ownerService":"orders-svc",
                                                        "dimensionSchema":{"orgIds":{"type":"list","item":"string"},
                                                                            "regions":{"type":"list","item":"string"}} }
```

> Introspection / ops (on the decision controller, not admin): `GET /v1/version` → `{ "version": 123 }`.
>
> `PUT .../scopes/{resourceType}` validates its body: dimension keys must exactly match the
> resource type's `dimensionSchema` (400 otherwise) — the scope-merge contract enforced at
> authoring. `POST /v1/admin/resource-types` requires a non-empty `dimensionSchema`.

---

## 5. Data model

### 5.1 Entities

- **Profile** — the "role." Has a stable name.
- **Permission** — a namespaced key: `<area>:<action>` for service features (`orders:create`) or `<area>.<element>` for UI (`customers-web.tab.invoices`). Central catalog so keys are defined once and referenced.
- **ResourceType** — a data domain owned by one service, with a **dimension schema** (the allowed filter keys). Registered by the owning service.
- **ProfileScope** — for one profile + one resourceType, the granted dimension values (JSONB).
- **Template** — a named bundle of UI elements for one app; each element is gated by a permission key.
- **ProfileTemplate** — profile grants the whole template (→ inherits all its element permissions).
- **Assignment (user ↔ profile)** — lives in the **IDM**, not here (per A1).

### 5.2 Effective-grant resolution

For a profile set `P`:

- `permissions(P) = ⋃ over p∈P of ( profile_permission(p) ∪ ⋃ over templates(p) of template_element → permission )`
- `scopes(P, R)   = merge( ⋃ over p∈P of profile_scope(p, R).dimensions )` — **union/OR** on each dimension list.
  > **Contract:** this only *widens* access when **every** profile scoping `R` declares the **same dimension set**. If profiles use different dimensions (e.g. A={`orgIds`}, B={`regions`}), the client applies the merged scope as an AND across dimensions and it can *shrink* access — so authoring requires a shared dimension set per resource type (a list-of-alternative-scopes OR-model is the future alternative). Enforced: `setScope` rejects dimensions whose keys differ from `resource_type.dimension_schema` (400).
- `ui(P, app)     = for each element e of each template of P with e.appId = app: visible = (e.permission ∈ permissions(P))`

### 5.3 PostgreSQL schema (v1)

```sql
create table profile (
  id          uuid primary key default gen_random_uuid(),
  name        text unique not null,
  description text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table permission (
  id          uuid primary key default gen_random_uuid(),
  key         text unique not null,          -- "orders:create" | "customers-web.tab.invoices"
  description text,
  created_at  timestamptz not null default now()
);

create table profile_permission (
  profile_id      uuid not null references profile(id),
  permission_id   uuid not null references permission(id),
  primary key (profile_id, permission_id)
);

create table resource_type (
  id               uuid primary key default gen_random_uuid(),
  name             text unique not null,      -- "orders"
  owner_service    text not null,
  dimension_schema jsonb not null             -- {orgIds:{type:list,item:string}, ...}
);

create table profile_scope (
  id               uuid primary key default gen_random_uuid(),
  profile_id       uuid not null references profile(id),
  resource_type_id uuid not null references resource_type(id),
  dimensions       jsonb not null,            -- {"orgIds":[1,2,3],"regions":["EU"]}
  unique (profile_id, resource_type_id)
);

create table template (
  id     uuid primary key default gen_random_uuid(),
  app_id text not null,
  name   text not null,
  unique (app_id, name)
);

create table template_element (
  id            uuid primary key default gen_random_uuid(),
  template_id   uuid not null references template(id),
  element_key   text not null,                -- "tab.invoices"
  permission_id uuid not null references permission(id),
  unique (template_id, element_key)
);

create table profile_template (
  profile_id  uuid not null references profile(id),
  template_id uuid not null references template(id),
  primary key (profile_id, template_id)
);

-- Indexes for the decision hot path (GrantLoader)
create index ix_pp_profile   on profile_permission (profile_id);
create index ix_pt_profile   on profile_template (profile_id);
create index ix_ps_profile   on profile_scope (profile_id, resource_type_id);
create index ix_te_template  on template_element (template_id);
```

**Audit (separate schema / partitioned, written async):**

```sql
create table audit_decision (
  id              bigint generated always as identity,
  ts              timestamptz not null default now(),
  user_id         text not null,
  profile_set     text[] not null,
  op              text not null,               -- 'check'
  target          text,                        -- permission key
  allowed         boolean,
  version         bigint,
  request_id      text,
  calling_service text
) partition by range (ts);

create table audit_change (
  id           bigint generated always as identity,
  ts           timestamptz not null default now(),
  actor        text not null,                  -- admin identity
  entity       text not null,                  -- 'profile' | 'template' | 'resource_type'
  entity_ref   text not null,
  action       text not null,                  -- 'grant_permission' | 'revoke_scope' | ...
  detail       jsonb
) partition by range (ts);
```

> Partition `audit_decision` by month; prune/archive per retention policy. Never block the decision path on the audit write (§8).

---

## 6. Client starter (the reusable "wheel")

`rbac-client-spring-boot-starter` — one shared library so no client re-implements fetching/caching/audit.

```java
public interface AccessClient {
    CheckResult check(UserContext user, String key);
    default boolean has(UserContext user, String key) { return check(user, key).allowed(); }
    Scope       scope(UserContext user, String resourceType);  // throws AccessDeniedException (403) if no scope
    UiElements  ui(UserContext user, String app);              // empty bundle (nothing visible) if no bundle
    GrantSet    grants(UserContext user);                       // the full cached unit (ADR D9)
}

// UserContext is resolved by the host app (it owns identity). The starter's default
// reads the 'rbac.userId' / 'rbac.profiles' request attributes — set them after
// validating the token against the IDM — or supply your own resolver. Never from
// an untrusted request body.
record UserContext(String userId, Set<String> profiles) {}
```

**Example integration — feature gate in a microservice:**

```java
@RestController
class OrdersController {
    private final AccessClient access;
    private final UserContextResolver users;   // host-derived, verified identity

    @PostMapping("/orders")
    Order create(@RequestHeader("X-Request-Id") String reqId,
                 @RequestBody OrderRequest body) {
        UserContext user = users.current();    // never from an untrusted body
        if (!access.has(user, "orders:create")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not permitted");
        }
        // ... business logic
    }
}
```

**Example integration — apply a data scope to a query:**

```java
@Service
class OrderQuery {
    private final AccessClient access;
    private final Querydsl JPA; // or Spring Data

    public Page<Order> list(UserContext user, Pageable page) {
        Scope s = access.scope(user, "orders");          // throws AccessDeniedException (403) if no scope
        List<String> orgIds  = s.values("orgIds");
        List<String> regions = s.values("regions");
        BooleanBuilder b = new BooleanBuilder();
        if (!orgIds.isEmpty())  b.and(order.orgId.in(orgIds));
        if (!regions.isEmpty()) b.and(order.region.in(regions));
        // b is AND-ed into the repository query — the filter never leaves the service
        return repo.findAll(b, page);
    }
}
```

**Example integration — front-end visibility:**

```java
// BFF: "which tabs of the standard customer UI may this user see?"
UiElements ui = access.ui(user, "customers-web");
// → [ {tab.orders:true}, {tab.invoices:true}, {tab.payments:false} ]
// The front-end renders only the visible elements.
```

The starter handles, transparently:
- HTTP call to **`/v1/grants`** (one call; `check`/`scope`/`ui` are derived locally, ADR D9).
- **L1 Caffeine** cache keyed by `(userId, profileSet)`, TTL ~60s, validated against `version`.
- Optional **L2 Redis** shared cache (read-only) + invalidation subscription (§7).
- **Fail-closed** by default: a genuine fault makes `check` → `allowed=false` (deny) and `scope`/`ui` → **throw** (→ 500), so an unscoped query can never run. A user who simply *lacks* a grant is a **403, not a 500**: `scope` → `AccessDeniedException`, `ui` → empty bundle (nothing visible).

---

## 7. Caching & invalidation (stateless clients)

Because clients are stateless and multi-replica, a purely local cache fragments. We use a three-tier read path where **the PDP owns the shared cache (Redis)** and a **broker (RabbitMQ) guarantees invalidation reaches every replica**:

| Tier | Where | Owner | TTL | Role |
|---|---|---|---|---|
| **L1** | Client process (Caffeine) | starter | **60s** | Absorb the hot path; zero network |
| **L2** | Redis | **PDP** (writes/invalidates) | **5–15 min** | Shared across all replicas; clients read it **directly — no REST hop** |
| **L3** | PostgreSQL via PDP REST | PDP | — | Compute from source of truth; only on L2 miss |

**Read path (starter):** `L1 (Caffeine)` → miss → `L2 (Redis GET)` → miss → `L3 (PDP /v1/grants)` → PDP computes, writes L2, returns → client warms L1. The common case never hits the PDP.

**Who writes Redis:** only the PDP. Clients never write L2 — this keeps one owner of the key schema and of invalidation. Clients read L2 or call the PDP.

**Invalidation (guaranteed):** on any grant change the PDP — **after the admin transaction commits** (so a refetch can never read uncommitted grants stamped with the new version) — (1) bumps the global `rbac:policy:version` counter, and (2) publishes `rbac.invalidated { userId?, profile?, version, ts }` to a **RabbitMQ** topic exchange. Every client replica declares its **own per-instance, non-durable, auto-delete queue** bound to that exchange (the correct broadcast pattern — a single shared queue would competitive-consume and deliver each event to only one replica) and, on receipt, raises its local version and clears its L1. RabbitMQ is chosen over Redis pub/sub because invalidation must *reach every replica* — a proper broker delivers that; Redis pub/sub is fire-and-forget.

**Why this is safe to raise L2 TTL:** L2 is shared and version-checked, and RabbitMQ invalidation fires on every change, so a long L2 TTL does not mean long staleness — real changes are pushed, not waited out. L1 stays short (60s) because it is the per-replica copy most likely to be stale.

**Revocation:** removing a permission is urgent → the RabbitMQ invalidation event makes it near-instant (not 60s), even with a long L2 TTL.

**Fallbacks / flags:** RabbitMQ subscription is optional (feature-flagged in the starter) — without it, correctness degrades to L1 60s TTL + L2 version check, which is still safe. `version` is kept as a cheap "am I stale?" revalidation for clients that can't subscribe to the bus.

**Bus-down posture (team decision, 2026-10-14):** if the bus is **down**, we **deliberately keep** TTL-bounded staleness (L1 60s / L2 ≤ 15 min) and do **not** accelerate invalidation. Accelerating it during an outage would amplify load on the PDP at the worst moment; **stale-but-bounded is the graceful-degradation choice**, not an accident. Revocation is near-instant *only while the bus is healthy*.

**Anti-pattern to avoid:** putting the permission list in the JWT. It goes stale the instant a profile changes and bloats the token — exactly what stateless, cached access argues against.

---

## 8. Audit

- **Decision audit** (`audit_decision`): every `check` — `ts, user_id, request_id, calling_service, op, target, allowed, profile_set`. Written **asynchronously** (an in-process `@Async` writer to a partitioned table). Never on the decision hot path.
- **Change audit** (`audit_change`): every admin mutation — `actor, entity, entity_ref, action, detail`. Synchronous (the mutation is the event) but written in the same transaction as the grant change.
- **Retention:** rolling **3 months** (team-confirmed 2026-10-14) for both decision and change audits; monthly partitions with **drop/archive of partitions older than 3 months** (scheduled job + runbook). Every record is queryable — an audit is a query, not archaeology.

---

## 9. Module decomposition (repo layout)

```
user-access/
├─ access-service/                    # the PDP (Spring Boot app)
│  ├─ api/                            # GrantService, AdminService, Decision + Admin controllers
│  ├─ rbac/                           # resolver: profile set → effective grants (pure, unit-tested)
│  ├─ domain/                         # entities, repositories (Spring Data JPA)
│  ├─ invalidation/                   # version bump + RabbitMQ publish (AFTER commit)
│  ├─ cache/                          # GrantCache (Redis L2, PDP-owned) + VersionService
│  ├─ audit/                          # async decision writer, change writer
│  └─ config/                         # Redis + RabbitMQ beans
│
├─ rbac-client-spring-boot-starter/   # the PEP helper library (shared wheel)
│  ├─ client/                         # AccessClient impl, HTTP, DTOs
│  ├─ cache/                          # Caffeine + optional Redis
│  ├─ security/                       # UserContextResolver (host resolves verified identity)
│  ├─ invalidation/                   # RabbitMQ subscriber + invalidation handler
│  └─ autoconfigure/                  # @AutoConfiguration, properties
│
├─ rbac-model/                        # shared DTOs / enums (versioned, consumed by both)
└─ docs/                              # this DESIGN.md, API examples, runbooks
```

The `rbac/` resolver is **pure and dependency-free** (profile set in → grants out) so it is trivially unit-tested against the union/merge semantics in §5.2, independent of HTTP, cache, or DB.

---

## 10. Non-functional notes

- **Performance:** 15k users is small. L1 TTL absorbs the hot path; the service sees only refetches. `version` keeps revalidation a tiny payload. No sharding needed.
- **Availability:** fail-closed by default. If you need degraded reads, the starter can be configured to serve last-known-good scope with an audit tag — but the default is deny.
- **Consistency:** bounded staleness is a documented, accepted property (L1's 60s TTL is the worst-case bound when the bus is down). Grant changes are rare; a user's own revocation should be immediate → the RabbitMQ invalidation event makes it near-instant **while the bus is healthy**. **When the bus is down we deliberately keep TTL-bounded staleness** rather than accelerate invalidation (avoids amplifying load during the outage) — a graceful-degradation choice, team-confirmed 2026-10-14.
- **Observability (SLO evidence, built 2026-10-15):** metrics are recorded through the **Micrometer API** and exported as **OTLP to the platform's OpenTelemetry collector** (team choice 2026-10-15: a CNCF-adopted framework, to minimize obsolescence risk — Prometheus/Tempo may remain *backends behind* the collector). PDP: `rbac.pdp.decision` latency per operation, `rbac.pdp.cache` L2 hit/miss, `rbac.pdp.invalidation.published`/`.lost` (the "lost" counter is the bus-outage alarm). Client starter: `rbac.client.cache` L1/L2 hit-miss, `rbac.client.decision` latency tagged with the serving source (l1/l2/pdp/error), `rbac.client.pdp.fetch` success/failure, `rbac.client.invalidation.events`. The starter's sink is a small no-op-by-default interface — **Micrometer stays an optional dependency**, so an L1-only host without an observability stack still boots (ADR D5).

---

## 11. Escape hatches (what we deliberately did NOT build)

- **ABAC / conditional grants** (e.g. "finance sees all orgs, but only their region"): add a `condition` (SpEL) column to `profile_permission` / `profile_scope`, evaluated at resolve time. SpEL is built into Spring — no new engine.
- **Policy-as-code / complex rules, security-authored, polyglot:** swap the resolver for **OPA** (Rego) or **Cerbos** (YAML + query plans). The decision contract in §4 stays identical — only the engine behind it changes.
- **Role hierarchy** (profile inherits from profile): add a `profile_parent` edge and resolve transitively in the pure `rbac/` resolver. Cheap to add later, not in v1.
- **Object-level (ReBAC)** "can user edit *this* record": out of scope; that's Oso/OpenFGA territory.

---

## 12. Accepted decisions (team-confirmed 2026-10-14)

1. **Profile source — client passes it.** The client owns all IDM interaction and enforcement; the PDP never calls the IDM (A1). PDP scales only for grant resolution, not identity lookups. **Confirmed.**
2. **Caching — Redis L2 from day 1.** L1 Caffeine 60s; L2 Redis (PDP-owned, clients read directly) at 5–15 min TTL; L3 PostgreSQL. Redis is what lets the L2 TTL be long safely.
3. **Invalidation bus — RabbitMQ** (over Kafka; easier to operate). PDP publishes `rbac.invalidated` (after the admin commit); every client replica — on its own per-instance, non-durable, auto-delete queue — clears its L1 and raises its local version. Makes revocation near-instant. **Confirmed 2026-10-14** after a Kafka-benefit analysis (replay/durability/throughput not decisive for an ephemeral invalidation signal); reuse Kafka only if the platform already runs it. Revocation is near-instant *while the bus is healthy*; **TTL-bounded graceful degradation when the bus is down** (deliberate).
4. **Permission keys** — `<area>:<action>` (service) and `<area>.<element>` (UI). Simplest, self-explanatory, no prior convention. **Confirmed 2026-10-14:** hard contract = cacheable key → JSON payload; naming is a soft convention; **format enforcement deferred** (optional catalog lint/test later).
5. **Audit store — PostgreSQL** (monthly-partitioned, async writes). No ES/ClickHouse for now. **Retention: rolling 3 months** (confirmed 2026-10-14) — drop/archive partitions older than 3 months.
6. **Admin surface — API-only for v1** (confirmed 2026-10-14; no admin UI).
7. **Scope model — per-resource-type, with the dimension-set constraint** (confirmed 2026-10-14). Schema authored as YAML in the owning service's repo and registered into the PDP; **Postgres is the runtime source of truth**.
