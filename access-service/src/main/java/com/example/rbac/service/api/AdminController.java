package com.example.rbac.service.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    public AdminController(AdminService admin) {
        this.admin = admin;
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

    @PutMapping("/profiles/{name}/scopes/{resourceType}")
    public Map<String, String> setScope(@RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
                                        @PathVariable String name, @PathVariable String resourceType,
                                        @RequestBody Map<String, Object> dimensions) {
        admin.setScope(actor, name, resourceType, dimensions);
        return Map.of("status", "set");
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

    @PostMapping("/resource-types")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerResourceType(
            @RequestHeader(value = "X-Actor", defaultValue = "unknown") String actor,
            @RequestBody ResourceTypeBody body) {
        var rt = admin.registerResourceType(actor, body.name(), body.ownerService(), body.dimensionSchema());
        return Map.of("name", rt.getName(), "ownerService", String.valueOf(rt.getOwnerService()));
    }

    // --- request bodies ---
    public record ProfileBody(String name, String description) {}
    public record KeyBody(String key) {}
    public record TemplateBody(String appId, String name, Set<String> elements) {}
    public record TemplateRef(String appId, String name) {}
    public record ResourceTypeBody(String name, String ownerService, Map<String, Object> dimensionSchema) {}
}
