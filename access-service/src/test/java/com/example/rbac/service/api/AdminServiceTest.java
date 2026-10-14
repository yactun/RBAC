package com.example.rbac.service.api;

import com.example.rbac.service.audit.AuditService;
import com.example.rbac.service.domain.PermissionRepository;
import com.example.rbac.service.domain.Profile;
import com.example.rbac.service.domain.ProfileRepository;
import com.example.rbac.service.domain.ProfileScope;
import com.example.rbac.service.domain.ProfileScopeRepository;
import com.example.rbac.service.domain.ResourceType;
import com.example.rbac.service.domain.ResourceTypeRepository;
import com.example.rbac.service.domain.TemplateRepository;
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
    private AdminService service;
    private final ObjectMapper json = new ObjectMapper();

    private final Profile p = new Profile("support", null);
    private ResourceType orders;

    @BeforeEach
    void setUp() {
        profiles = mock(ProfileRepository.class);
        resourceTypes = mock(ResourceTypeRepository.class);
        scopes = mock(ProfileScopeRepository.class);
        service = new AdminService(profiles,
                mock(PermissionRepository.class),
                resourceTypes, scopes,
                mock(TemplateRepository.class),
                mock(ApplicationEventPublisher.class),
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

    @Test
    void setScope_updatesExistingScopeInPlace_neverDeletes() throws Exception {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"},\"regions\":{\"type\":\"list\"}}");
        ProfileScope existing = new ProfileScope(null, orders, "{\"orgIds\":[9]}");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.of(existing));

        service.setScope("alice", "support", "orders",
                Map.of("orgIds", List.of(1, 2), "regions", List.of("EU")));

        verify(scopes, never()).delete(any(ProfileScope.class));   // ← the regression, pinned
        Map<String, Object> stored = json.readValue(existing.getDimensions(),
                new TypeReference<Map<String, Object>>() {});
        assertEquals(List.of(1, 2), stored.get("orgIds"));
        assertEquals(List.of("EU"), stored.get("regions"));
        verify(scopes).save(existing);
    }

    @Test
    void setScope_insertsWhenNoScopeExists() throws Exception {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"},\"regions\":{\"type\":\"list\"}}");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.empty());

        service.setScope("alice", "support", "orders",
                Map.of("orgIds", List.of(1, 2), "regions", List.of("EU")));

        verify(scopes, never()).delete(any(ProfileScope.class));
        ArgumentCaptor<ProfileScope> saved = ArgumentCaptor.forClass(ProfileScope.class);
        verify(scopes).save(saved.capture());
        Map<String, Object> stored = json.readValue(saved.getValue().getDimensions(),
                new TypeReference<Map<String, Object>>() {});
        assertEquals(List.of(1, 2), stored.get("orgIds"));
        assertEquals(List.of("EU"), stored.get("regions"));
    }

    @Test
    void setScope_rejectsDimensionSetThatDiffersFromSchema() {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"},\"regions\":{\"type\":\"list\"}}");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders", Map.of("orgIds", List.of(1))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    @Test
    void setScope_rejectsWhenResourceTypeHasNoSchema() {
        givenOrdersType(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders", Map.of("orgIds", List.of(1))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    @Test
    void registerResourceType_requiresNonEmptySchema() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.registerResourceType("alice", "orders", "orders-svc", Map.of()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(resourceTypes, never()).save(any(ResourceType.class));
    }
}
