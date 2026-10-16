package com.example.rbac.service.api;

import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.Scope;
import com.example.rbac.service.audit.AuditService;
import com.example.rbac.service.cache.GrantCache;
import com.example.rbac.service.cache.VersionService;
import com.example.rbac.service.rbac.GrantLoader;
import com.example.rbac.service.rbac.ProfileGrants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REVIEW #5 — a Redis outage must NOT take down authorization. When the policy-version
 * read and the L2 read both fail (Redis down), the PDP must still serve **correct grants
 * from Postgres** (compute, uncached) instead of 500-ing. Pure unit test (Mockito) — no
 * live Redis needed; the outage is simulated by the version/cache beans throwing.
 */
class GrantServiceRedisDownTest {

    private static final UserContext USER = new UserContext("u-1", Set.of("ops"));

    private GrantService redisDownService() {
        VersionService version = mock(VersionService.class);
        GrantCache cache = mock(GrantCache.class);
        GrantLoader loader = mock(GrantLoader.class);
        AuditService audit = mock(AuditService.class);

        RedisConnectionFailureException down =
                new RedisConnectionFailureException("simulated Redis outage (connection refused)");
        when(version.current()).thenThrow(down);
        when(cache.get(any(), any())).thenThrow(down);

        // What the loader would return from Postgres (healthy): the "ops" profile grants orders:create.
        ProfileGrants ops = new ProfileGrants("ops", Set.of("orders:create"),
                Map.of("orders", List.of(new Scope.Filter("orgIds", Scope.Op.IN, List.of(1, 2)))), List.of());
        when(loader.load(any())).thenReturn(List.of(ops));

        return new GrantService(cache, version, loader, audit, new SimpleMeterRegistry());
    }

    @Test
    void redisDown_grantsStillServedFromPostgres_notAnError() {
        GrantService service = redisDownService();

        assertThatCode(() -> service.grants(USER)).as("a Redis outage is not a 500").doesNotThrowAnyException();

        GrantSet gs = service.grants(USER);
        assertThat(gs.permissions()).containsExactly("orders:create");
        ScopeHolder.assertScope(gs);
    }

    @Test
    void redisDown_checkStillDecidesCorrectly() {
        GrantService service = redisDownService();

        CheckResult allowed = service.check(USER, "orders:create", "req", "orders-svc");
        assertThat(allowed.allowed()).as("granted permission is still allowed").isTrue();

        CheckResult denied = service.check(USER, "orders:refund", "req", "orders-svc");
        assertThat(denied.allowed()).as("absent permission is still denied").isFalse();
    }

    /** Small holder so the assertion above reads cleanly. */
    static final class ScopeHolder {
        static void assertScope(GrantSet gs) {
            assertThat(gs.scopes().get("orders").values("orgIds")).containsExactly(1, 2);
        }
    }
}
