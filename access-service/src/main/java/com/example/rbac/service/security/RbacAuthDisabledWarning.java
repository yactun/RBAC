package com.example.rbac.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Loud startup warning when PDP auth is off (the dev/test default): anyone who
 * can reach the port can author grant changes, and the audit actor is the
 * untrusted {@code X-Actor} header.
 */
@Configuration
@ConditionalOnProperty(prefix = "rbac.pdp.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
public class RbacAuthDisabledWarning {

    private static final Logger log = LoggerFactory.getLogger(RbacAuthDisabledWarning.class);

    public RbacAuthDisabledWarning() {
        log.warn("===========================================================================");
        log.warn("rbac.pdp.auth.enabled is not true — the PDP is UNAUTHENTICATED:");
        log.warn("any caller can invoke the decision API AND author admin changes, and the");
        log.warn("audit actor is the untrusted X-Actor header. Do NOT run like this in a");
        log.warn("production environment. Set rbac.pdp.auth.enabled=true and either");
        log.warn("  rbac.pdp.auth.mode=mesh   (service mesh mTLS + workload identity), or");
        log.warn("  rbac.pdp.auth.mode=jwt   (OAuth2 client-credentials / OIDC bearer).");
        log.warn("===========================================================================");
    }
}
