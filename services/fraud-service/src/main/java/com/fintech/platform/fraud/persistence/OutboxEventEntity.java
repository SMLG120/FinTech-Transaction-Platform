package com.fintech.platform.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event waiting to be published, in this service's own outbox.
 *
 * <p>The same pattern, and deliberately the same code, as transaction-service's outbox — a row written in
 * the same database transaction as the decision it describes, published afterwards by a relay, marked
 * published when the send succeeded. The reasoning is in ADR-0007 and it has not changed: commit-then-
 * publish leaves a window in which a crash loses the event, and at-least-once delivery is the right
 * asymmetry because a duplicate can be deduplicated and a lost one cannot.
 *
 * <p><b>This is a copy, not a shared class, and that is a debt with a plan.</b> Two services now have an
 * outbox entity, a relay, a repository and a publish loop. When a third service needs one, the four pieces
 * are lifted into {@code platform-common} in a single change rather than a third copy — the alternative,
 * extracting it "properly" now, means editing a service that is already in production and tested, for the
 * sake of a consumer that does not exist yet. ADR-0008 records this as the first thing to do when the
 * next service needs it.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 128)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEventEntity() {}

    private OutboxEventEntity(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            Instant occurredAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aggregateType = aggregateType;
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.aggregateVersion = aggregateVersion;
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
        this.eventKey = Objects.requireNonNull(eventKey, "eventKey must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.createdAt = occurredAt;
        this.attempts = 0;
    }

    public static OutboxEventEntity pending(
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            Instant occurredAt) {
        return new OutboxEventEntity(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                aggregateVersion,
                topic,
                eventKey,
                eventType,
                payload,
                occurredAt);
    }

    /** Marks it sent. The row stays; a published outbox row is the audit of what was announced. */
    public void markPublished(Instant now) {
        this.publishedAt = Objects.requireNonNull(now, "now must not be null");
        this.lastError = null;
    }

    /**
     * Records a failed attempt.
     *
     * <p>The message is truncated at 500 characters. A driver exception can carry the whole JDBC
     * connection string, and an outbox table that logs unbounded text from a failure is a table that
     * eventually contains a password.
     *
     * <p>{@code occurredAt} is deliberately not moved. The row's occurrence is when the decision was
     * made, which does not change because publishing failed; moving it would make the relay's
     * oldest-first ordering bury a row that keeps failing under newer ones.
     */
    public void markFailed(String error) {
        this.attempts = this.attempts + 1;
        String message = error == null ? "unknown" : error;
        this.lastError = message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** Whether this row is still waiting to be published. */
    public boolean isPending() {
        return publishedAt == null;
    }

    public UUID id() {
        return id;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public long aggregateVersion() {
        return aggregateVersion;
    }

    public String topic() {
        return topic;
    }

    public String eventKey() {
        return eventKey;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
