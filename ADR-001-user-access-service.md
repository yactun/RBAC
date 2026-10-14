# ADR-001 — User Access Service: Architecture

| | |
|---|---|
| **Status** | **Accepted** (2026-10-14) — team review completed; feedback incorporated in §8 |
| **Date** | 2026-10-10 |
| **Deciders** | Platform / Identity team |
| **Related** | `DESIGN.md` (detailed design: schema, API, module layout) |
| **Supersedes** | — |

This record explains **why** the design in `DESIGN.md` is shaped the way it is. `DESIGN.md` is the *what*; this ADR is the *why and at what cost*. Every decision below names the alternatives we considered and rejected, the trade-off we accepted, and the condition under which we would reconsider.

---

## 1. Summary

We will build a **central, thin RBAC service** (a Policy Decision Point, "PDP") plus a **shared Spring client starter** (the Policy Enforcement helper). The service resolves a **profile set** into an effective grant set — functionality permissions, per-resource-type data scopes, and UI-element visibility — and returns it as one cached unit. Clients (stateless Java/Spring microservices and front-ends) cache it locally, read a shared cache, and apply it. A broker guarantees invalidation reaches every replica.

We deliberately **do not** adopt a general-purpose policy engine (OPA, Cerbos, Casbin). The requirement is *"a profile grants a set of things"* — a data relationship, not a rule to evaluate — so a catalog + union is the correct abstraction. The decision API is engine-agnostic, so we can swap in a policy engine later without touching clients.

---

## 2. Context & problem

An **identity manager (IDM)** already attaches **profiles** to users. Client microservices fetch a user's profiles from the IDM and then need a central place to ask two things:

1. **Functionality** — "may this user do `X`?" (feature gates in services; tab/element visibility in front-ends).
2. **Data scope** — "which records may this user see in data domain `R`?" (returned as a filter the client applies to its own query).

**Hard constraints (from the team):**

- Clients are **stateless**, Java/Spring, and **own their enforcement** (developers keep ownership of the code that enforces access).
- The client already talks to the IDM; the central service should not duplicate that work.
- ~**15,000 users** peak.
- **Audit trail is mandatory.**
- **RBAC**: decisions depend on the profile (role), not on free-form user attributes.
- We want to **avoid reinventing the wheel** and avoid over-engineering.

**What "good" looks like:** one place to reason about and change access; a consistent answer for the same input everywhere; a fast, cacheable decision path for stateless clients; and an audit that is a query, not archaeology.

---

## 3. Assumptions (each with its blast radius)

An ADR is only as good as its assumptions. Each is stated with what breaks if it is wrong.

| # | Assumption | If it is wrong… |
|---|---|---|
| **A1** | **The client passes the profile set.** The client validates the user token against the IDM, extracts the profiles, and sends them to the PDP. The PDP never calls the IDM. | The PDP cannot verify identity itself; it trusts the caller. If we must serve **untrusted/third-party** callers, D2 must change. **(Team-confirmed 2026-10-14: this trust model is acceptable.)** |
| **A2** | **Pure RBAC in v1.** Grants depend only on the profile set — not on user attributes or request context. | We lose the ability to say "user with profile X, but only if `attr=Y`." Mitigation in D3. |
| **A3** | **Per-resource-type scopes.** Each microservice defines its own data domain and the dimension keys it filters on; the PDP stores granted values, the client translates to its query. | If scopes must be *cross-service* (one rule spanning several domains), the per-domain model needs a composition layer. **(Team-confirmed 2026-10-14, with the dimension-set constraint below; see the "where the schema lives" note.)** |
| **A4** | **Templates are UI bundles.** A template is a named set of UI-element permission keys for one app; the front-end owns the actual UI. | If the platform must centrally own/audit *layout*, the front-end-owns-UI model is insufficient. |
| **A5** | **API/config first.** No admin UI in v1; an audited admin API is enough to author grants. | If non-developers must self-serve grants, a UI becomes a requirement, not a nicety. **(Team-confirmed 2026-10-14: API-only for v1.)** |
| **A6** | **Scale is modest.** ~15k users; a single Spring service + Postgres handles it. Only audit write volume is a concern. | If RPS/user counts are an order of magnitude higher, the cache + audit design needs re-examination. |
| **A7** | **Stack is available.** Spring Boot 3 / Java 21, PostgreSQL, Redis, RabbitMQ, Caffeine, Flyway. | Any missing component forces a substitution (see D6/D8 alternatives). |
| **A8** | **No existing permission-key convention.** We define a simple one. | If an enterprise scheme already exists, D7 must conform to it instead. **(Team-confirmed 2026-10-14: naming is a soft convention; the hard contract is a cacheable key + JSON payload; schema-enforcement deferred.)** |

> **Scope-merge contract (consequence of A3, pinned by `RbacResolverTest`):** the effective
> scope for a resource type is the per-dimension union across a user's profiles. That union
> *widens* access only when **every** profile scoping the type declares the **same dimension
> set**; if profiles use different dimensions, the merged scope applies as an AND across
> dimensions and can *shrink* access. Authoring therefore keeps one shared dimension set per
> resource type — enforced at authoring time (`setScope` rejects dimensions that differ from
> `resource_type.dimension_schema`, 400).
>
> **Where the schema lives (team question, answered 2026-10-14):** the dimension schema is
> *owned by the owning service* and authored as a **YAML manifest in that service's repo**,
> registered into the PDP via the existing admin API (idempotent upsert, e.g. at boot or on
> change). **Postgres (`resource_type.dimension_schema`) remains the runtime source of truth**
> and the authoring-time validation target. The PDP does not read git directly.

---

## 4. Decisions

Each decision is given as: **Context → Decision → Alternatives considered → Trade-off accepted → Consequences → When to reconsider.**

### D1 — Central PDP + client starter, *not* a policy engine

- **Context:** We need functionality checks + data scoping, per-resource-type, static, RBAC, for Java/Spring clients.
- **Decision:** A thin central service holds a **catalog of grants** and a **pure resolver** (profile set → union of grants). A shared starter handles client-side fetch/cache/apply. No policy language, no separate engine process.
- **Alternatives considered:**
  - **OPA** (Rego) — powerful, but a rule *evaluator*; we have no rules, only grants. Adds engine ops + a learning curve for a data-relationship problem.
  - **Cerbos** — purpose-built, has "query plans" (policy → DB filter); attractive, but still an extra engine + policy-authoring model we don't need for static RBAC.
  - **Casbin** — in-process RBAC/ABAC library; good, but we'd still wrap it in a service and duplicate the admin/audit/cache we're building anyway.
  - **Keycloak Authorization Services** — only fits if the IDM *is* Keycloak; not assumed (A1).
  - **Oso / OpenFGA / Cedar** — relationship-based (ReBAC); overkill; weaker fit for "return a filter."
  - **GCHQ Palisade** — solves data scoping + masking but is a heavy Spring platform; more than this need.
- **Trade-off accepted:** We give up policy-as-code, security-team-authored rules, and a built-in policy test harness. We keep **full ownership** of a small, reviewable codebase and avoid operating a second runtime.
- **Consequences:** Simpler to run and to explain; the resolver is pure and unit-testable; the "don't reinvent the wheel" bar is met by *not* solving a problem we don't have.
- **When to reconsider:** When grants must be **conditional, cross-resource, or authored by a non-developer security team** — then swap the resolver for OPA/Cerbos. The client contract (the `GrantSet` payload) does not change.

### D2 — Client passes the profile set; PDP is stateless about identity

- **Context:** Clients are stateless and already validate the token against the IDM; the team wants developers to keep ownership of enforcement.
- **Decision:** The request carries the **profile set** (derived by the client from the verified token). The PDP resolves grants only and **never calls the IDM**.
- **Alternatives considered:** PDP resolves `userId → profiles` via the IDM (cacheable, but couples the PDP to IDM availability/scaling and duplicates work the client already does).
- **Trade-off accepted:** The PDP **trusts the caller** to pass a legitimate profile set.
- **Consequences:** The PDP scales for grant resolution only, not identity; it stays independent of the IDM.
- **Mitigation:** The starter enforces that profiles come from the verified security context, not an untrusted body; the PDP sits behind service-to-service auth; every decision audit records the profile set used.
- **When to reconsider:** If the PDP must serve **untrusted or third-party** callers that cannot be trusted to self-report profiles.
- **Team review (2026-10-14):** trust model **confirmed** — the PDP trusts the authenticated caller to pass a verified profile set.

### D3 — Pure RBAC in v1 (no ABAC / attribute conditions)

- **Context:** The team wants role-based decisions; static, auditable, enumerable.
- **Decision:** Grants depend only on the profile set. "Who can do what" is a **set**, not a query.
- **Alternatives considered:** ABAC with SpEL conditions now (e.g. "finance sees all orgs, but only their region").
- **Trade-off accepted:** We cannot yet express attribute-conditional grants.
- **Consequences:** Simpler to reason about, test, and audit; the resolver stays trivially correct.
- **When to reconsider / escape hatch:** Add a `condition` (SpEL) column to `profile_permission` / `profile_scope`, evaluated at resolve time. SpEL is in Spring — **no engine change**, no client change.

### D4 — Templates = named bundles of UI-element permission keys

- **Context:** "Templates" are UI elements/tabs that a front-end shows or hides.
- **Decision:** **One mechanism** — namespaced permission keys — serves both service features (`orders:create`) and UI visibility (`customers-web.tab.invoices`). A **template** is a named *grouping* of UI-element keys for one app, so a profile can grant a whole UI in one move. The PDP returns **visibility**; the front-end owns rendering.
- **Alternatives considered:** The PDP owns the full UI structure (tabs/fields/layout) and returns a rendered model.
- **Trade-off accepted:** The platform cannot centrally *enforce* UI rules; the front-end must honor the flags it is given.
- **Consequences:** No parallel authorization system; UI and feature access are the same, testable concept. The front-end is the PEP for its own surface.
- **When to reconsider:** If the org requires the platform to centrally own and audit application **layout**.

### D5 — Three-tier cache; PDP owns the shared tier

- **Context:** Stateless, multi-replica clients; we must not force a REST call per decision, and a purely local cache would fragment across replicas.
- **Decision:**
  - **L1** — Caffeine in each client, **60s**.
  - **L2** — Redis, **owned by the PDP** (PDP writes + invalidates), clients **read directly** (no REST hop), **5–15 min**.
  - **L3** — PostgreSQL via the PDP, only on L2 miss.
  - One owner of the cache key schema and invalidation = the PDP.
- **Alternatives considered:** local TTL only (no Redis); Redis pub/sub as the cache bus.
- **Trade-off accepted:** One more component (Redis); clients depend on the PDP's key contract.
- **Consequences:** The common case never hits the PDP; replicas share state; revocation can be made near-instant (see D10) while keeping L2 TTL long.
- **When to reconsider:** If we can live with local-only staleness, drop Redis (it is the documented fallback).

### D6 — Invalidation bus = RabbitMQ

- **Context:** Invalidation must **reach every replica**, and **revocation** (removing access) is urgent.
- **Decision:** On any grant change the PDP publishes an `InvalidationEvent` to a **RabbitMQ** exchange; every client is a subscriber and drops stale L1.
- **Implementation note (v1):** each client declares its **own per-instance, non-durable, auto-delete queue** bound to the shared exchange (a single shared queue would competitive-consume and deliver each event to only one replica, defeating "reach every replica").
- **Alternatives considered:**
  - **Redis pub/sub** — fire-and-forget; no delivery guarantee; weaker for "must reach every replica."
  - **Kafka** — benefits: (a) **replay/retention** (messages retained as a log, re-consumable from any offset); (b) **strong durability** (min-ISR replication, consumer-group offsets, exactly-once); (c) **log compaction** (latest value per key); (d) **high-throughput ordered streaming** (horizontal partitioning); (e) a large streaming ecosystem (Flink/Spark/Connect). **None are decisive for this use case** — see the Team-review note.
  - **TTL-only** — no push; revocation waits up to the TTL.
- **Trade-off accepted:** One more broker to operate.
- **Consequences:** Guaranteed fan-out, acked delivery, near-instant revocation, clean audit-event transport if we later want it.
- **When to reconsider:** If the platform already runs **Kafka** (prefer it), or if we need **replayable** audit streams (Kafka).
- **Team review (2026-10-14):** keep **RabbitMQ**. Kafka's replay/durability/throughput benefits are not decisive for an ephemeral "drop your cache" signal (no replay value — a late replica just refetches fresh grants; TTL + version are the safety net; volume is tiny; the global monotonic version already orders events so out-of-order delivery is harmless). The only argument for Kafka is **ops consolidation**: if the platform already runs Kafka, reuse it to avoid a second broker.

### D7 — Permission-key convention: `<area>:<action>` and `<area>.<element>`

- **Context:** No existing convention (A8).
- **Decision:** Two self-explanatory shapes — service features use `<area>:<action>` (`orders:create`); UI elements use `<area>.<element>` (`customers-web.tab.invoices`). A central `permission` catalog defines each key once.
- **Alternatives considered:** a single flat namespace; a fully structured model (resource/action/scope objects).
- **Trade-off accepted:** Structure is by **convention**, not schema-enforced.
- **Mitigation:** catalog + a lint/test that validates key format; keys are defined centrally, not ad-hoc.
- **When to reconsider:** If an enterprise key scheme exists (A8), conform to it.
- **Team review (2026-10-14):** naming is a **soft convention** (organizational, not technical). The **hard contract** is a cacheable **key → JSON payload** (`GrantSet`), which stays unchanged. **Schema-enforcement of the key format is deferred** ("if interesting, we can do it later"); the catalog + optional lint/test remains the recommended (non-mandatory) guard.

### D8 — Audit in PostgreSQL (partitioned, async)

- **Context:** Audit is mandatory; 15k users; one datastore already holds the grants.
- **Decision:** `audit_decision` (every check) and `audit_change` (every admin mutation) in **PostgreSQL**, **monthly-partitioned**. Decision writes are **async** (never on the decision hot path); change writes are synchronous (the mutation *is* the event).
- **Alternatives considered:** Kafka → Elasticsearch/ClickHouse for analytics.
- **Trade-off accepted:** Postgres is less ideal for large-scale security analytics/dashboards.
- **Consequences:** One infra, queryable audit, fast decision path.
- **When to reconsider:** If we need rich security analytics/dashboards → add ES/ClickHouse downstream.
- **Team review (2026-10-14):** retention = **rolling 3 months** (decision + change audits). Ops: roll forward monthly partitions; **drop/archive partitions older than 3 months** (scheduled job + runbook).

### D9 — One cached grant-set unit; clients derive check/scope/ui locally

- **Context:** Avoid repeated REST calls; keep check/scope/ui mutually consistent.
- **Decision:** The PDP returns a single **`GrantSet`** (all permissions + all scopes + all UI) keyed by `(userId, profileSet)`. The client caches it and **derives** `check`, `scope`, and `ui` from that one object.
- **Alternatives considered:** separate `/check`, `/scope`, `/ui` endpoints, each doing its own lookup.
- **Trade-off accepted:** A single `GrantSet` may carry more than one narrow check needs.
- **Consequences:** One fetch, coherent cache, and check/scope/ui can never disagree about the same user.
- **When to reconsider:** If a user's grant set becomes large enough that "everything" is wasteful → re-introduce filtered endpoints (the API already supports filters).

### D10 — Version-based staleness + push invalidation

- **Context:** We want a **long L2 TTL** to be safe, and **revocation** to be near-instant.
- **Decision:** A global **policy version** is bumped on every change. Every cached value carries the version it was written with; a read validates `value.version >= currentVersion`. The RabbitMQ event (D6) pushes the new version so clients invalidate without waiting for TTL.
- **Implementation note (v1):** the bump + publish happen **after the admin transaction commits** (`@TransactionalEventListener(AFTER_COMMIT)`), so a client refetching in the publish→commit gap can never read uncommitted grants stamped with the new version.
- **Alternatives considered:** per-key deletion (needs a user→profile reverse index); TTL-only.
- **Trade-off accepted:** A global bump makes *all* logically-stale entries invalid until revalidated (a broad, if rare, invalidation).
- **Consequences:** Long L2 TTL is safe (real changes are pushed, not waited out); revocation is near-instant; revalidation is a tiny version read.
- **When to reconsider:** If changes are frequent enough that broad invalidation hurts → scope invalidation per profile/user (needs the reverse index).
- **Team review (2026-10-14):** revocation is near-instant **while the bus is healthy**. If the bus is **down**, we **deliberately keep** TTL-bounded staleness (L1 60s / L2 ≤ 15 min) rather than accelerating invalidation — accelerating it during an outage would amplify load on the PDP at the worst moment. **Stale-but-bounded is the graceful-degradation choice**, not an accident.

---

## 5. Trade-off summary

| What we **gain** | What we **give up** |
|---|---|
| One place to reason about & change access; consistent answers | A policy language / rule test harness (OPA/Cerbos) |
| A fast, cacheable decision path; no REST call per decision | Two extra runtimes (Redis, RabbitMQ) to operate |
| Developer ownership of enforcement (PEP) | Central enforcement of UI layout (D4) |
| Trivially correct, unit-testable RBAC core | Attribute-conditional grants in v1 (D3) |
| Guaranteed invalidation fan-out; near-instant revocation | A PDP that trusts the caller's profile set (D2) |
| Queryable audit on infra we already run | Large-scale security analytics out of the box (D8) |

**Net:** we optimize for **operability, consistency, and team ownership** at a known scale, and we keep **explicit, low-cost escape hatches** (D1, D3, D6, D8) for the growth cases we have deliberately deferred.

---

## 6. Consequences

- **Operational:** the service is a small stateless Spring app + Postgres + Redis + RabbitMQ. No sidecars, no policy engine. Fail-closed by default.
- **Team:** developers keep ownership of enforcement (PEP); the platform owns the PDP, the cache contract, and audit.
- **Security:** trust boundary is explicit (D2) and audited; revocation is near-instant (D10); no unscoped query can run (starter throws `AccessDeniedException` → 403 on missing scope).
- **Cost:** two extra components (Redis, RabbitMQ) vs. a single local TTL — accepted for consistency + revocation speed.

---

## 7. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| A client passes a **forged/stale profile set** (A1/D2) | Low (internal, S2S auth) | Starter reads profiles only from the verified token; S2S auth; decision audit records the set used |
| **Broad invalidation** after a change (D10) | Medium (change volume) | Changes are rare; L1 60s + version revalidation bounds impact; can scope invalidation later |
| **Audit write spike** during a hot period | Low (15k users) | Async decision writer + partitioned tables; backpressure/batching |
| **Key-convention drift** (D7) | Low (soft convention; enforcement deferred) | Central catalog + optional format lint/test |
| **UI not honoring visibility flags** (D4) | Medium | Front-end is the PEP; contract is a simple visible-boolean list; e2e tests per app |
| **Outgrowing static RBAC** (A2/D3) | Later | Escape hatch D3 (SpEL) or D1 (OPA/Cerbos) without client changes |

---

## 8. Team feedback (recorded 2026-10-14)

The review is complete. Each ask is resolved below; the affected decisions (D2, D6, D7, D8, D10) carry a matching **Team review** note in §4.

| # | Ask | Team answer | Disposition |
|---|-----|-------------|-------------|
| 1 | **Trust model (D2/A1)** | **Yes** — acceptable. | Confirmed. PDP trusts the authenticated caller; the starter enforces verified-token origin of the profile set. |
| 2 | **Revocation SLO (D10)** | Bounded staleness is acceptable; **do not accelerate invalidation while the bus is down** (it would amplify load during the outage). | Confirmed. Near-instant while the bus is healthy; **TTL-bounded graceful degradation** (L1 60s / L2 ≤ 15 min) when the bus is down. |
| 3 | **Broker choice (D6)** | Restate Kafka's benefits; keep RabbitMQ unless a benefit is decisive. | **Keep RabbitMQ.** Kafka's replay/durability/throughput benefits are not decisive for an ephemeral invalidation signal. Reuse Kafka only if the platform already runs it (ops consolidation). |
| 4 | **Permission keys (D7)** | Not a technical issue; keep key/payload (JSON value); schema-enforcement optional later. | **Hard contract = cacheable key → JSON payload** (unchanged). Naming is a soft convention; format enforcement deferred. |
| 5 | **Scope model (A3)** | **Yes, with constraints.** Where does the centralized schema live? | Confirmed with the dimension-set constraint. **Schema authored as YAML in the owning service's repo, registered into the PDP; Postgres remains the runtime source of truth** (the PDP does not read git directly). |
| 6 | **Audit retention (D8)** | **3 months** is fine. | **Rolling 3-month retention**; drop/archive partitions older than 3 months (job + runbook). |
| 7 | **Admin surface (A5)** | **API-only for v1.** | Confirmed. No admin UI. |

---

## 9. How to validate this design (testable properties)

These are the properties we will encode as tests once accepted — i.e., the design is defensible *because* it makes falsifiable claims:

1. **Union correctness:** for any user, `permissions(user) == ⋃ permissions(profile)` and scopes union per resource type.
2. **Mutual consistency:** `check`, `scope`, and `ui` for the same `(userId, profiles)` can never disagree (they derive from one `GrantSet`).
3. **Fail-closed:** on a genuine fault, `check → deny` and `scope`/`ui` → throw (→ 500); on a legitimate absence, `scope` → `AccessDeniedException` (→ 403) and `ui` → empty bundle — an unscoped query never runs.
4. **Revocation latency:** after a revoke + invalidation event, a client's next decision reflects it (near-instant with the bus; ≤ 60s without).
5. **Audit completeness:** every decision and every change is logged with actor/user/target/timestamp.
6. **Cache safety:** a stale (older-version) cached value is never served.

---

## 10. Decision log (session chronology)

1. Problem stated: attach **data scopes + templates** to profiles; central service returns **filters/scopes** and answers **functionality** checks for stateless clients; avoid reinventing the wheel.
2. Decomposed the need into **functionality checks** + **data scoping**; surveyed OPA, Cerbos, Casbin, Keycloak AuthZ, Oso/OpenFGA/Cedar, Palisade.
3. Team answered: Java/Spring; per-resource-type scopes; templates = UI elements; static (+ light conditional if cheap); audit mandatory; multiple profiles per user.
4. Converged on **RBAC**; clarified **templates** as UI permission bundles; produced `DESIGN.md`.
5. Converged on: **client passes profiles** (A1/D2); **Redis L2 from day 1** (D5); **RabbitMQ** invalidation (D6); **Postgres** audit (D8); **key conventions** (D7); **one cached GrantSet** (D9); **version + push invalidation** (D10).
6. This ADR captures the *why* and the trade-offs for review.
7. **2026-10-14 — Team review completed.** Trust model confirmed (D2/A1); revocation SLO = near-instant while the bus is healthy, **TTL-bounded graceful degradation** when the bus is down (D10); **RabbitMQ kept** after a Kafka-benefit analysis (D6); permission-key naming re-scoped as a soft convention with **key → JSON payload** as the hard contract and format enforcement deferred (D7); scope model confirmed **with the dimension-set constraint**, schema to live as **YAML in the owning service's repo** registered into the PDP (A3); audit retention = **3 months** (D8); admin = **API-only** for v1 (A5). ADR marked **Accepted**.

---

## 11. Status & next steps

- **Status: Accepted** (2026-10-14). Team feedback incorporated (§8); decisions D2/D6/D7/D8/D10 carry matching notes.
- **Next:** proceed to the remaining implementation items — Testcontainers integration tests, the PDP↔starter L2 key contract test, Prometheus metrics, and the partition roll-forward + **3-month retention** runbook. See `AGENTS.md` §11 for the ordered list.
- **Any change** to a decision after acceptance gets a new ADR (e.g. ADR-002) referencing this one, so the history stays explicit.
