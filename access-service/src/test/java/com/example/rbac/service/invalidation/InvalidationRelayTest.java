package com.example.rbac.service.invalidation;

import com.example.rbac.model.dto.InvalidationEvent;
import com.example.rbac.service.cache.VersionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the relay's shared processing path (C6) — the at-least-once,
 * failure-tolerant bump + publish. Pure Mockito (no live infra): the outbox, the
 * version service and the bus publisher are all simulated, so we pin the semantics
 * that make the durable fallback safe:
 *
 * <ul>
 *   <li>happy path: claim &rarr; bump &rarr; publish &rarr; mark published;</li>
 *   <li>a lost claim is a no-op (another worker owns the row);</li>
 *   <li>a publish or bump failure leaves the row PENDING (never marks it) and never
 *       throws — so the next relay poll retries it;</li>
 *   <li>the ops escape hatch bumps + publishes and returns the new version.</li>
 * </ul>
 */
class InvalidationRelayTest {

    private OutboxService outbox;
    private VersionService version;
    private InvalidationPublisher publisher;
    private MeterRegistry metrics;
    private InvalidationRelay relay;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxService.class);
        version = mock(VersionService.class);
        publisher = mock(InvalidationPublisher.class);
        metrics = new SimpleMeterRegistry();
        relay = new InvalidationRelay(outbox, version, publisher, metrics);
    }

    @Test
    void process_claimsBumpsPublishesAndMarks() {
        when(outbox.tryClaim(7L)).thenReturn(true);
        when(version.bump()).thenReturn(42L);

        relay.process(7L);

        verify(outbox).tryClaim(7L);
        verify(version).bump();
        verify(publisher).publish(any(InvalidationEvent.class));
        verify(outbox).markPublished(7L, 42L);
        assertThat(counterCount("rbac.pdp.outbox.published")).isEqualTo(1.0);
    }

    @Test
    void process_publishesTheBumpedVersion_globally() {
        when(outbox.tryClaim(7L)).thenReturn(true);
        when(version.bump()).thenReturn(42L);

        relay.process(7L);

        ArgumentCaptor<InvalidationEvent> event = ArgumentCaptor.forClass(InvalidationEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().version()).isEqualTo(42L);
        assertThat(event.getValue().userId()).as("global invalidation carries no userId").isNull();
    }

    @Test
    void process_noopWhenClaimLost() {
        when(outbox.tryClaim(7L)).thenReturn(false);

        relay.process(7L);

        verify(version, never()).bump();
        verify(publisher, never()).publish(any());
        verify(outbox, never()).markPublished(anyLong(), anyLong());
        assertThat(counterCount("rbac.pdp.outbox.published")).as("no publish happened").isEqualTo(0.0);
    }

    @Test
    void process_publishFailureLeavesRowPending_andDoesNotThrow() {
        when(outbox.tryClaim(7L)).thenReturn(true);
        when(version.bump()).thenReturn(42L);
        doThrow(new IllegalStateException("simulated broker outage")).when(publisher).publish(any());

        assertThatCode(() -> relay.process(7L)).as("a publish failure must not propagate").doesNotThrowAnyException();

        verify(outbox, never()).markPublished(anyLong(), anyLong());   // stays pending -> retried
        assertThat(counterCount("rbac.pdp.outbox.failed")).isEqualTo(1.0);
    }

    @Test
    void process_bumpFailureLeavesRowPending_andDoesNotThrow() {
        when(outbox.tryClaim(7L)).thenReturn(true);
        when(version.bump()).thenThrow(new IllegalStateException("simulated Redis outage"));

        assertThatCode(() -> relay.process(7L)).as("a bump failure must not propagate").doesNotThrowAnyException();

        verify(publisher, never()).publish(any());
        verify(outbox, never()).markPublished(anyLong(), anyLong());   // stays pending -> retried
        assertThat(counterCount("rbac.pdp.outbox.failed")).isEqualTo(1.0);
    }

    @Test
    void forceInvalidate_bumpsPublishesAndReturnsVersion() {
        when(version.bump()).thenReturn(99L);

        long v = relay.forceInvalidate();

        assertThat(v).isEqualTo(99L);
        ArgumentCaptor<InvalidationEvent> event = ArgumentCaptor.forClass(InvalidationEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().version()).isEqualTo(99L);
        assertThat(counterCount("rbac.pdp.outbox.published")).isGreaterThanOrEqualTo(1.0);
    }

    /** Lenient counter read — the meter may not exist yet (see AGENTS.md gotcha #24b). */
    private double counterCount(String name) {
        var c = metrics.find(name).counters();
        return (c.isEmpty()) ? 0.0 : c.iterator().next().count();
    }
}
