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
import com.example.rbac.service.domain.TemplateRepository;
import com.example.rbac.service.invalidation.OutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
    private PermissionRepository permissions;
    private ResourceTypeRepository resourceTypes;
    private ProfileScopeRepository scopes;
    private TemplateRepository templates;
    private JdbcTemplate jdbc;
    private AuditService audit;
    private AdminService service;
    private final ObjectMapper json = new ObjectMapper();

    private final Profile p = new Profile("support", null);
    private ResourceType orders;

    @BeforeEach
    void setUp() {
        profiles = mock(ProfileRepository.class);
        permissions = mock(PermissionRepository.class);
        resourceTypes = mock(ResourceTypeRepository.class);
        scopes = mock(ProfileScopeRepository.class);
        templates = mock(TemplateRepository.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);   // mocked so the change-audit JSON can be captured
        service = new AdminService(profiles,
                permissions,
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
                audit,
                json,
                jdbc);
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

    // --- R1 (ADR-002): live-grant provenance (granted_at / granted_by) ---

    @Test
    void grantPermission_stampsTheLiveRowWithTheActor() {
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        Permission perm = new Permission("orders:create", null);
        when(permissions.findByKey("orders:create")).thenReturn(Optional.of(perm));

        service.grantPermission("alice", "support", "orders:create");

        verify(jdbc).update(contains("update profile_permission"), eq("alice"), any(), any());
    }

    @Test
    void setScope_stampsTheLiveRowWithTheActor() {
        givenOrdersType("{\"orgIds\":{\"type\":\"list\"}}");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.empty());

        service.setScope("alice", "support", "orders", List.of(in("orgIds", 1)));

        verify(jdbc).update(contains("update profile_scope"), eq("alice"), any(), any());
    }

    @Test
    void attachTemplate_stampsTheLiveRowWithTheActor() {
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        Template t = new Template("web", "standard");
        when(templates.findByAppIdAndName("web", "standard")).thenReturn(Optional.of(t));

        service.attachTemplate("alice", "support", "web", "standard");

        verify(jdbc).update(contains("update profile_template"), eq("alice"), any(), any());
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

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.detachTemplate("alice", "support", "customers-web", "ghost"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(profiles, never()).save(any(Profile.class));
    }

    // --- D2 (REVIEW): explicit permission catalog, 404/409 semantics, value-type
    // validation, and JSON-escaped change-audit ---

    @Test
    void createPermission_addsACatalogEntry_andAuditsIt() throws Exception {
        when(permissions.findByKey("orders:refund")).thenReturn(Optional.empty());
        when(permissions.save(any(Permission.class))).thenAnswer(inv -> inv.getArgument(0));

        Permission saved = service.createPermission("alice", "orders:refund", "Refund an order");

        assertEquals("orders:refund", saved.getKey());
        verify(permissions).save(any(Permission.class));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).change(eq("alice"), eq("permission"), eq("orders:refund"), eq("create"), detail.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(detail.getValue(), Map.class);
        assertEquals("orders:refund", parsed.get("key"));
        assertEquals("Refund an order", parsed.get("description"));
    }

    @Test
    void createPermission_conflictsWhenTheKeyAlreadyExists() {
        when(permissions.findByKey("orders:refund"))
                .thenReturn(Optional.of(new Permission("orders:refund", null)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createPermission("alice", "orders:refund", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(permissions, never()).save(any(Permission.class));
    }

    @Test
    void grantPermission_rejectsAKeyNotInTheCatalog_noAutoCreate() {
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        when(permissions.findByKey("orders:refnud")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.grantPermission("alice", "support", "orders:refnud"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(permissions, never()).save(any(Permission.class));   // the old silent auto-create, pinned off
    }

    @Test
    void grantPermission_unknownProfileIsA404() {
        when(profiles.findByName("ghost")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.grantPermission("alice", "ghost", "orders:create"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void createProfile_conflictsWhenItAlreadyExists() {
        when(profiles.existsByName("support")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createProfile("alice", "support", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(profiles, never()).save(any(Profile.class));
    }

    @Test
    void registerResourceType_conflictsWhenItAlreadyExists() {
        when(resourceTypes.existsByName("orders")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.registerResourceType("alice", "orders", "orders-svc",
                        Map.of("orgIds", Map.of("type", "list"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(resourceTypes, never()).save(any(ResourceType.class));
    }

    @Test
    void setScope_rejectsValuesOfTheWrongDeclaredType() {
        givenOrdersType("{\"orgIds\":{\"item\":\"string\"}}");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setScope("alice", "support", "orders", List.of(in("orgIds", 1, 2))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scopes, never()).save(any(ProfileScope.class));
    }

    @Test
    void setScope_acceptsValuesMatchingTheDeclaredType() {
        givenOrdersType("{\"orgIds\":{\"item\":\"int\"}}");
        when(scopes.findByProfileIdAndResourceType_Id(any(), any())).thenReturn(Optional.empty());

        service.setScope("alice", "support", "orders", List.of(in("orgIds", 1, 2)));

        verify(scopes).save(any(ProfileScope.class));
    }

    @Test
    void changeAuditDetail_isEscapedThroughObjectMapper() throws Exception {
        // A permission key containing a quote must produce VALID jsonb — the old
        // string concatenation built `{"key":"orders:create"evil"}` and broke the cast.
        String tricky = "orders:create\"injected";
        when(profiles.findByName("support")).thenReturn(Optional.of(p));
        Permission perm = new Permission(tricky, null);
        when(permissions.findByKey(tricky)).thenReturn(Optional.of(perm));

        service.grantPermission("alice", "support", tricky);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).change(eq("alice"), eq("profile"), eq("support"), eq("grant_permission"), detail.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(detail.getValue(), Map.class);
        assertEquals(tricky, parsed.get("key"));
    }
}
