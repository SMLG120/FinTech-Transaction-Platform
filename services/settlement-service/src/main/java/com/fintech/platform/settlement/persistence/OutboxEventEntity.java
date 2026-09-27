package com.fintech.platform.settlement.persistence;

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
 * <p>The third copy of this class. ADR-0008 recorded the plan to lift the first two into
 * {@code platform-common} when a third service needed one, and this is that service — but the plan was
 * written before anyone compared the two, and they have diverged: transaction-service's relay is more than
 * twice the size of fraud-service's, because the two services park a failed event differently. Lifting
 * them now would mean editing two tested services and reconciling a behavioural difference nobody has
 * characterised, in a phase whose subject is settlement.
 *
 * <p>So this is a copy, and the debt is now three deep. That is the honest cost of the sequencing, and it
 * is cheaper than the alternative: extracting it properly is a change with its own tests, on its own
 * merits, that nobody will thank the settlement phase for bundling. ADR-0009 records the same debt again
 * for the same reason.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
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
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
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

    /**
     * Creates a pending outbox row.
     *
     * @param aggregateType the aggregate the event is about
     * @param aggregateId the aggregate's id
     * @param aggregateVersion the version the change produced
     * @param topic the topic to publish to
     * @param eventKey the partition key
     * @param eventType the event's type
     * @param payload the serialised envelope
     * @param occurredAt when the fact being announced happened
     * @return the pending row
     */
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
                aggregateType, aggregateId, aggregateVersion, topic, eventKey, eventType, payload, occurredAt);
    }

    /** Marks it sent. The row stays; a published outbox row is the audit of what was announced. */
    public void markPublished(Instant now) {
        this.publishedAt = Objects.requireNonNull(now, "now must not be null");
        this.lastError = null;
    }

    /**
     * Records a failed attempt.
     *
     * <p>{@code occurredAt} is deliberately not moved. The row's occurrence is when the fact happened,
     * which does not change because publishing failed; moving it would make the relay's oldest-first
     * ordering bury a row that keeps failing under newer ones.
     *
     * @param error the failure, or null when the driver gave none
     */
    public void markFailed(String error) {
        this.attempts = this.attempts + 1;
        String message = error == null ? "unknown" : error;
        this.lastError = message.length() > 1000 ? message.substring(0, 1000) : message;
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
