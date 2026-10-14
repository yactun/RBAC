package com.example.rbac.client.invalidation;

import com.example.rbac.model.dto.InvalidationEvent;

/** Receives invalidation events from the bus and acts on local caches. */
@FunctionalInterface
public interface InvalidationHandler {
    void onEvent(InvalidationEvent event);
}
