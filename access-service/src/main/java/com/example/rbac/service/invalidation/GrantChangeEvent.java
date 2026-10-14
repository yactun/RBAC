package com.example.rbac.service.invalidation;

/**
 * Published (synchronously, inside the admin transaction) to signal that grants
 * changed and clients must be invalidated. The actual version bump + bus publish
 * is deferred to AFTER the transaction commits (see {@link InvalidationOnCommit}),
 * so a client can never observe a new policy version before the change is durable.
 *
 * <p>Carries no payload: the event means "something changed, invalidate all".
 */
public final class GrantChangeEvent {

    /** Shared singleton — the event is a stateless marker. */
    public static final GrantChangeEvent INSTANCE = new GrantChangeEvent();

    private GrantChangeEvent() {
    }
}
