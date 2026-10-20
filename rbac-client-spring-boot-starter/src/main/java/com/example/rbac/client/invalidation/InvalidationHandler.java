package com.example.rbac.client.invalidation;

import com.example.rbac.model.dto.InvalidationEvent;

/** Receives invalidation events from the bus and acts on local caches. */
@FunctionalInterface
public interface InvalidationHandler {

    void onEvent(InvalidationEvent event);

    /**
     * Called when the invalidation bus connection is (re)established — i.e. after a
     * startup, or after the broker went away and came back. A good moment for a
     * client to re-sync anything it may have missed while the bus was down (e.g. the
     * local policy version). Default: no-op.
     */
    default void onBusConnected() {
    }
}
