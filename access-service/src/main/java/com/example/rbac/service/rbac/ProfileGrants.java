package com.example.rbac.service.rbac;

import com.example.rbac.model.dto.Scope;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The expanded grant data for a single profile, loaded from the DB:
 * <ul>
 *   <li>{@code permissionKeys} — direct permissions (T1, ADR-002: template-element
 *       permissions are NOT included — a template is a layout catalog)</li>
 *   <li>{@code scopes} — resourceType → typed predicate filters (canonical Option A,
 *       ADR-002 S2: {@code {dimension, op, values}}; values are service-defined)</li>
 *   <li>{@code uiElements} — app, elementKey, gating permission (from attached templates)</li>
 * </ul>
 * Kept as a plain record so {@link RbacResolver} stays pure and dependency-free.
 */
public record ProfileGrants(String profileName,
                            Set<String> permissionKeys,
                            Map<String, List<Scope.Filter>> scopes,
                            List<UiElementDef> uiElements) {
}
