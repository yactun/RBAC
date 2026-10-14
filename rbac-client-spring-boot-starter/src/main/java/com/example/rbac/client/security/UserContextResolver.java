package com.example.rbac.client.security;

import com.example.rbac.model.UserContext;

/**
 * Resolves the {@link UserContext} for the current request (userId + profile set).
 * The client OWNS identity: it has already validated the user against the IDM and
 * extracted the profiles. Host apps provide their own implementation — how the
 * profile set is derived is app-specific (token claims, a verified header, etc.).
 * See the default Spring Security-based implementation in
 * {@code RbacClientAutoConfiguration}.
 */
@FunctionalInterface
public interface UserContextResolver {

    UserContext current();
}
