package com.example.rbac.service.api;

import com.example.rbac.model.dto.Scope;
import com.example.rbac.service.audit.AuditService;
import com.example.rbac.service.domain.PermissionRepository;
import com.example.rbac.service.domain.Profile;
import com.example.rbac.service.domain.ProfileRepository;
import com.example.rbac.service.domain.ProfileScope;
import com.example.rbac.service.domain.ProfileScopeRepository;
import com.example.rbac.service.domain.ResourceType;
import com.example.rbac.service.domain.ResourceTypeRepository;
import com.example.rbac.service.domain.Template;
import com.example.rbac.service.domain.TemplateRepository;
import com.example.rbac.service.invalidation.OutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the setScope upsert fix and the scope-merge validation:
 * <ul>
 *   <li>An existing scope must be UPDATED in place — never delete+insert (Hibernate
 *       flushes INSERTs before DELETEs, so the old pattern violated the unique
 *       {@code (profile_id, resource_type_id)} constraint);</li>
 *   <li>Dimensions must exactly match the resource type's dimension_schema
 *       (scope-merge contract, 400);</li>
 *   <li>{@code registerResourceType} requires a non-empty schema.</li>
 * </ul>
 * Uses only <b>interface</b> mocks + real entities (no Mockito class-mocking), so it
 * runs on any JVM. Proving the constraint itself still needs a real Postgres
 * (Testcontainers, parked).
 */
class AdminServiceTest {

    private ProfileRepository profiles;
    private ResourceTypeRepository resourceTypes;
    private ProfileScopeRepository scopes;
    private TemplateRepository templates;
    private AdminService service;
    private final ObjectMapper json = new ObjectMapper();

    private final Profile p = new Profile("support", null);
    private ResourceType orders;

    @BeforeEach
    void setUp() {
        profiles = mock(ProfileRepository.class);
        resourceTypes = mock(ResourceTypeRepository.class);
        scopes = mock(ProfileScopeRepository.class);
        templates = mock(TemplateRepository.class);
        service = new AdminService(profiles,
                mock(PermissionRepository.class),
                resourceTypes, scopes,
                templates,
                mock(ApplicationEventPublisher.class),
                new OutboxService(null) {
                    @Override
                    public long append() {
                        // no real DB in this unit test; the id is irrelevant here
                        return 42L;
                    }
                },
                new AuditService(null) {
                    @Override
                    public void change(String actor, String entity, String entityRef,
                                      String action, String detailJson) {
                        // no-op fake: audit persistence is out of scope here
                    }
                },
                json);
    }

    private void givenOrdersType(String dimensionSchemaJson) {
        orders = new ResourceType("orders", "orders-svc", dimensionSchemaJson);
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        when(resourceTypes.findByName("orders")).thenReturn(Optional.of(orders));
    }

    private static Scope.Filter in(String dimension, Object... values) {
        return new Scope.Filter(dimension, Scope.Op.IN, List.of(values));
    }

    @Test
    void setScope_updatesExistingScopeInPlace_neverDeletes() throws Exception {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"},\"regions\":{\"type\":\"list\"}}");
        ProfileScope existing = new ProfileScope(null, orders, "[]");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.of(existing));

        service.setScope("alice", "support", "orders",
                List.of(in("orgIds", 1, 2), in("regions", "EU")));

        verify(scopes, never()).delete(any(ProfileScope.class));   // ← the regression, pinned
        List<Scope.Filter> stored = json.readValue(existing.getDimensions(),
                new TypeReference<List<Scope.Filter>>() {});
        assertEquals(List.of(1, 2), stored.get(0).values());
        assertEquals(List.of("EU"), stored.get(1).values());
        verify(scopes).save(existing);
    }

    @Test
    void setScope_insertsWhenNoScopeExists() throws Exception {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"},\"regions\":{\"type\":\"list\"}}");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.empty());

        service.setScope("alice", "support", "orders",
                List.of(in("orgIds", 1, 2), in("regions", "EU")));

        verify(scopes, never()).delete(any(ProfileScope.class));
        ArgumentCaptor<ProfileScope> saved = ArgumentCaptor.forClass(ProfileScope.class);
        verify(scopes).save(saved.capture());
        List<Scope.Filter> stored = json.readValue(saved.getValue().getDimensions(),
                new TypeReference<List<Scope.Filter>>() {});
        assertEquals(List.of(1, 2), stored.get(0).values());
        assertEquals(List.of("EU"), stored.get(1).values());
    }

    @Test
    void setScope_rejectsDimensionSetThatDiffersFromSchema() {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"},\"regions\":{\"type\":\"list\"}}");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders", List.of(in("orgIds", 1))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    @Test
    void setScope_rejectsWhenResourceTypeHasNoSchema() {
        givenOrdersType(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders", List.of(in("orgIds", 1))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    // --- S2 (ADR-002): Option A op validation + explicit scope deletion ---

    @Test
    void setScope_rejectsAnOpTheSchemaDoesNotAllow() {
        givenOrdersType("{\"orgIds\":{\"ops\":[\"IN\",\"ALL\"]}}");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders",
                        List.of(new Scope.Filter("orgIds", Scope.Op.GT, List.of(1)))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    @Test
    void setScope_rejectsADimensionTwice() {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"}}");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders",
                        List.of(in("orgIds", 1), in("orgIds", 2))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    @Test
    void setScope_allowsTheExplicitAllOp() throws Exception {
        givenOrdersType("{\"orgIds\":{\"ops\":[\"IN\",\"ALL\"]}}");
        ProfileScope existing = new ProfileScope(null, orders, "[]");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.of(existing));

        service.setScope("alice", "support", "orders",
                List.of(new Scope.Filter("orgIds", Scope.Op.ALL, List.of())));

        List<Scope.Filter> stored = json.readValue(existing.getDimensions(),
                new TypeReference<List<Scope.Filter>>() {});
        assertEquals(Scope.Op.ALL, stored.get(0).op());
        assertTrue(stored.get(0).values().isEmpty());
    }

    @Test
    void deleteScope_removesTheScopeRow() {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"}}");
        ProfileScope existing = new ProfileScope(null, orders, "[]");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.of(existing));

        service.deleteScope("alice", "support", "orders");

        verify(scopes).delete(existing);
    }

    @Test
    void deleteScope_isIdempotent_whenNoScopeExists() {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"}}");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.empty());

        service.deleteScope("alice", "support", "orders");

        verify(scopes, never()).delete(any(ProfileScope.class));
    }

    @Test
    void registerResourceType_requiresNonEmptySchema() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.registerResourceType("alice", "orders", "orders-svc", Map.of()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(resourceTypes, never()).save(any(ResourceType.class));
    }

    // --- T1 (ADR-002): detachTemplate — the catalog counterpart of attach ---

    @Test
    void detachTemplate_removesTheTemplateFromTheProfile() {
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        Template standard = new Template("customers-web", "standard");
        when(templates.findByAppIdAndName("customers-web", "standard"))
                .thenReturn(Optional.of(standard));
        p.getTemplates().add(standard);

        service.detachTemplate("alice", "support", "customers-web", "standard");

        assertEquals(0, p.getTemplates().size());
        verify(profiles).save(p);
    }

    @Test
    void detachTemplate_isIdempotent_whenNotAttached() {
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        Template standard = new Template("customers-web", "standard");
        when(templates.findByAppIdAndName("customers-web", "standard"))
                .thenReturn(Optional.of(standard));
        p.getTemplates().add(new Template("customers-web", "other"));

        service.detachTemplate("alice", "support", "customers-web", "standard");

        // still attached (we detached a different one) — and no save (no-op)
        assertEquals(1, p.getTemplates().size());
        verify(profiles, never()).save(any(Profile.class));
    }

    @Test
    void detachTemplate_requiresTheTemplateToExist() {
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        when(templates.findByAppIdAndName("customers-web", "ghost"))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.detachTemplate("alice", "support", "customers-web", "ghost"));

        assertEquals("Template not found: customers-web/ghost", ex.getMessage());
        verify(profiles, never()).save(any(Profile.class));
    }
}
