package com.example.rbac.service.rbac;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.service.domain.Permission;
import com.example.rbac.service.domain.Profile;
import com.example.rbac.service.domain.ProfileRepository;
import com.example.rbac.service.domain.ProfileScope;
import com.example.rbac.service.domain.ProfileScopeRepository;
import com.example.rbac.service.domain.Template;
import com.example.rbac.service.domain.TemplateElement;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the expanded grants for a set of profiles from the database.
 * Pure data shaping — no caching, no policy logic (that is {@link RbacResolver}).
 * Loads scopes for all requested profiles in ONE query (no N+1).
 */
@Component
public class GrantLoader {

    /**
     * S2 (ADR-002): the stored dimensions JSON is now the canonical Option A filter
     * list. Deserializing through the record constructors also validates the shape
     * (op arity, non-blank dimensions) — a corrupt row fails closed at compute time
     * (500, never cached) instead of serving a half-parsed scope.
     */
    private static final TypeReference<List<Scope.Filter>> FILTERS = new TypeReference<>() {
    };

    private final ProfileRepository profiles;
    private final ProfileScopeRepository scopes;
    private final ObjectMapper json;

    public GrantLoader(ProfileRepository profiles, ProfileScopeRepository scopes, ObjectMapper json) {
        this.profiles = profiles;
        this.scopes = scopes;
        this.json = json;
    }

    public List<ProfileGrants> load(Set<String> profileNames) {
        List<Profile> loaded = profiles.findByNameIn(profileNames);
        if (loaded.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = loaded.stream().map(Profile::getId).collect(Collectors.toSet());
        Map<UUID, List<ProfileScope>> scopesByProfile = scopes.findByProfileIdIn(ids).stream()
                .collect(Collectors.groupingBy(ProfileScope::getProfileId));

        List<ProfileGrants> out = new ArrayList<>(loaded.size());
        for (Profile p : loaded) {
            out.add(toGrants(p, scopesByProfile.getOrDefault(p.getId(), List.of())));
        }
        return out;
    }

    private ProfileGrants toGrants(Profile p, List<ProfileScope> profileScopes) {
        Set<String> permKeys = new LinkedHashSet<>();
        List<UiElementDef> ui = new ArrayList<>();

        for (Permission perm : p.getPermissions()) {
            permKeys.add(perm.getKey());
        }
        for (Template t : p.getTemplates()) {
            for (TemplateElement e : t.getElements()) {
                Permission perm = e.getPermission();
                // T1 (ADR-002): a template is a LAYOUT CATALOG — attaching it does NOT grant
                // the element's permission. It only contributes the UiElementDef (app,
                // elementKey, gating-permission). Visibility stays permission-driven in
                // RbacResolver, so `visible:false` is reachable and revoking the permission
                // is effective.
                ui.add(new UiElementDef(t.getAppId(), e.getElementKey(), perm.getKey()));
            }
        }

        Map<String, List<Scope.Filter>> scopeMap = new LinkedHashMap<>();
        for (ProfileScope s : profileScopes) {
            scopeMap.put(s.getResourceType().getName(), parse(s.getDimensions()));
        }

        return new ProfileGrants(p.getName(), permKeys, scopeMap, ui);
    }

    private List<Scope.Filter> parse(String dimensionsJson) {
        if (dimensionsJson == null || dimensionsJson.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(dimensionsJson, FILTERS);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid dimensions JSON on profile scope: " + dimensionsJson, e);
        }
    }
}
