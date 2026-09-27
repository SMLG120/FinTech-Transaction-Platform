package com.fintech.platform.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The record of one consumed event, keyed by the event's own id.
 *
 * <p>Written in the same database transaction as the decision the event produced, which is what makes
 * consuming an event exactly-once in effect. The sequence is: insert this row, and if the insert
 * conflicts, the event has been seen before and the transaction rolls back. Because the insert and the
 * decision commit together, there is no window in which the event is marked processed but the decision
 * was lost, or the decision exists but the event will be applied again.
 *
 * <p>Keyed by the <b>event id</b> rather than by the payment's id, so a redelivery of a different event
 * about the same payment — a re-score request, say — is processed as itself. The payment's id has its own
 * second line of defence in {@code risk_decisions}'s primary key, and the two are not redundant: the
 * event id catches the redelivery, and the decision's key catches the case where two <em>different</em>
 * events both try to write a first decision for one payment.
 *
 * <p>Rows are deleted by {@code FraudEventDeduplicator} after the retention window, which is what stops
 * this table becoming the largest thing in the database. A deduplication record that is kept forever is a
 * deduplication table that has to be vacuumed forever.
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

    public static ProcessedEventEntity of(UUID eventId, String topic, Instant now) {
        return new ProcessedEventEntity(eventId, topic, now);
    }

    public UUID eventId() {
        return eventId;
    }

    public String topic() {
        return topic;
    }

    public Instant processedAt() {
        return processedAt;
    }
}
