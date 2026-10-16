package com.example.rbac.model;

/**
 * Fail-closed (ADR-002 S2): a scope was asked for a dimension it does not declare
 * (typo, a dimension the profile never scoped, …). Throwing — instead of the old
 * behavior of returning an empty list — is what stops the documented pattern
 * {@code if (!scope.values(dim).isEmpty()) query = query.in(dim, values)} from turning
 * a mistake into <b>no filter = all rows</b>.
 *
 * <p>This is a resolution error, not a legitimate deny: hosts should let it surface
 * as a <b>500</b> (an unscoped query must never run) — unlike
 * {@link AccessDeniedException} (a clean 403). It lives in the dependency-free model
 * module so both the PDP and the client starter raise the same type.
 */
public class UnknownDimensionException extends RuntimeException {

    public UnknownDimensionException(String message) {
        super(message);
    }
}
