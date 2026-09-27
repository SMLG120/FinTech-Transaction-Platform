package com.fintech.platform.transaction.service;

import com.fintech.platform.transaction.persistence.OutboxRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks outbox rows published or failed, each in its own transaction.
 *
 * <p>A separate bean rather than two methods on {@link OutboxRelay}, and the reason is Spring's
 * proxying: {@code @Transactional} on a method the containing class calls itself is a no-op, because
 * the call does not pass through the proxy. A {@code markPublished} method on the relay would be called
 * from the relay, so its {@code REQUIRES_NEW} would be silently ignored and each mark would join
 * whatever transaction happened to be open — which is exactly the thing that has to be independent.
 *
 * <p>{@code REQUIRES_NEW} because a mark must commit or roll back on its own terms. Batched into a
 * surrounding transaction, one failure would discard the marks for events that were already sent, and
 * they would be published again indefinitely.
 */
@Component
public class OutboxMarker {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxMarker(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Marks an event published, if it is still pending. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        outbox.findById(eventId).ifPresent(event -> {
            // The relay can reach the same row twice, since at-least-once delivery means a duplicate
            // send is expected. Marking an already-published row is a no-op rather than an error, so a
            // redelivery does not wedge the relay.
            if (event.isPending()) {
                event.markPublished(clock.instant());
            }
        });
    }

    /** Records a failed attempt so the event is retried and the reason stays visible. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, String error) {
        outbox.findById(eventId).ifPresent(event -> {
            if (event.isPending()) {
                event.markFailed(error);
            }
        });
    }

    /** How many events are still waiting. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return outbox.countPending();
    }
}
