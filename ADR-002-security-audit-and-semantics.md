# ADR-002 — Security enforcement, decision audit, and semantics corrections

| | |
|---|---|
| **Status** | **Accepted** (2026-10-16) — all §7 questions answered by the team (see §7 Team-review notes + §9 decision log). **S1/T1/S2/R1 implemented 2026-10-17 (A1 descoped)** — build green (88 tests). |
| **Date** | 2026-10-16 |
| **Deciders** | Platform / Identity team (+ compliance owner for R1) |
| **Related** | `ADR-001-user-access-service.md` (superseded-in-part: D4, D8), `DESIGN.md`, `REVIEW-implementation-vs-design.md` (evidence) |
| **Supersedes** | ADR-001 **D4** (template semantics, re-decided in T1) and the D8 team-review note on retention (re-decided in R1). D2's *trust model* is unchanged — S1 **enforces its stated mitigation** rather than changing it. |

ADR-001 (Accepted 2026-10-14) remains the architecture of record. This ADR records **five follow-on decisions** forced by the implementation review (`REVIEW-implementation-vs-design.md`): four of them correct a place where the implementation contradicts ADR-001's intent, and one (S1) turns a stated mitigation into an enforced control. Each decision is given as **Context → Recommendation → Alternatives → Trade-off → Consequences → When to reconsider**, and §7 lists the **open questions the team must answer** before the ADR is Accepted and the gated code (Tracks A/B) proceeds.

> **Why a new ADR and not just code:** per ADR-001 §11 ("any change to a decision after acceptance gets a new ADR") and the standing instruction in `AGENTS.md`, T1/A1/S2/R1 change behavior the team already accepted (D4/D8). They are drafted here as *recommendations* and must be confirmed in §7 before implementation.

---

## 1. Summary

The v1 architecture holds (thin PDP, one cached `GrantSet`, version + push invalidation, fail-closed client). The review found a handful of places where the **code doesn't do what the docs claim**, and a few that **weaken the security story that is the reason this service exists**. This ADR decides how to fix the ones that are *decisions* (not plain bugs):

- **S1 — Enforce S2S authentication on the PDP** and take the **actor from the verified principal**, not a header. (Enforces ADR-001 D2's mitigation, which today is unenforced.)
- **T1 — A template is a *layout catalog*, not a grant-bundle.** Attaching it must **not** grant its element permissions; `visible` stays permission-driven. Fixes the "revoke is a no-op / `visible:false` unreachable" problem.
- **A1 — Move decision audit to the PEP** (starter emits batched decision records), resolving the D8↔D9 contradiction that leaves `audit_decision` empty in production.
- **S2 — Make scopes fail-closed:** `Scope.values()` throws on an unknown dimension; empty list = explicit wildcard; add a `DELETE …/scopes/{rt}` endpoint.
- **R1 — Retention split:** keep `audit_change` ≥ 1 year (compliance), `audit_decision` at 3 months; add `granted_at` to the live M2M rows.

The plain-bug items (Redis single-point-of-failure, fresh-replica stale L2, subscriber not surviving broker restart, the unbounded durable queue, no timeouts, JSON-escaping, 404/409, etc.) are **not decisions** — they are tracked as code work in the review page, not here.

---

## 2. Context & problem

ADR-001 was accepted 2026-10-14 and the implementation is green (25 tests). The implementation-vs-design review (`REVIEW-implementation-vs-design.md`) then verified, against source, that:

- The **PDP has no authentication at all** and the admin API trusts the `X-Actor` header (default `"unknown"`). ADR-001 D2's mitigation — "the PDP sits behind service-to-service auth" — is **stated but unenforced**.
- **Decision audit is effectively empty in production**: the starter only ever calls `/v1/grants` and derives `check` locally, so `audit.decision` (the only writer of `audit_decision`) is never reached on the production path. D8 ("every check is audited") and D9 ("client derives check locally") **contradict each other**.
- **`visible:false` can never be produced** and **revoking a permission is a silent no-op** when a template also grants it, because `GrantLoader` adds every template element's permission to the grant set and `RbacResolver` then checks "visible iff granted" — always true.
- **Scopes can fail open**: a missing/typo dimension returns `[]`, and the documented pattern `if (!orgIds.isEmpty()) …` turns `[]` into *no filter = all rows*.
- **Change-audit is dropped at 3 months**, so "who granted admin to X?" is no longer answerable for a still-active grant — at odds with typical SOX/ISO 27001 retention.

Each of these is a **decision** (it changes accepted behavior), not a bug to fix silently. This ADR records the recommended decisions for team confirmation.

---

## 3. Decisions

### S1 — Enforce service-to-service auth on the PDP; actor from the verified principal

- **Context:** The PDP is an internet- or LAN-reachable Spring service with **no `spring-security` dependency**. Anyone who can reach port 8080 can call `POST /v1/admin/profiles/{name}/permissions` and grant themselves anything. The change-audit records whatever `X-Actor` header is sent (default `"unknown"`). This defeats both "audit trail is mandatory" and the D2 trust model. ADR-001 D2 already *declares* the mitigation ("the PDP sits behind service-to-service auth") — this decision **implements** it.
- **Recommendation:**
  1. **Authenticate every PDP call** with a bearer token validated against the platform's existing identity issuer (OAuth2 **client-credentials** / service-to-service). The PDP runs as an **OAuth2 resource server**; it does not issue tokens.
  2. **Authorize `/v1/admin/**` separately** — require an elevated authority (a dedicated `role:rbac-admin` scope/claim) distinct from the decision API (`/v1/check`, `/v1/grants`, `/v1/version`). Decision callers and grant authors are different trust levels.
  3. **Take the audit actor from the verified principal** (the authenticated service/client identity), **not** from the `X-Actor` header. Drop the `X-Actor` default-`"unknown"` path. (A human "on behalf of" can still be carried in a claim if needed, but it must be *asserted by the verified principal*, not a free header.)
  4. Keep the PDP **stateless about the end-user** (D2 unchanged): it still trusts the *profile set* the (now-authenticated) caller passes; it now additionally knows *which caller* passed it.
- **Alternatives considered:**
  - **mTLS / service-mesh authN** (Istio/Linkerd) — transport-level, no per-call token; but actor/authority still needs a claim, and it couples us to the mesh. Acceptable *in addition to*, not as a substitute for, authZ.
  - **A shared static API token** per service — simpler, but no per-service principal for audit and one leaked token = full admin. Weaker.
  - **Leave as-is (network policy only)** — "not routable from untrusted networks" is a deployment control, not an in-service one; it doesn't stop a compromised internal service or a mis-scoped LB. Insufficient for an admin surface.
- **Trade-off accepted:** Every caller must hold a valid client-credentials token; admin callers need the elevated scope. Slightly more setup (token acquisition in the starter and any admin tooling) in exchange for a real, auditable trust boundary.
- **Consequences:** Admin API is no longer open; the audit `actor` is a verified identity; the decision path gains an auth check (cheap, cached JWKS). The starter's `AccessClient` must present a client token when calling the PDP (see A1/T1 for how the starter changes anyway).
- **When to reconsider:** If the platform mandates a specific IdP flow (e.g. SPIFFE workload identity / mTLS-only), conform to it — the *principle* (verified principal + separated admin authZ + actor-from-principal) is the invariant, not the token format.
- **Open (team):** **Which identity issuer** do we validate against (existing IDM / Keycloak / other), and do we already run a mesh (in which case authN may be mesh-level and we focus on authZ + actor)? *(§7 Q1.)*
- **✅ Team decision (2026-10-16):** Platform is **Kubernetes**. S2S = **mesh mTLS + workload identity** (primary) with **OAuth2 client-credentials as fallback**; admin via **OIDC**. Implement as a **config-gated, mode-switchable** auth layer (`mesh` | `jwt` | `disabled`) so the PDP works on both paths; the *invariant* (verified principal + separated admin authZ + actor-from-principal) is enforced regardless of mode.
- **✅ Implemented (2026-10-17):** config-gated on `rbac.pdp.auth.{enabled, mode, admin-authority, admin-principals}` (`enabled` defaults to `false` + a loud startup WARN, so existing deployments/tests are unchanged). `mode=mesh`: `MeshAuthFilter` takes the SPIFFE ID from the mesh's `Forwarded-Client-Cert` header; `mode=jwt`: standard Spring OAuth2 resource server (token `scope`/`scp` values map to **plain** authorities, so `scope=rbac-admin` satisfies the admin matcher). `/v1/admin/**` requires the admin authority (403 for an authenticated non-admin; 401 without identity); actuator health/info + `/error` stay open. The audit actor is **always** the verified principal (`AuthPrincipal`); `X-Actor` is honored **only** in disabled/dev mode. The starter presents `rbac.client.pdp-token` (or a host `BearerTokenProvider` bean) as `Authorization: Bearer …` on every PDP call. Pinned by `MeshAuthFilterTest`, `AuthPrincipalTest`, `AccessServiceAuthMeshTest`, `AccessServiceAuthJwtTest`, `BearerTokenWiringTest` (88-test build green).

### T1 — A template is a **layout catalog**, not a grant-bundle

- **Context:** `GrantLoader.toGrants` adds **every template element's permission** to the grant set, then `RbacResolver.computeUi` checks "visible iff that permission is granted" — which is then **always true** because the loader just granted it. Consequences: `visible:false` is unreachable; **`DELETE …/permissions/{key}` silently does nothing** if any attached template contains that key; and there is **no detach-template endpoint**. The design's own example (`DESIGN §6`, `{tab.payments:false}`) and test (`RbacResolverTest.uiElementIsVisibleOnlyIfItsGatingPermissionIsGranted`) assume `visible:false` is possible — the code can't produce it. This contradicts ADR-001 D4's intent ("the PDP returns visibility; the front-end owns rendering").
- **Recommendation:** A template is a **named catalog of UI elements for one app** — a statement of what *may appear* — and **attaching it does NOT grant the element permissions**. `visible` remains driven by separately-granted permissions:
  1. **Fix `GrantLoader`** so it does **not** add template-element permissions to the grant set; it only contributes the `UiElementDef` list (app, elementKey, gating-permission).
  2. **Keep `RbacResolver.computeUi`** as "element visible iff its gating permission is in the union" — which then becomes correct.
  3. **Add the missing revoke op:** `DELETE /v1/admin/profiles/{name}/templates/{appId}/{templateName}` (detach). Revoking the *permission* (existing `DELETE …/permissions/{key}`) now correctly flips `visible` to `false` while the element remains in the catalog (progressive disclosure preserved).
  4. Front-ends that want an element *functional* must also grant the corresponding service permission — one mechanism, no hidden grants.
- **Alternatives considered:**
  - **Grant-bundle semantics** (attaching a template *grants* its element permissions; drop the `visible` flag). Simpler mental model, but then "visibility" = "granted" (no present-but-hidden state), and revoking a permission shared with a template is still a no-op — the exact bug we're fixing. Rejected.
  - **Status quo + a "detach" endpoint only.** Leaves the self-granting loader, so `visible` is still always true. Insufficient.
- **Trade-off accepted:** Authors must grant element permissions explicitly to make an element usable (not just listed). Slightly more authoring in exchange for `visible:false` working and revocation being effective.
- **Consequences:** `RbacResolverTest` continues to pass (it already encodes the intended semantics); the **integration test** that asserts template permissions appear in `GrantSet.permissions` must be updated to assert the *opposite* (elements listed, not granted) — a deliberate, visible break, which is the point.
- **When to reconsider:** If the platform must centrally *own and enforce* layout (the D4 reconsider trigger), revisit — but that's a different (bigger) change.
- **Open (team):** Confirm "catalog, not bundle" as the intended semantics (it matches the existing design example and test). *(§7 Q2.)*
- **✅ Team decision (2026-10-16): ACCEPTED.** Catalog semantics; attach does not grant. Rationale: *better no data than a data breach — a user who lacks access raises a support ticket.*
- **✅ Implemented (2026-10-17, `ed2d13e`):** `GrantLoader` no longer adds template-element permissions to the grant set; new `DELETE /v1/admin/profiles/{name}/templates/{appId}/{templateName}` (idempotent detach, audited, invalidates). `RbacResolverTest` + the `decisionFlow` IT pin the full round-trip: attach → element listed but **not** granted (hidden); grant the permission → visible; revoke it → hidden again.

### A1 — Decision audit lives at the **PEP**; PDP keeps change-audit + grant-issuance

- **Context:** ADR-001 D8 says "every check is audited" (`audit_decision`); D9 says the client derives `check` locally from the cached `GrantSet`. The starter only ever calls `/v1/grants`, so `audit.decision` — the **only** writer of `audit_decision` — is never invoked on the production path. **In production, decision audit is empty.** This is a direct D8↔D9 contradiction, and it undercuts "audit trail is mandatory."
- **Recommendation:** Move **decision audit to the PEP** (the starter, where the decision actually happens), emitted **asynchronously in batches** so it is never on the hot path:
  1. The starter buffers `(userId, profiles, op, target, allowed, version, requestId, service, ts)` and flushes **batched** to `POST /v1/audit/decision` (a new, **authenticated** endpoint that writes `audit_decision` reusing the existing partitioned table + async-safe path).
  2. Keep **change-audit + grant-set issuance** on the PDP (unchanged).
  3. The PDP's existing `/v1/check` + its inline `audit.decision` can remain for direct-API callers, but it is no longer the *primary* decision-audit path.
- **Alternatives considered:**
  - **OTel log stream** (decision records as logs via OTLP) — aligns with the OTel choice already made for metrics and is the OPA/Cerbos "decision log" idiom; but it sends audit to a log/analytics sink, not the queryable Postgres table the design promises ("an audit is a query, not archaeology"). *Good fallback / later migration.*
  - **RabbitMQ decision topic** — reuses the broker; but adds a consumer + ordering/dedup concerns for what is fundamentally a fire-and-forget record.
  - **Redefine the requirement** to "grant-set issuance + change audit only" (no per-check audit). Simplest, but descopes the audit trail the team mandated. Rejected unless the team explicitly descopes (see §7 Q3).
- **Trade-off accepted:** The starter gains a small async batch emitter + a new PDP endpoint; in exchange decision audit is actually populated and co-located with where decisions occur.
- **Consequences:** `audit_decision` reflects real production decisions; the "async, never on the hot path" guarantee of D8 is preserved (batched, fire-and-forget with a bounded buffer + drop-and-metric on overflow, mirroring the existing swallow-and-log posture).
- **When to reconsider:** If decision volume or the need for streaming analytics grows → switch the transport to an OTLP log stream / Kafka without changing the record shape.
- **Open (team):** Confirm PEP-side batched decision audit (vs. descoping, vs. OTLP-first). *(§7 Q3.)*
- **✅ Team decision (2026-10-16): option (c) — DESCOPED.** Audit covers **grant issuance + changes only**, not per-check decisions. This **overrides the (a) recommendation above**. Consequence: **no PEP batch emitter or new `/v1/audit/decision` endpoint is built**; the raw `/v1/check` + its inline `audit.decision` may remain for direct-API callers, and `audit_decision` is no longer a mandated production control. D8↔D9 resolved by choosing D9 (local derivation) and dropping the per-check audit obligation.
- **✅ Implemented (2026-10-17, `6f53a5b`):** as descoped — nothing built. The raw `/v1/check` + inline `audit.decision` remain for direct-API callers; the descoping is recorded in `DESIGN.md` §8.

### S2 — Scopes fail-closed: throw on unknown dimension; empty = wildcard; add DELETE

- **Context:** `Scope.values(dimension)` returns `List.of()` for **both** a missing dimension **and** a typo (`s.values("orgId")`). The documented pattern (`DESIGN §6`: `if (!orgIds.isEmpty()) b.and(… in(orgIds))`) then treats `[]` as **no filter = all rows**. Three paths lead there: (a) the dimension is authored as `[]` (also the *only* way to "revoke" a scope — there's no DELETE scope endpoint), (b) a client misspells the dimension, (c) the schema later gains a dimension older profiles lack. This is a **fail-open** in a security control.
- **Recommendation:**
  1. **`Scope.values(dimension)` throws** (e.g. `UnknownDimensionException` → 500/fail-closed) if `dimension` is **not present** in the scope's declared filter keys. A typo can no longer silently mean "all rows."
  2. An **empty list for a *known* dimension** = **explicit wildcard** (all), documented as such. Distinguish "dimension absent" (error) from "dimension present but empty" (all).
  3. **Add `DELETE /v1/admin/profiles/{name}/scopes/{resourceType}`** to *remove* a scope entirely (distinct from "dimension present but empty = all"). Revocation is now an explicit op.
  4. **Fix the `DESIGN §6` example** so it does not equate "empty list" with "no filter."
- **Alternatives considered:**
  - **Return `Optional.empty()` / sentinel** instead of throwing — pushes the failure into every caller and is easy to mishandle (`if (present) filter`). Throwing is the fail-closed default.
  - **Status quo** — leaves a live fail-open. Rejected.
- **Trade-off accepted:** **Breaking change** for any client calling `.values()` with a wrong key and relying on `[]`→no-filter. That reliance *is* the bug; breaking it is intentional. Clients must use declared dimension keys.
- **Consequences:** Typos surface loudly (fail-closed) instead of widening access; scope revocation has an explicit endpoint; the design example is corrected.
- **When to reconsider:** If a legitimate "optional dimension" pattern emerges (some callers don't filter on a dimension at all), model it explicitly (e.g. an `ALL` marker value) rather than silently treating absence as all.
- **Open (team):** Confirm the fail-closed semantics + the breaking change. *(§7 Q4.)*
- **✅ Team decision (2026-10-16): CONFIRMED, using canonical format Option A.** Typed predicates with a closed op set (`IN/EQ/GT/GTE/LT/LTE/BETWEEN`), flat AND, `IN` empty = deny, explicit `ALL` for all-rows, unknown dim/op = throw. Starter validates the PDP response before any query; the developer owns the final filter mapping.
- **✅ Implemented (2026-10-17, `2bd63ab`):** `Scope` is now `{resourceType, filters:[{dimension, op, values}]}` with the closed op set (arity enforced in the compact constructors, so Jackson (de)serialization fails closed on a corrupt/stale value); `values(dim)`/`isAll(dim)` throw `UnknownDimensionException` for an undeclared dimension; `IN []` = deny, `ALL` = all-rows; `RbacResolver` OR-merges per dimension (mixed ops throw — a loud 500, never cached); `AdminService.setScope` validates the dimension set + ops against `resource_type.dimension_schema` (400) and `DELETE …/scopes/{rt}` exists. Pinned by `ScopeTest` (9), `RbacResolverTest`, `AdminServiceTest` + ITs.

### R1 — Retention split: `audit_change` ≥ 1 year, `audit_decision` 3 months; add `granted_at`

- **Context:** The team set **rolling 3-month** retention for *both* decision and change audit (ADR-001 D8, 2026-10-14), and `ops/rotate_audit_partitions.sql` drops **both** past 3 months. But `audit_change` is the record that answers **"who granted admin to X, and when?"** for a grant that is *still active* — and a 3-month window can no longer answer it. SOX / ISO 27001 and similar typically require **at least a year** for authorization/audit-of-change records. The change-audit table is tiny (one row per admin mutation), so keeping it long is cheap; the decision-audit table is the high-volume one.
- **Recommendation:**
  1. **Split the retention windows:** `audit_decision` → **3 months** (unchanged, high volume); `audit_change` → **≥ 1 year** (compliance). Update `ops/rotate_audit_partitions.sql` + `RUNBOOK-audit-partitions.md` to drop the two tables on **separate** rolling windows.
  2. **Add `granted_at` (and `granted_by`) to the live M2M rows** (`profile_permission`, `profile_scope`, `profile_template`) so the *current* grant's origin is answerable from the model itself, even after the change-audit rolls off.
- **Alternatives considered:**
  - **Keep both at 3 months** (status quo). Undercuts "who granted this active grant." Rejected.
  - **Keep both at 1 year.** Simplest to operate, but retains high-volume decision data longer than needed/cost-justified.
  - **Archive `audit_change` to cold storage at 3 months** instead of dropping. Viable, but the runbook already documents an archive option; a longer *online* window is simpler and the table is small.
- **Trade-off accepted:** Slightly more Postgres storage for the (tiny) change-audit and two new columns on the M2M tables; in exchange the active-grant provenance survives.
- **Consequences:** "Who granted admin to X?" stays answerable for ≥ 1 year; decision-audit cost bounded; the partition job is table-aware.
- **When to reconsider:** If the compliance owner specifies a different floor (e.g. 7 years for SOX in some scopes), set the `audit_change` window to that.
- **Open (team/compliance):** Confirm the exact `audit_change` retention floor and whether `granted_by` (actor) is required on the M2M rows. *(§7 Q5.)*
- **✅ Team decision (2026-10-16): 1-year floor adopted** for `audit_change` (SOX/ISO 27001 norm); `audit_decision` stays 3 months; add `granted_at` + `granted_by` to the live M2M rows.
- **✅ Implemented (2026-10-17, `6f53a5b`):** `V4__grant_provenance.sql` adds `granted_at`/`granted_by` to `profile_permission`/`profile_scope`/`profile_template` (stamped by `AdminService` on every grant mutation); `ops/rotate_audit_partitions.sql` + `RUNBOOK-audit-partitions.md` now drop on **separate** rolling windows (`audit_decision` 3 months, `audit_change` 12 months), verified on real PostgreSQL 16.

---

## 4. Trade-off summary

| What we **gain** | What we **give up** |
|---|---|
| A real, auditable trust boundary; admin no longer open (S1) | Token acquisition in the starter + admin tooling (S1) |
| `visible:false` works; revocation is effective; no hidden grants (T1) | Authors must grant element permissions explicitly (T1) |
| Decision audit actually populated, at the PEP (A1) | A small async batch emitter + one new PDP endpoint (A1) |
| Scope typos fail closed; explicit scope revocation (S2) | A **breaking change** to `Scope.values()` semantics (S2) |
| Active-grant provenance survives ≥ 1 yr (R1) | More storage for change-audit; two M2M columns (R1) |

**Net:** we convert ADR-001's *stated* guarantees (auth, mandatory audit, effective revocation, queryable change history) from "documented intent" into **enforced, testable behavior**, at modest setup cost.

---

## 5. Consequences

- **Security:** S1 is the load-bearing one — it is what makes the rest defensible. Until S1 lands, the admin surface must be treated as **untrusted-network-only**.
- **Client contract:** T1 changes what `GrantSet.permissions` contains (template element perms no longer self-granted) — a deliberate, test-visible break. S2 changes `Scope.values()` semantics — another deliberate break. Both need a note in the client-facing docs + the affected tests updated.
- **Audit:** A1 populates `audit_decision` in production; R1 makes change-audit durable. The partition runbook becomes table-aware.
- **Build/test:** Each gated item ships with the regression test the review lists as missing (unauthenticated admin, template-revoke, empty/misspelled scope, decision-audit-in-PEP).

---

## 6. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| **S1** breaks existing callers that don't present a token | Medium | Ship behind a config flag; document token requirement; keep the decision API + admin API scopes distinct |
| **T1** hides elements that were "implicitly granted" | Medium | Authoring migration: grant the element permissions for any template that relied on self-grant; pinned by an updated IT |
| **A1** decision-audit batch loss under PEP load | Low | Bounded buffer + drop-and-metric (swallow-and-log posture already in `AuditService`); the record is best-effort by design |
| **S2** breaking change surprises a client | Medium | Mark breaking in the release notes; the old behavior was a fail-open, so the "regression" is a security fix |
| **R1** storage growth on change-audit | Low | Table is tiny (one row/admin mutation); window is configurable |

---

## 7. Open questions for the team (must be answered before the ADR is Accepted)

| # | Question | Options | Why it matters |
|---|----------|---------|----------------|
| **Q1 (S1)** | **Which identity issuer** do we validate PDP tokens against, and do we already run a service mesh (authN at the mesh)? | (a) OAuth2 client-credentials against existing IDM/Keycloak; (b) mTLS/SPIFFE via mesh + claim for actor | Determines the exact S1 implementation; the *invariant* (verified principal + separated admin authZ + actor-from-principal) holds either way |
| **Q2 (T1)** | Confirm **template = layout catalog** (attaching does *not* grant element perms)? | (a) catalog (recommended); (b) grant-bundle | Changes the client contract (what's in `GrantSet.permissions`) |
| **Q3 (A1)** | Where does **decision audit** live? | (a) PEP batched `POST /v1/audit/decision` (recommended); (b) OTLP log stream; (c) **descoped** to issuance+change only | Resolves the D8↔D9 contradiction; (c) descopes a mandated control |
| **Q4 (S2)** | Confirm scopes **fail closed** (throw on unknown dim; `[]` = explicit wildcard; add DELETE)? | (a) as recommended; (b) keep `[]` = no-filter | A **breaking change**; the status quo is a fail-open |
| **Q5 (R1)** | What is the **`audit_change` retention floor** (compliance), and is `granted_by` required on the M2M rows? | (a) ≥ 1 yr + `granted_at`/`granted_by` (recommended); (b) other floor per compliance | SOX/ISO 27001 typically ≥ 1 yr; needs the compliance owner |

> **Process:** answers to Q1–Q5 are recorded as **Team review** notes in §3 (matching the ADR-001 §8 pattern), the Status moves to **Accepted**, and only then do the gated items (S1, T1, A1, S2, R1) get implemented with their regression tests. The non-gated code fixes (Redis SPOF, fresh-replica L2, subscriber durability, durable-queue leak, timeouts, JSON-escaping, 404/409, cache-key-on-profileSet, transactional outbox) proceed on the review page's plan and need **no** ADR.

### Team review — answers (2026-10-16)

- **Q1 (S1):** We run **Kubernetes**. Primary S2S path = **service-mesh mTLS + workload identity** (e.g. Istio Ambient / SPIFFE); **fall back to OAuth2 client-credentials** if a mesh is not available. Admin (human/CI) via **OIDC**. Invariant holds either way: verified principal + separated admin authZ + actor-from-principal.
- **Q2 (T1):** **Accepted — catalog, not grant-bundle.** Attaching a template does *not* grant its element permissions. Rationale (team): *better no data than a data breach; a user who lacks access raises a support ticket.*
- **Q3 (A1):** **Descoped (option c)** — audit covers **grant issuance + changes only**, *not* per-check decisions. This **overrides the §3-A1 recommendation (option a)**. The raw `/v1/check` + its inline decision audit may remain for direct-API callers, but per-check audit on the production (starter) path is **out of scope by decision**.
- **Q4 (S2):** **Fail-closed confirmed (option a), using canonical format Option A** (typed predicates, closed op set `IN/EQ/GT/GTE/LT/LTE/BETWEEN`, flat AND, `IN` empty = deny, explicit `ALL` for all-rows, unknown dim/op = throw). Starter validates the PDP response before it reaches any query; the developer owns the final filter mapping.
- **Q5 (R1):** **1-year retention floor adopted** for `audit_change` (industry norm, SOX/ISO 27001); `audit_decision` stays 3 months; add `granted_at`/`granted_by` to the live M2M rows.

---

## 8. How to validate (testable properties, once accepted)

1. **S1:** unauthenticated `POST /v1/admin/**` → **401**; authenticated caller without the admin authority → **403**; the `audit_change.actor` equals the **verified principal**, never a free `X-Actor` header value.
2. **T1:** after attaching a template, `GrantSet.permissions` does **not** contain the element permissions; `ui(app)` lists the elements with `visible:false` until the permission is separately granted; after `DELETE …/permissions/{key}`, `visible` flips to `false`; `DELETE …/templates/…` detaches.
3. **A1:** a production `check()` via the starter produces an `audit_decision` row (batched), with the correct `userId/op/target/allowed/version/service`.
4. **S2:** `Scope.values("orgId")` (misspelled) **throws**; `values("orgIds")` with `[]` = all; `DELETE …/scopes/{rt}` removes the scope.
5. **R1:** `audit_change` partitions older than the (≥1 yr) window are retained while `audit_decision` partitions older than 3 months are dropped; `profile_permission.granted_at` is set on grant.

---

## 9. Decision log

1. 2026-10-16 — Implementation-vs-design review completed; claims verified against source (see `REVIEW-implementation-vs-design.md`).
2. 2026-10-16 — This ADR drafted with **recommended** decisions for S1/T1/A1/S2/R1; **Proposed** pending team answers to §7 (Q1–Q5).
3. 2026-10-16 — **Team answers recorded** (§7 Team-review notes): Q1 = K8s, mesh mTLS primary + OAuth2 client-credentials fallback + OIDC admin; Q2 = catalog (accepted); **Q3 = DESCOPED (option c, overrides the (a) recommendation)**; Q4 = fail-closed, canonical **Option A**; Q5 = 1-year floor + `granted_at`/`granted_by`. **Status → Accepted.** Gated implementation proceeds per `REVIEW-implementation-vs-design.md` Tracks A/B.

---

## 10. Status & next steps

- **Status: ACCEPTED** (2026-10-16). All §7 questions answered by the team (see §7 Team-review notes).
- **On acceptance:** implement S1, T1, S2, R1 with their §8 regression tests; **A1 is descoped** (team chose option c — no PEP batch emitter; audit = issuance + changes only). Update `DESIGN.md` (template semantics §4.4, scope §6, audit §8) and `AGENTS.md` to keep the three artifacts in agreement; record the new gotchas.
- **Not gated by this ADR** (proceed on the review plan): Redis SPOF bypass, fresh-replica L2 seeding, subscriber durability, durable-queue leak, timeouts/stampede, JSON-escaping, 404/409, **cache-key-on-profileSet**, **transactional outbox (done — `812b3a3`)**. See `REVIEW-implementation-vs-design.md`.
