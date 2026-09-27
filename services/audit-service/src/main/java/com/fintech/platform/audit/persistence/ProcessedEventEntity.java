package com.fintech.platform.audit.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The claim table behind exactly-once recording.
 *
 * <p>Same shape and same reasoning as the other services': the claim is an {@code INSERT ... ON
 * CONFLICT DO NOTHING} in the same transaction that writes the audit row, so a redelivered event
 * collides here instead of writing the same fact twice. A trail that counts one claim as two is a
 * trail that cannot be reconciled against the service that produced it.
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
