package com.example.rbac.service.api;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.service.audit.AuditService;
import com.example.rbac.service.domain.Permission;
import com.example.rbac.service.domain.PermissionRepository;
import com.example.rbac.service.domain.Profile;
import com.example.rbac.service.domain.ProfileRepository;
import com.example.rbac.service.domain.ProfileScope;
import com.example.rbac.service.domain.ProfileScopeRepository;
import com.example.rbac.service.domain.ResourceType;
import com.example.rbac.service.domain.ResourceTypeRepository;
import com.example.rbac.service.domain.Template;
import com.example.rbac.service.domain.TemplateElement;
import com.example.rbac.service.domain.TemplateRepository;
import com.example.rbac.service.invalidation.GrantChangeEvent;
import com.example.rbac.service.invalidation.OutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grant authoring (ADR A5, D6, D8, D10). Every mutation: persists, writes a
 * change-audit row, records an invalidation intent in the transactional outbox
 * (same transaction — C6), and publishes a {@link GrantChangeEvent} that — AFTER
 * the transaction commits — bumps the global version and pushes an invalidation
 * event so every client replica drops stale L1 near-instantly (with the relay
 * as the durable fallback if that publish fails).
 */
@Service
public class AdminService {

    private final ProfileRepository profiles;
    private final PermissionRepository permissions;
    private final ResourceTypeRepository resourceTypes;
    private final ProfileScopeRepository scopes;
    private final TemplateRepository templates;
    private final ApplicationEventPublisher events;
    private final OutboxService outbox;
    private final AuditService audit;
    private final ObjectMapper json;

    public AdminService(ProfileRepository profiles, PermissionRepository permissions,
                        ResourceTypeRepository resourceTypes, ProfileScopeRepository scopes,
                        TemplateRepository templates, ApplicationEventPublisher events,
                        OutboxService outbox, AuditService audit, ObjectMapper json) {
        this.profiles = profiles;
        this.permissions = permissions;
        this.resourceTypes = resourceTypes;
        this.scopes = scopes;
        this.templates = templates;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public Profile createProfile(String actor, String name, String description) {
        if (profiles.existsByName(name)) {
            throw new IllegalStateException("Profile already exists: " + name);
        }
        Profile p = profiles.save(new Profile(name, description));
        audit.change(actor, "profile", name, "create", "{}");
        publishInvalidation();
        return p;
    }

    @Transactional
    public void grantPermission(String actor, String profileName, String permissionKey) {
        Profile p = requireProfile(profileName);
        Permission perm = requirePermission(permissionKey);
        if (!p.getPermissions().contains(perm)) {
            p.getPermissions().add(perm);
            p.touch();
            profiles.save(p);
        }
        audit.change(actor, "profile", profileName, "grant_permission", "{\"key\":\"" + permissionKey + "\"}");
        publishInvalidation();
    }

    @Transactional
    public void revokePermission(String actor, String profileName, String permissionKey) {
        Profile p = requireProfile(profileName);
        Permission perm = requirePermission(permissionKey);
        boolean removed = p.getPermissions().remove(perm);
        if (removed) {
            p.touch();
            profiles.save(p);
        }
        audit.change(actor, "profile", profileName, "revoke_permission", "{\"key\":\"" + permissionKey + "\"}");
        publishInvalidation();
    }

    /**
     * S2 (ADR-002): set the profile's scope for a resource type as canonical Option A
     * filters ({@code {dimension, op, values}}; op validated against the resource
     * type's dimension schema; {@code IN []} = deny, {@code ALL} = all-rows).
     */
    @Transactional
    public void setScope(String actor, String profileName, String resourceTypeName, List<Scope.Filter> filters) {
        Profile p = requireProfile(profileName);
        ResourceType rt = requireResourceType(resourceTypeName);
        requireFiltersMatchSchema(rt, filters);
        String dimsJson = toJson(filters);
        // Upsert: mutate the existing (managed) row in place → one UPDATE. Never
        // delete+insert: Hibernate flushes INSERTs before DELETEs, so a fresh
        // insert would violate unique (profile_id, resource_type_id).
        ProfileScope toSave = scopes.findByProfileIdAndResourceType_Id(p.getId(), rt.getId())
                .orElseGet(() -> new ProfileScope(p.getId(), rt, dimsJson));
        toSave.setDimensions(dimsJson);
        scopes.save(toSave);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resourceType", resourceTypeName);
        detail.put("filters", filters);
        audit.change(actor, "profile", profileName, "set_scope", toJson(detail));
        publishInvalidation();
    }

    /**
     * S2 (ADR-002): remove the profile's scope for a resource type entirely — the
     * explicit revocation op (the old "set an empty dimension" trick is gone).
     * Idempotent: still audited + invalidated when the scope is already absent
     * (matching {@link #revokePermission}).
     */
    @Transactional
    public void deleteScope(String actor, String profileName, String resourceTypeName) {
        Profile p = requireProfile(profileName);
        ResourceType rt = requireResourceType(resourceTypeName);
        boolean removed = scopes.findByProfileIdAndResourceType_Id(p.getId(), rt.getId())
                .map(row -> {
                    scopes.delete(row);
                    return true;
                })
                .orElse(false);
        audit.change(actor, "profile", profileName, "delete_scope",
                "{\"resourceType\":\"" + resourceTypeName + "\",\"existed\":" + removed + "}");
        publishInvalidation();
    }

    @Transactional
    public Template createTemplate(String actor, String appId, String name,
                                   Set<String> elementPermissions) {
        if (templates.existsByAppIdAndName(appId, name)) {
            throw new IllegalStateException("Template already exists: " + appId + "/" + name);
        }
        Template t = new Template(appId, name);
        for (String permKey : elementPermissions) {
            Permission perm = requirePermission(permKey);
            t.getElements().add(new TemplateElement(t, elementKeyFromPermission(permKey), perm));
        }
        Template saved = templates.save(t);   // cascade = PERSIST writes the elements
        audit.change(actor, "template", appId + "/" + name, "create",
                "{\"elements\":" + toJson(elementPermissions) + "}");
        publishInvalidation();
        return saved;
    }

    @Transactional
    public void attachTemplate(String actor, String profileName, String appId, String templateName) {
        Profile p = requireProfile(profileName);
        Template t = templates.findByAppIdAndName(appId, templateName)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + appId + "/" + templateName));
        p.getTemplates().add(t);
        p.touch();
        profiles.save(p);
        audit.change(actor, "profile", profileName, "attach_template",
                "{\"app\":\"" + appId + "\",\"template\":\"" + templateName + "\"}");
        publishInvalidation();
    }

    /**
     * T1 (ADR-002): remove a template from a profile. The template is a LAYOUT
     * CATALOG — detaching removes its elements from the profile's UI bundles. It
     * never had (or removes) any permission grant: those are independent
     * ({@link #revokePermission}). Idempotent: detaching a non-attached template
     * is a no-op (still audited + invalidated, matching {@link #revokePermission}).
     */
    @Transactional
    public void detachTemplate(String actor, String profileName, String appId, String templateName) {
        Profile p = requireProfile(profileName);
        Template t = templates.findByAppIdAndName(appId, templateName)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + appId + "/" + templateName));
        boolean removed = p.getTemplates().remove(t);
        if (removed) {
            p.touch();
            profiles.save(p);
        }
        audit.change(actor, "profile", profileName, "detach_template",
                "{\"app\":\"" + appId + "\",\"template\":\"" + templateName + "\"}");
        publishInvalidation();
    }

    @Transactional
    public ResourceType registerResourceType(String actor, String name, String ownerService,
                                             Map<String, Object> dimensionSchema) {
        if (dimensionSchema == null || dimensionSchema.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dimensionSchema is required: it defines the canonical dimension set for scopes.");
        }
        if (resourceTypes.existsByName(name)) {
            throw new IllegalStateException("Resource type already exists: " + name);
        }
        ResourceType rt = resourceTypes.save(new ResourceType(name, ownerService, toJson(dimensionSchema)));
        audit.change(actor, "resource_type", name, "register",
                "{\"ownerService\":\"" + ownerService + "\"}");
        publishInvalidation();
        return rt;
    }

    // --- helpers ---

    /**
     * Record the invalidation intent (an outbox row) in the CURRENT transaction, then
     * publish the in-transaction event that — AFTER commit — bumps the version and
     * pushes the invalidation (ADR D10). Writing the outbox row in the same transaction
     * as the grant change is what makes "change committed &rArr; invalidation owed"
     * atomic (C6): it survives a Redis/RabbitMQ outage at commit time and a crash right
     * after commit. If the after-commit publish fails, the relay re-drives the row.
     */
    private void publishInvalidation() {
        events.publishEvent(new GrantChangeEvent(outbox.append()));
    }

    private Profile requireProfile(String name) {
        return profiles.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Profile not found: " + name));
    }

    private Permission requirePermission(String key) {
        return permissions.findByKey(key)
                .orElseGet(() -> permissions.save(new Permission(key, null)));
    }

    private ResourceType requireResourceType(String name) {
        return resourceTypes.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Resource type not found: " + name));
    }

    /**
     * Enforces the scope-merge contract at authoring time (ADR A3, ADR-002 S2,
     * DESIGN §5.2): the filters must cover exactly the resource type's dimension
     * set (otherwise the resolver's merge degrades and can *shrink* access), each
     * dimension at most once, and each op must be allowed by the dimension schema
     * (closed op set; unknown op = 400).
     */
    private void requireFiltersMatchSchema(ResourceType rt, List<Scope.Filter> filters) {
        Map<String, Object> schema = fromJson(rt.getDimensionSchema());
        if (schema == null || schema.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Resource type '" + rt.getName() + "' has no dimension schema; "
                            + "register one before setting scopes.");
        }
        if (filters == null || filters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "filters must not be empty for resource type '" + rt.getName() + "': every dimension "
                            + schema.keySet() + " must be declared (use op ALL for all-rows, IN [] for deny).");
        }
        Set<String> dims = new LinkedHashSet<>();
        for (Scope.Filter f : filters) {
            if (f == null || !dims.add(f.dimension())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "each dimension must appear at most once (got "
                                + filters.stream().map(Scope.Filter::dimension).toList() + ")");
            }
        }
        if (!schema.keySet().equals(dims)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dimensions for resource type '" + rt.getName() + "' must be exactly "
                            + schema.keySet() + " (got " + dims + ") — profiles scoping "
                            + "the same resource type must share the dimension set (scope-merge contract).");
        }
        for (Scope.Filter f : filters) {
            Set<String> allowed = allowedOps(schema.get(f.dimension()));
            if (!allowed.contains(f.op().name())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "op " + f.op() + " is not allowed for dimension '" + f.dimension()
                                + "' of resource type '" + rt.getName() + "' (allowed: " + allowed + ")");
            }
        }
    }

    /**
     * The ops a dimension allows, per its schema entry: {@code {"ops":["IN","GTE"]}}.
     * Legacy schemas without {@code ops} default to {@code [IN, ALL]} (the historical
     * value-list shape).
     */
    private Set<String> allowedOps(Object dimensionSchema) {
        if (dimensionSchema instanceof Map<?, ?> m && m.get("ops") instanceof List<?> ops && !ops.isEmpty()) {
            Set<String> out = new LinkedHashSet<>();
            ops.forEach(o -> out.add(String.valueOf(o)));
            return out;
        }
        return Set.of("IN", "ALL");
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return this.json.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt dimension_schema JSON: " + json, e);
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String elementKeyFromPermission(String permKey) {
        // "customers-web.tab.invoices" -> "tab.invoices"
        int idx = permKey.indexOf('.');
        return (idx >= 0) ? permKey.substring(idx + 1) : permKey;
    }
}
