package com.example.rbac.service.rbac;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The expanded grant data for a single profile, loaded from the DB:
 * <ul>
 *   <li>{@code permissionKeys} — direct permissions plus all template-element permissions</li>
 *   <li>{@code scopes} — resourceType → dimension → granted values (values are service-defined,
 *       in practice a list)</li>
 *   <li>{@code uiElements} — app, elementKey, gating permission (from attached templates)</li>
 * </ul>
 * Kept as a plain record so {@link RbacResolver} stays pure and dependency-free.
 */
public record ProfileGrants(String profileName,
                            Set<String> permissionKeys,
                            Map<String, Map<String, Object>> scopes,
                            List<UiElementDef> uiElements) {
}
