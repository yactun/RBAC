package com.example.rbac.service.rbac;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the resolution semantics from ADR-001 §9 (testable properties):
 * permission union, per-dimension scope OR-merge, and UI visibility-by-permission.
 */
class RbacResolverTest {

    private final RbacResolver resolver = new RbacResolver();

    @Test
    void permissionsAreTheUnionAcrossProfiles() {
        ProfileGrants a = new ProfileGrants("support", Set.of("orders:read"), Map.of(), List.of());
        ProfileGrants b = new ProfileGrants("manager", Set.of("orders:read", "orders:refund"), Map.of(), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        assertTrue(r.permissions().containsAll(Set.of("orders:read", "orders:refund")));
        assertEquals(2, r.permissions().size());
    }

    @Test
    void scopesAreOrMergedPerDimension() {
        ProfileGrants a = new ProfileGrants("support", Set.of(),
                Map.of("orders", Map.of("orgIds", List.of(1, 2), "regions", List.of("EU"))), List.of());
        ProfileGrants b = new ProfileGrants("manager", Set.of(),
                Map.of("orders", Map.of("orgIds", List.of(3), "regions", List.of("US", "EU"))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope orders = r.scopes().get("orders");
        assertEquals(List.of(1, 2, 3), orders.values("orgIds"));
        assertEquals(List.of("EU", "US", "EU"), orders.values("regions")); // OR = plain union, order preserved
    }

    @Test
    void crossDimensionScopesAreCombinedAsAnd_soAllProfilesMustShareDimensions() {
        // A scopes only on orgIds, B only on regions. The merge yields
        // {orgIds:[1], regions:[EU]}, which a client applying all dimensions as AND
        // reads as "org 1 AND EU" — narrower than either profile alone. This is the
        // documented contract limit: every profile scoping a resource type must
        // declare the same dimension set. The test pins the current union-per-
        // dimension behavior so any move to a list-of-alternatives OR-model is a
        // deliberate, visible break.
        ProfileGrants a = new ProfileGrants("a", Set.of(),
                Map.of("orders", Map.of("orgIds", List.of(1))), List.of());
        ProfileGrants b = new ProfileGrants("b", Set.of(),
                Map.of("orders", Map.of("regions", List.of("EU"))), List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a, b), null, Set.of());

        Scope orders = r.scopes().get("orders");
        assertEquals(List.of(1), orders.values("orgIds"));
        assertEquals(List.of("EU"), orders.values("regions"));
    }

    @Test
    void scopesAreFilteredToRequestedResourceTypes() {
        ProfileGrants a = new ProfileGrants("support", Set.of(),
                Map.of("orders", Map.of("orgIds", List.of(1)),
                       "invoices", Map.of("orgIds", List.of(9))),
                List.of());

        RbacResolver.Resolved r = resolver.resolve(List.of(a), null, Set.of("orders"));

        assertTrue(r.scopes().containsKey("orders"));
        assertFalse(r.scopes().containsKey("invoices"));
    }

    @Test
    void uiElementIsVisibleOnlyIfItsGatingPermissionIsGranted() {
        UiElementDef visible = new UiElementDef("web", "tab.invoices", "invoices:read");
        UiElementDef hidden = new UiElementDef("web", "tab.refunds", "refunds:read");
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
