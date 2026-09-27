package com.fintech.platform.audit.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable fact, recorded exactly as the producing service stated it.
 *
 * <p>The row is written once and never changed: there is no setter, no update path, and a database
 * trigger refuses UPDATE and DELETE outright, so append-only survives even a SQL client with a
 * deadline. A trail that can be edited after the fact is a second draft of history, and a second
 * draft is what a regulator assumes is hiding the first.
 *
 * <p>The record interprets nothing. The action, result and metadata are the producer's own words,
 * stored rather than shredded, because a recording service that grades the vocabulary starts
 * disagreeing with the service it records — and the disagreement reads as the trail being wrong
 * when it was the interpretation.
 */
@Entity
@Table(name = "audit_records")
public class AuditRecordEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "actor_digest", length = 128)
    private String actorDigest;

    @Column(name = "result", nullable = false, length = 32)
    private String result;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AuditRecordEntity() {}

    private AuditRecordEntity(
            UUID id,
            UUID eventId,
            String topic,
            String action,
            String resourceType,
            String resourceId,
            UUID transactionId,
            String actorDigest,
            String result,
            String correlationId,
            String metadata,
            Instant occurredAt,
            Instant receivedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
        this.result = Objects.requireNonNull(result, "result must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        this.transactionId = transactionId;
        this.actorDigest = actorDigest;
        this.correlationId = correlationId;
        this.metadata = metadata;
    }

    public static AuditRecordEntity record(
            UUID eventId,
            String topic,
            String action,
            String resourceType,
            String resourceId,
            UUID transactionId,
            String actorDigest,
            String result,
            String correlationId,
            String metadata,
            Instant occurredAt,
            Instant receivedAt) {
        return new AuditRecordEntity(
                UUID.randomUUID(),
                eventId,
                topic,
                action,
                resourceType,
                resourceId,
                transactionId,
                actorDigest,
                result,
                correlationId,
                metadata,
                occurredAt,
                receivedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getActorDigest() {
        return actorDigest;
    }

    public String getResult() {
        return result;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
