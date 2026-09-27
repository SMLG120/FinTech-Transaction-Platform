package com.fintech.platform.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An event id already turned into a notification, for deduplication.
 *
 * <p>The same table and the same reasoning as the other services': delivery is at least once, so a
 * consumer that does not dedupe tells the customer twice. The claim is {@code ON CONFLICT DO NOTHING}
 * against this primary key rather than a check-then-insert, because two consumers of the same
 * partition must not both find the row absent and both send.
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

    private ProcessedEventEntity(UUID eventId, String topic, Instant processedAt) {
        this.eventId = eventId;
        this.topic = topic;
        this.processedAt = processedAt;
    }

    public static ProcessedEventEntity processed(UUID eventId, String topic, Instant now) {
        return new ProcessedEventEntity(eventId, topic, now);
    }

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
