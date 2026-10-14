package com.example.rbac.model;

/**
 * Thrown when an authenticated user simply lacks the grant — no data scope for a
 * resource type, or nothing they may see. This is a legitimate <b>deny</b>, not a
 * server fault: hosts should map it to HTTP <b>403</b>, distinct from a 500 for a
 * genuine resolution/infrastructure error.
 *
 * <p>It lives in the dependency-free model module so both the PDP and the client
 * starter raise the same type. A host microservice that calls
 * {@code accessClient.scope(user, ...)} and wants a clean 403 can add:
 * <pre>{@code
 * @RestControllerAdvice
 * class Access {
 *   @ExceptionHandler(AccessDeniedException.class)
 *   ResponseEntity<Void> denied(AccessDeniedException e) {
 *     return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
 *   }
 * }
 * }</pre>
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
