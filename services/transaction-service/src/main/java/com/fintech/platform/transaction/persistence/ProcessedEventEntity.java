package com.fintech.platform.transaction.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The claim table behind exactly-once dispute resolution.
 *
 * <p>Same shape and same reasoning as the other services': the claim is an {@code INSERT ... ON
 * CONFLICT DO NOTHING} in the same transaction that reverses the payment, so a redelivered
 * resolution collides here instead of refunding twice. A refund issued twice for one dispute is
 * not a duplicate message — it is extra money, which is the one failure this platform treats as
 * categorically worse than the rest.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {}

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
