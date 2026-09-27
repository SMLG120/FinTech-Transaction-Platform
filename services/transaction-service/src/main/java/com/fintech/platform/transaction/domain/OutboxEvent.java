package com.fintech.platform.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event waiting to be published to Kafka.
 *
 * <p><b>Why a row and not a publish.</b> The alternative is to commit the payment and then publish,
 * which leaves a window between the two in which a crash loses the event: the money moved, the row says
 * {@code SETTLED}, and nobody was ever told. That window is small and it is exactly the size that makes
 * the bug survive to production. Writing the event as a row in the same transaction as the state change
 * removes the window entirely — the event and the payment commit together or not at all.
 *
 * <p><b>Delivery is at least once, and that is the correct choice.</b> The relay publishes and then
 * marks the row published; a crash in between republishes. So a consumer may see the same event twice
 * and must tolerate it. It may never see one zero times. The asymmetry decides it: a consumer can
 * deduplicate on {@link #aggregateVersion}, whereas a lost payment event can be recovered by nothing at
 * all, so the failure mode is pushed to the side that can handle it.
 *
 * <p><b>Never updated in place except to be marked published.</b> The payload is what the relay sends
 * and what a consumer will act on; rewriting it after the fact would mean the event in the topic and
 * the event in the table are not the same event.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    /**
     * The type of aggregate this event describes, e.g. {@code "Transaction"}.
     *
     * <p>Part of the deduplication key with {@link #aggregateVersion}, so a consumer's rule is "I have
     * seen Transaction 7 version 3, therefore version 3 again is a redelivery" — stated per aggregate
     * type, so two different aggregates at the same version do not shadow each other.
     */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /**
     * The aggregate's version when the event was recorded.
     *
     * <p>Included in the payload as well as here, because a consumer cannot deduplicate on a value it
     * cannot see. A consumer that has processed version N and receives N again ignores it; one that
     * receives N+1 applies it. This is what makes at-least-once delivery cost a redundant message
     * rather than a double-applied state change.
     */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    /**
     * The partition key, normally the aggregate id.
     *
     * <p>Events for one payment go to one partition, so a consumer reads that payment's events in the
     * order they happened. Without it, a {@code transaction-settled} could be processed before the
     * {@code transaction-authorized} that caused it, and the consumer would have to resolve that
     * itself.
     */
    @Column(name = "event_key", nullable = false, length = 128)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    /** Null until published. The relay's work queue. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * How many times publishing has been attempted.
     *
     * <p>Transport failures are worth seeing and worth retrying, and an event that fails silently
     * forever is a payment whose state change nobody ever hears about.
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEvent() {}

    private OutboxEvent(
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
        this.aggregateType = require(aggregateType, "aggregateType", 64);
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.aggregateVersion = aggregateVersion;
        this.topic = require(topic, "topic", 128);
        this.eventKey = require(eventKey, "eventKey", 128);
        this.eventType = require(eventType, "eventType", 64);
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.createdAt = occurredAt;
        this.attempts = 0;
    }

    /**
     * Records an event to be published with the state change in the same transaction.
     *
     * @param aggregateVersion the aggregate's version after the change, so a consumer can order and
     *     deduplicate on it
     */
    public static OutboxEvent record(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            Instant occurredAt) {
        return new OutboxEvent(
                id, aggregateType, aggregateId, aggregateVersion, topic, eventKey, eventType, payload, occurredAt);
    }

    /**
     * An event for a payment, keyed by the payment's id so its events stay in order on one partition.
     *
     * <p><b>The version is passed in rather than read from the transaction.</b> The caller has just flushed
     * the state change and is about to put the same number in the event body, and a version that could
     * differ between the two — because it was read twice off an object that is not reliably in step with
     * the row — would be worse than either value being wrong alone. A consumer would deduplicate on the
     * row's number and act on the body's.
     */
    public static OutboxEvent forTransaction(
            Transaction transaction,
            long aggregateVersion,
            String topic,
            String eventType,
            String payload,
            Instant occurredAt) {
        return record(
                UUID.randomUUID(),
                "Transaction",
                transaction.id(),
                aggregateVersion,
                topic,
                transaction.id().toString(),
                eventType,
                payload,
                occurredAt);
    }

    private static String require(String value, String field, int max) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    field + " must be at most " + max + " characters, got " + trimmed.length());
        }
        return trimmed;
    }

    /** Whether the relay still has work to do for this event. */
    public boolean isPending() {
        return publishedAt == null;
    }

    /** Records a successful publish. The row is kept, not deleted — the log of what was sent is worth more than the space. */
    public void markPublished(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (publishedAt != null) {
            throw new IllegalStateException("outbox event " + id + " was already published at " + publishedAt);
        }
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * Records a failed attempt so the event is retried and the reason is visible.
     *
     * <p>The message is truncated because a broker error can be arbitrarily long and this column is in a
     * table that grows forever.
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 2000));
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

    @Override
    public String toString() {
        return "OutboxEvent[" + aggregateType + " " + aggregateId + " v" + aggregateVersion + " " + eventType
                + (isPending() ? " pending" : " published") + "]";
    }
}
