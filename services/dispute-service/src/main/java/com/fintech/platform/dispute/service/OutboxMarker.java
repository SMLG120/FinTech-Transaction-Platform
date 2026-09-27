package com.fintech.platform.dispute.service;

import com.fintech.platform.dispute.persistence.OutboxRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks outbox rows published or failed, each in its own transaction.
 *
 * <p>Separate transactions from the relay's poll, because the relay holds no transaction across its
 * network round trips: marking must commit even when the next row's send is still in flight, or a
 * published event stays pending and goes out twice for no reason.
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
     * <p>Already-published is a no-op rather than an error, because at-least-once delivery means the
     * relay reaching the same row twice is expected behaviour and not a fault.
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
