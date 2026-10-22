package com.example.rbac.service.rbac;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure RBAC resolution: given the expanded grants of the user's profiles,
 * compute the effective grant set. No Spring, no I/O — trivially unit-testable.
 *
 * <p>Semantics (design §5.2, ADR-002 T1/S2):
 * <ul>
 *   <li><b>permissions</b> — union across all profiles. Template elements are a
 *       LAYOUT CATALOG (T1): they never contribute permissions here.</li>
 *   <li><b>grantedBy</b> — each permission key &rarr; the profiles that grant it
 *       (provenance for {@code /v1/check}; carried in the cached {@code GrantSet}
 *       so the decision path never re-queries the DB per check — REVIEW D2).</li>
 *   <li><b>scopes</b> — per resourceType, the filters are OR-merged per dimension
 *       (union of the allowed sets): {@code IN} values union; {@code EQ} OR
 *       {@code EQ} = {@code IN} (or {@code EQ} if equal); {@code GT}/{@code GTE} =
 *       the widest (lowest) bound; {@code LT}/{@code LTE} = the widest (highest)
 *       bound; {@code BETWEEN} = the covering interval; {@code ALL} wins over
 *       anything (all-rows OR a subset = all-rows). Mixed ops on one dimension are
 *       ambiguous and fail closed (throw — never a guess). The client applies the
 *       resulting dimensions as an AND across dimensions.</li>
 *   <li><b>ui</b> — for a given app, an element is visible iff its gating permission is granted</li>
 * </ul>
 */
public final class RbacResolver {

    /**
     * @param grants the expanded grants of the user's profiles
     * @param apps   the apps to include UI bundles for; {@code null} or empty means
     *               <b>every</b> app referenced by the profiles' templates — the full
     *               {@code GrantSet} stored in L1/L2 (ADR D9: one cached unit with
     *               perms + scopes + ui, the client derives check/scope/ui locally)
     * @param wantedResourceTypes resource types to include scopes for; empty = all
     */
    public Resolved resolve(List<ProfileGrants> grants, Set<String> apps, Set<String> wantedResourceTypes) {
        boolean allResources = (wantedResourceTypes == null) || wantedResourceTypes.isEmpty();

        Set<String> permissions = new LinkedHashSet<>();
        Map<String, List<Scope.Filter>> scopes = new LinkedHashMap<>();
        Map<String, List<String>> grantedBy = new LinkedHashMap<>();

        for (ProfileGrants g : grants) {
            for (String key : g.permissionKeys()) {
                permissions.add(key);
                grantedBy.computeIfAbsent(key, k -> new ArrayList<>()).add(g.profileName());
            }
            g.scopes().forEach((resourceType, filters) -> {
                if (allResources || wantedResourceTypes.contains(resourceType)) {
                    merge(scopes, resourceType, filters);
                }
            });
        }

        Set<String> targetApps = (apps == null || apps.isEmpty()) ? allApps(grants) : apps;
        Map<String, UiElements> ui = new LinkedHashMap<>();
        for (String app : targetApps) {
            ui.put(app, computeUi(grants, app, permissions));
        }

        Map<String, Scope> scopeViews = new LinkedHashMap<>();
        scopes.forEach((rt, filters) -> scopeViews.put(rt, new Scope(rt, filters)));

        return new Resolved(
                List.copyOf(permissions),
                scopeViews,
                ui,
                Map.copyOf(grantedBy)
        );
    }

    private Set<String> allApps(List<ProfileGrants> grants) {
        Set<String> apps = new LinkedHashSet<>();
        for (ProfileGrants g : grants) {
            for (UiElementDef def : g.uiElements()) {
                apps.add(def.app());
            }
        }
        return apps;
    }

    private UiElements computeUi(List<ProfileGrants> grants, String app, Set<String> permissions) {
        // elementKey -> visible (OR across profiles); visible iff gating permission is granted
        Map<String, Boolean> byKey = new LinkedHashMap<>();
        for (ProfileGrants g : grants) {
            for (UiElementDef def : g.uiElements()) {
                if (app.equals(def.app())) {
                    boolean visible = permissions.contains(def.permissionKey());
                    byKey.merge(def.elementKey(), visible, (a, b) -> a || b);
                }
            }
        }
        List<UiElements.Element> elements = byKey.entrySet().stream()
                .map(e -> new UiElements.Element(e.getKey(), e.getValue()))
                .toList();
        return new UiElements(app, elements);
    }

    /** OR-merge one profile's filters into the accumulated scope for the resource type. */
    private void merge(Map<String, List<Scope.Filter>> target, String resourceType, List<Scope.Filter> filters) {
        Map<String, Scope.Filter> byDimension = new LinkedHashMap<>();
        List<Scope.Filter> existing = target.get(resourceType);
        if (existing != null) {
            existing.forEach(f -> byDimension.put(f.dimension(), f));
        }
        for (Scope.Filter f : filters) {
            byDimension.merge(f.dimension(), f, RbacResolver::mergeOne);
        }
        target.put(resourceType, List.copyOf(byDimension.values()));
    }

    /**
     * OR-merge two filters on the same dimension (the union of the allowed sets —
     * the scope-merge contract, ADR A3):
     * <ul>
     *   <li>{@code IN} OR {@code IN} = {@code IN} (union of values; empty = deny stays)</li>
     *   <li>{@code EQ} OR {@code EQ} = {@code EQ} if equal, else {@code IN} [a, b]</li>
     *   <li>{@code GT}/{@code GTE} OR same op = the lowest bound (widest set)</li>
     *   <li>{@code LT}/{@code LTE} OR same op = the highest bound (widest set)</li>
     *   <li>{@code BETWEEN} OR {@code BETWEEN} = the covering interval</li>
     *   <li>{@code ALL} OR anything = {@code ALL}</li>
     *   <li>any other mix = <b>fail closed</b> (throw): the union is not expressible as
     *       one filter in v1, so refuse rather than guess (a 500 the ops can see beats
     *       a silent widen or shrink of access)</li>
     * </ul>
     */
    private static Scope.Filter mergeOne(Scope.Filter a, Scope.Filter b) {
        String dim = a.dimension();
        if (a.op() != b.op()) {
            if (a.op() == Scope.Op.ALL || b.op() == Scope.Op.ALL) {
                return all(dim);
            }
            throw new IllegalStateException("cannot OR-merge scope filters on dimension '" + dim
                    + "': " + a.op() + " vs " + b.op() + " (mixed ops are ambiguous — fail-closed)");
        }
        return switch (a.op()) {
            case IN -> new Scope.Filter(dim, Scope.Op.IN, union(a.values(), b.values()));
            case EQ -> a.single().equals(b.single())
                    ? a
                    : new Scope.Filter(dim, Scope.Op.IN, union(a.values(), b.values())); // EQ OR EQ = IN
            case GT, GTE -> new Scope.Filter(dim, a.op(), List.of(min(a.single(), b.single())));
            case LT, LTE -> new Scope.Filter(dim, a.op(), List.of(max(a.single(), b.single())));
            case BETWEEN -> new Scope.Filter(dim, Scope.Op.BETWEEN,
                    List.of(min(a.low(), b.low()), max(a.high(), b.high())));
            case ALL -> a;
        };
    }

    private static Scope.Filter all(String dim) {
        return new Scope.Filter(dim, Scope.Op.ALL, List.of());
    }

    private static List<Object> union(List<Object> a, List<Object> b) {
        Set<Object> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return new ArrayList<>(out);
    }

    private static Object min(Object a, Object b) {
        int c = compare(a, b);
        return c <= 0 ? a : b;
    }

    private static Object max(Object a, Object b) {
        int c = compare(a, b);
        return c >= 0 ? a : b;
    }

    /** Fail-closed comparison: scope values must be orderable (Number/String from JSON). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Comparable && b instanceof Comparable) {
            try {
                return ((Comparable) a).compareTo(b);
            } catch (ClassCastException e) {
                throw new IllegalStateException(
                        "incomparable scope values for OR-merge (fail-closed): " + a + " vs " + b, e);
            }
        }
        throw new IllegalStateException(
                "scope values must be comparable (Number/String) to OR-merge (fail-closed): "
                        + a + " vs " + b);
    }

    /** The resolved effective grants (permissions, scopes, ui, grantedBy). */
    public record Resolved(List<String> permissions,
                           Map<String, Scope> scopes,
                           Map<String, UiElements> ui,
                           Map<String, List<String>> grantedBy) {
    }
}
