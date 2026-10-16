package com.example.rbac.model.dto;

import com.example.rbac.model.UnknownDimensionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 (ADR-002): pins the canonical Option A scope contract and its fail-closed
 * invariants — a missing dimension is an error (never "all rows"), op arity is
 * enforced at construction (so a corrupt PDP response is rejected at
 * deserialization, before any query), and the deny/wildcard markers are explicit.
 */
class ScopeTest {

    private final Scope scope = new Scope("orders", List.of(
            new Scope.Filter("orgIds", Scope.Op.IN, List.of(1, 2)),
            new Scope.Filter("createdAt", Scope.Op.GTE, List.of("2026-01-01")),
            new Scope.Filter("regions", Scope.Op.ALL, List.of())
    ));

    @Test
    void inValuesAreReturned() {
        assertEquals(List.of(1, 2), scope.values("orgIds"));
        assertTrue(scope.has("orgIds"));
    }

    @Test
    void inEmptyListIsDeny_notAWildcard() {
        Scope deny = new Scope("orders", List.of(new Scope.Filter("orgIds", Scope.Op.IN, List.of())));
        assertTrue(deny.values("orgIds").isEmpty()); // the developer must return NO rows
        assertFalse(deny.isAll("orgIds"));
    }

    @Test
    void allIsTheExplicitWildcard() {
        assertTrue(scope.isAll("regions"));
        assertEquals(Scope.Op.ALL, scope.filter("regions").orElseThrow().op());
    }

    @Test
    void unknownDimensionThrows_neverReturnsAnEmptyList() {
        // The #3 fail-open: a typo used to return [] and the documented
        // "if (!values.isEmpty()) ..." pattern read that as "no filter = all rows".
        UnknownDimensionException ex =
                assertThrows(UnknownDimensionException.class, () -> scope.values("orgId"));
        assertTrue(ex.getMessage().contains("orgId"));

        assertThrows(UnknownDimensionException.class, () -> scope.isAll("regionsX"));
    }

    @Test
    void valuesOnANonInDimensionForcesExplicitHandling() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> scope.values("createdAt"));
        assertTrue(ex.getMessage().contains("GTE"));
        // the developer applies it via the typed filter:
        assertEquals("2026-01-01", scope.filter("createdAt").orElseThrow().single());
    }

    @Test
    void opArityIsEnforcedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Scope.Filter("d", Scope.Op.EQ, List.of(1, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> new Scope.Filter("d", Scope.Op.GTE, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Scope.Filter("d", Scope.Op.BETWEEN, List.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Scope.Filter("d", Scope.Op.ALL, List.of(1)));
    }

    @Test
    void duplicateDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Scope("orders", List.of(
                new Scope.Filter("orgIds", Scope.Op.IN, List.of(1)),
                new Scope.Filter("orgIds", Scope.Op.IN, List.of(2)))));
    }

    @Test
    void betweenBoundedAccessors() {
        Scope.Filter f = new Scope.Filter("total", Scope.Op.BETWEEN, List.of(10, 100));
        assertEquals(10, f.low());
        assertEquals(100, f.high());
    }

    @Test
    void nullFiltersAreTreatedAsEmpty() {
        Scope s = new Scope("orders", null);
        assertFalse(s.has("orgIds"));
        assertThrows(UnknownDimensionException.class, () -> s.values("orgIds"));
    }
}
