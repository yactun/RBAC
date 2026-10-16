package com.example.rbac.service.api;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.service.invalidation.InvalidationRelay;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Audited grant authoring (design §4.3). Every endpoint records the actor and
 * triggers version bump + invalidation (ADR D6, D8, D10).
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final AdminService admin;
    private final InvalidationRelay relay;

    public AdminController(AdminService admin, InvalidationRelay relay) {
        this.admin = admin;
        this.relay = relay;
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProfile(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                             @RequestBody ProfileBody body) {
        var profile = admin.createProfile(actor, body.name(), body.description());
        return Map.of("name", profile.getName());
    }

    @PostMapping("/profiles/{name}/permissions")
    public Map<String, String> grantPermission(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                               @PathVariable String name, @RequestBody KeyBody body) {
        admin.grantPermission(actor, name, body.key());
        return Map.of("status", "granted");
    }

    @DeleteMapping("/profiles/{name}/permissions/{key}")
    public Map<String, String> revokePermission(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                                @PathVariable String name, @PathVariable String key) {
        admin.revokePermission(actor, name, key);
        return Map.of("status", "revoked");
    }

    /**
     * S2 (ADR-002): the body is the canonical Option A filter list
     * ({@code [{dimension, op, values}, …]}); ops are validated against the
     * resource type's dimension schema (400 on an unknown op/dimension).
     */
    @PutMapping("/profiles/{name}/scopes/{resourceType}")
    public Map<String, String> setScope(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                        @PathVariable String name, @PathVariable String resourceType,
                                        @RequestBody List<Scope.Filter> filters) {
        admin.setScope(actor, name, resourceType, filters);
        return Map.of("status", "set");
    }

    /** S2 (ADR-002): explicit scope revocation (remove the whole scope for the resource type). */
    @DeleteMapping("/profiles/{name}/scopes/{resourceType}")
    public Map<String, String> deleteScope(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                           @PathVariable String name, @PathVariable String resourceType) {
        admin.deleteScope(actor, name, resourceType);
        return Map.of("status", "deleted");
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTemplate(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                              @RequestBody TemplateBody body) {
        var t = admin.createTemplate(actor, body.appId(), body.name(), body.elements());
        return Map.of("appId", t.getAppId(), "name", t.getName());
    }

    @PostMapping("/profiles/{name}/templates")
    public Map<String, String> attachTemplate(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                              @PathVariable String name, @RequestBody TemplateRef body) {
        admin.attachTemplate(actor, name, body.appId(), body.name());
        return Map.of("status", "attached");
    }

    @DeleteMapping("/profiles/{name}/templates/{appId}/{templateName}")
    public Map<String, String> detachTemplate(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                              @PathVariable String name,
                                              @PathVariable String appId,
                                              @PathVariable String templateName) {
        admin.detachTemplate(actor, name, appId, templateName);
        return Map.of("status", "detached");
    }

    @PostMapping("/resource-types")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerResourceType(
            @RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
            @RequestBody ResourceTypeBody body) {
        var rt = admin.registerResourceType(actor, body.name(), body.ownerService(), body.dimensionSchema());
        return Map.of("name", rt.getName(), "ownerService", String.valueOf(rt.getOwnerService()));
    }

    /**
     * Ops escape hatch (REVIEW #9): force an immediate global version bump +
     * invalidation publish, bypassing the outbox. Use to unstick clients if the relay
     * appears stuck (e.g. the {@code rbac.pdp.outbox.pending} gauge is climbing).
     */
    @PostMapping("/invalidate")
    public Map<String, Object> invalidate() {
        long version = relay.forceInvalidate();
        return Map.of("version", version);
    }

    // --- request bodies ---
    public record ProfileBody(String name, String description) {}
    public record KeyBody(String key) {}
    public record TemplateBody(String appId, String name, Set<String> elements) {}
    public record TemplateRef(String appId, String name) {}
    public record ResourceTypeBody(String name, String ownerService, Map<String, Object> dimensionSchema) {}
}
