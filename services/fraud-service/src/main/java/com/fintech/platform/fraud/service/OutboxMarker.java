package com.fintech.platform.fraud.service;

import com.fintech.platform.fraud.persistence.OutboxRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks outbox rows published or failed, each in its own transaction.
 *
 * <p>A separate bean for Spring's proxying, not a stylistic choice: {@code @Transactional} on a method a
 * class calls on itself does nothing, because the call never passes through the proxy. A
 * {@code markPublished} on the relay would be called from the relay, its {@code REQUIRES_NEW} would be
 * silently ignored, and each mark would join whatever transaction happened to be open — which is exactly
 * the thing that has to be independent.
 *
 * <p>{@code REQUIRES_NEW} because a mark must commit or roll back on its own terms. Batched into a
 * surrounding transaction, one failure would discard the marks for events already sent, and they would be
 * published again forever.
 */
@Component
public class OutboxMarker {

    private final OutboxRepository outbox;

    private final Clock clock;

    public OutboxMarker(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Marks an event published, if it is still pending.
     *
     * <p>Already-published is a no-op rather than an error, because at-least-once delivery means the relay
     * reaching the same row twice is expected behaviour and not a fault.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        outbox.findById(eventId).ifPresent(event -> {
            if (event.isPending()) {
                event.markPublished(clock.instant());
            }
        });
    }

    /** Records a failed attempt, so the row stays pending and the reason is visible on it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, String error) {
        outbox.findById(eventId).ifPresent(event -> {
            if (event.isPending()) {
                event.markFailed(error);
            }
        });
    }

    /** How many rows are still waiting. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return outbox.countPending();
    }
}
