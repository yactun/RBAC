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
 * Semantics (design §5.2):
 * <ul>
 *   <li>permissions — union across all profiles</li>
 *   <li>scopes — per resourceType, union (OR) on each dimension list.
 *       <b>Contract:</b> this widens access only when every profile scoping a given
 *       resource type declares the <b>same dimension set</b>. If profiles scope on
 *       different dimensions (e.g. A={orgIds}, B={regions}), the merged scope is
 *       applied by the client as an AND across dimensions and can <i>shrink</i>
 *       access — so authoring requires a shared dimension set per resource type (or a
 *       future move to a list-of-alternative-scopes OR-model).</li>
 *   <li>ui — for a given app, an element is visible iff its gating permission is granted</li>
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
        Map<String, Map<String, Object>> scopes = new LinkedHashMap<>();

        for (ProfileGrants g : grants) {
            permissions.addAll(g.permissionKeys());
            g.scopes().forEach((resourceType, dims) -> {
                if (allResources || wantedResourceTypes.contains(resourceType)) {
                    merge(scopes, resourceType, dims);
                }
            });
        }

        Set<String> targetApps = (apps == null || apps.isEmpty()) ? allApps(grants) : apps;
        Map<String, UiElements> ui = new LinkedHashMap<>();
        for (String app : targetApps) {
            ui.put(app, computeUi(grants, app, permissions));
        }

        Map<String, Scope> scopeViews = new LinkedHashMap<>();
        scopes.forEach((rt, dims) -> scopeViews.put(rt, new Scope(rt, new LinkedHashMap<>(dims))));

        return new Resolved(
                List.copyOf(permissions),
                scopeViews,
                ui
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

    private void merge(Map<String, Map<String, Object>> target, String resourceType, Map<String, Object> dims) {
        Map<String, Object> t = target.computeIfAbsent(resourceType, k -> new LinkedHashMap<>());
        for (Map.Entry<String, Object> e : dims.entrySet()) {
            t.merge(e.getKey(), copyList(e.getValue()), (a, b) -> {
                List<Object> merged = (List<Object>) a;
                merged.addAll((List<Object>) b);
                return merged;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> copyList(Object value) {
        if (value instanceof List<?> l) {
            return new ArrayList<>((List<Object>) l);
        }
        return new ArrayList<>(List.of(value));
    }

    /** The resolved effective grants (permissions, scopes, ui). */
    public record Resolved(List<String> permissions,
                           Map<String, Scope> scopes,
                           Map<String, UiElements> ui) {
    }
}
