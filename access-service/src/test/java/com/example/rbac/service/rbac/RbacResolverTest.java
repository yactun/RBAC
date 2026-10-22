package com.example.rbac.service.rbac;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the resolution semantics from ADR-001 §9 + ADR-002 (T1/S2):
 * permission union, Option A scope OR-merge (typed predicates), and
 * UI visibility-by-permission (template = catalog, attach does not grant).
 */
class RbacResolverTest {

    private final RbacResolver resolver = new RbacResolver();

    private static Scope.Filter in(String dim, Object... values) {
        return new Scope.Filter(dim, Scope.Op.IN, List.of(values));
    }

    @Test
    void permissionsAreTheUnionAcrossProfiles() {
        ProfileGrants a = new ProfileGrants("support", Set.of("orders:read"), Map.of(), List.of());
        ProfileGrants b = new ProfileGrants("manager", Set.of("orders:read", "orders:refund"), Map.of(), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        assertTrue(r.permissions().containsAll(Set.of("orders:read", "orders:refund")));
        assertEquals(2, r.permissions().size());
    }

    @Test
    void grantedBy_mapsEachKeyToTheProfilesGrantingIt() {
        // REVIEW D2: provenance is resolved once at compute time and carried in the
        // GrantSet, so /v1/check no longer re-queries the DB per check.
        ProfileGrants a = new ProfileGrants("support", Set.of("orders:read"), Map.of(), List.of());
        ProfileGrants b = new ProfileGrants("manager", Set.of("orders:read", "orders:refund"), Map.of(), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        assertEquals(List.of("support", "manager"), r.grantedBy().get("orders:read"));
        assertEquals(List.of("manager"), r.grantedBy().get("orders:refund"));
        assertEquals(2, r.grantedBy().size());
    }

    @Test
    void inFiltersAreOrMergedPerDimension_unionOfValues() {
        ProfileGrants a = new ProfileGrants("support", Set.of(),
                Map.of("orders", List.of(in("orgIds", 1, 2), in("regions", "EU"))), List.of());
        ProfileGrants b = new ProfileGrants("manager", Set.of(),
                Map.of("orders", List.of(in("orgIds", 3), in("regions", "US", "EU"))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope orders = r.scopes().get("orders");
        assertEquals(List.of(1, 2, 3), orders.values("orgIds"));
        assertEquals(List.of("EU", "US"), orders.values("regions")); // OR = union, deduped, order preserved
    }

    @Test
    void inEmptyIsDeny_andUnionsWithANonEmptyGrant() {
        // Profile A grants nothing (IN [] = deny), profile B grants org 3.
        // OR-union = org 3 (the wider grant wins — a deny from one profile never
        // shrinks another profile's grant in a union).
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(in("orgIds"))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(in("orgIds", 3))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        assertEquals(List.of(3), r.scopes().get("orders").values("orgIds"));
    }

    @Test
    void allWinsOverIn_whenOrMerged() {
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(new Scope.Filter("orgIds", Scope.Op.ALL, List.of()))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(in("orgIds", 1, 2))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope.Filter merged = r.scopes().get("orders").filter("orgIds").orElseThrow();
        assertEquals(Scope.Op.ALL, merged.op());
        assertTrue(r.scopes().get("orders").isAll("orgIds"));
    }

    @Test
    void eqOrEq_becomesInUnlessEqual() {
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(new Scope.Filter("owner", Scope.Op.EQ, List.of("alice")))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(new Scope.Filter("owner", Scope.Op.EQ, List.of("bob")))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope.Filter merged = r.scopes().get("orders").filter("owner").orElseThrow();
        assertEquals(Scope.Op.IN, merged.op());
        assertEquals(List.of("alice", "bob"), merged.values());
    }

    @Test
    void rangeFiltersMergeToTheWidestBound() {
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(
                        new Scope.Filter("createdAt", Scope.Op.GTE, List.of("2026-01-01")),
                        new Scope.Filter("total", Scope.Op.LT, List.of(100)))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(
                        new Scope.Filter("createdAt", Scope.Op.GTE, List.of("2025-06-01")),
                        new Scope.Filter("total", Scope.Op.LT, List.of(500)))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        assertEquals("2025-06-01", r.scopes().get("orders").filter("createdAt").orElseThrow().single());
        assertEquals(500, r.scopes().get("orders").filter("total").orElseThrow().single());
    }

    @Test
    void betweenFiltersMergeToTheCoveringInterval() {
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(
                        new Scope.Filter("total", Scope.Op.BETWEEN, List.of(10, 100)))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(
                        new Scope.Filter("total", Scope.Op.BETWEEN, List.of(50, 500)))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope.Filter merged = r.scopes().get("orders").filter("total").orElseThrow();
        assertEquals(10, merged.low());
        assertEquals(500, merged.high());
    }

    @Test
    void mixedOpsOnOneDimension_failClosed() {
        // IN vs GT on the same dimension: the OR-union is not one expressible filter
        // in v1 — refuse (loud 500 at compute, never cached) instead of guessing.
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(in("orgIds", 1, 2))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(
                        new Scope.Filter("orgIds", Scope.Op.GT, List.of(3)))), List.of());

        assertThrows(IllegalStateException.class, () -> resolver.resolve(List.of(a, b), null, Set.of()));
    }

    @Test
    void crossDimensionScopesAreCombinedAsAnd_soAllProfilesMustShareDimensions() {
        // A scopes only on orgIds, B only on regions. The merge yields
        // [orgIds IN (1), regions IN (EU)], which a client applying all dimensions
        // as AND reads as "org 1 AND EU" — narrower than either profile alone. This is
        // the documented contract limit: every profile scoping a resource type must
        // declare the same dimension set (enforced at authoring, ADR A3).
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", List.of(in("orgIds", 1))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", List.of(in("regions", "EU"))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope orders = r.scopes().get("orders");
        assertEquals(List.of(1), orders.values("orgIds"));
        assertEquals(List.of("EU"), orders.values("regions"));
    }

    @Test
    void scopesAreFilteredToRequestedResourceTypes() {
        ProfileGrants a = new ProfileGrants("support", Set.of(),
                Map.of("orders", List.of(in("orgIds", 1)),
                        "invoices", List.of(in("orgIds", 9))),
                List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a), null, Set.of("orders"));

        assertTrue(r.scopes().containsKey("orders"));
        assertFalse(r.scopes().containsKey("invoices"));
    }

    @Test
    void uiElementIsVisibleOnlyIfItsGatingPermissionIsGranted() {
        UiElementDef visible = new UiElementDef("web", "tab.invoices", "invoices:read");
        UiElementDef hidden = new UiElementDef("web", "tab.refunds", "refunds:read");
        // T1 (ADR-002): the profile's permissions carry ONLY invoices:read — the
        // template contributed the elements, not the grants.
        ProfileGrants support = new ProfileGrants("support", Set.of("invoices:read"), Map.of(), List.of(visible, hidden));

        RbacResolver.Resolved r = resolver.resolve(List.of(support), Set.of("web"), Set.of());

        UiElements web = r.ui().get("web");
        UiElements.Element inv = web.elements().stream().filter(e -> e.key().equals("tab.invoices")).findFirst().orElseThrow();
        UiElements.Element ref = web.elements().stream().filter(e -> e.key().equals("tab.refunds")).findFirst().orElseThrow();
        assertTrue(inv.visible());
        assertFalse(ref.visible());
    }

    @Test
    void emptyProfilesYieldEmptyGrants() {
        RbacResolver.Resolved r = resolver.resolve(List.of(), Set.of("web"), Set.of());
        assertTrue(r.permissions().isEmpty());
        assertTrue(r.scopes().isEmpty());
    }

    @Test
    void nullAppsResolvesUiForEveryAppReferencedByTheTemplates() {
        // The cached GrantSet (ADR D9) must carry a UI bundle for EVERY app the
        // profiles' templates reference, so the client can derive ui(app) locally.
        UiElementDef web = new UiElementDef("web", "tab.invoices", "invoices:read");
        UiElementDef mobile = new UiElementDef("mobile", "tab.orders", "orders:refund");
        ProfileGrants a = new ProfileGrants("a", Set.of("invoices:read"), Map.of(), List.of(web));
        ProfileGrants b = new ProfileGrants("b", Set.of(), Map.of(), List.of(mobile)); // gating perm not granted -> hidden

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        assertTrue(r.ui().containsKey("web"));
        assertTrue(r.ui().containsKey("mobile"));
        assertTrue(r.ui().get("web").elements().get(0).visible());
        assertFalse(r.ui().get("mobile").elements().get(0).visible());
    }
}
