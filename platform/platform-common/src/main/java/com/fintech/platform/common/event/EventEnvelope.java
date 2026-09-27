package com.fintech.platform.common.event;

import com.fintech.platform.common.correlation.CorrelationId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The envelope every message on every Kafka topic is wrapped in.
 *
 * <p>Events in this platform are facts about something that has already happened, expressed in the
 * past tense ({@code transaction-authorized}, not {@code authorize-transaction}). That is what makes
 * late-arriving, duplicated and out-of-order delivery survivable, which is the whole reason Kafka is
 * in the architecture rather than an HTTP call between services.
 *
 * <p>Fields fall into three groups:
 *
 * <ul>
 *   <li><b>Identity</b> — {@code eventId} is assigned at publish time and is the idempotency key
 *       consumers deduplicate on. A redelivery from Kafka has the same {@code eventId}.
 *   <li><b>Routing</b> — {@code eventType} plus {@code eventVersion} let a consumer reject or
 *       safely ignore a payload shape it does not understand, instead of deserialising into a
 *       half-populated object.
 *   <li><b>Context</b> — {@code correlationId} ties the event back to the HTTP request that caused
 *       it; {@code aggregateType}/{@code aggregateId} make partition-by-key possible without parsing
 *       the payload.
 * </ul>
 *
 * @param <T> the event payload type
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String aggregateType,
        String aggregateId,
        T payload,
        Map<String, String> metadata) {

    public static final int CURRENT_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        requireNotBlank(eventId, "eventId");
        requireNotBlank(eventType, "eventType");
        requireNotBlank(aggregateType, "aggregateType");
        requireNotBlank(aggregateId, "aggregateId");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        correlationId = CorrelationId.isAcceptable(correlationId) ? correlationId : CorrelationId.generate();
    }

    private static void requireNotBlank(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public static <T> EventEnvelope<T> of(String eventType, String aggregateType, String aggregateId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_VERSION,
                Instant.now(),
                CorrelationId.current(),
                aggregateType,
                aggregateId,
                payload,
                Map.of());
    }

    public static <T> EventEnvelope<T> of(
            String eventType, String aggregateType, String aggregateId, T payload, Map<String, String> metadata) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_VERSION,
                Instant.now(),
                CorrelationId.current(),
                aggregateType,
                aggregateId,
                payload,
                metadata);
    }

    /**
     * An envelope whose {@code occurredAt} is supplied rather than read from the system clock.
     *
     * <p>For the outbox, where the point is that the event carries the same instant as the state change
     * that caused it. {@link #of} reading {@code Instant.now()} at serialisation time would put a
     * slightly later time on every event than on the payment it describes, and the difference — a few
     * milliseconds — is exactly the kind of drift that makes "what happened first?" unanswerable from
     * the topic alone.
     *
     * <p>The correlation id is still taken from {@link CorrelationId#current()}, because a service does
     * not get to choose that and the fallback generates one when there is no request in flight.
     */
    public static <T> EventEnvelope<T> ofAt(
            String eventType, String aggregateType, String aggregateId, T payload, Instant occurredAt) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_VERSION,
                occurredAt,
                CorrelationId.current(),
                aggregateType,
                aggregateId,
                payload,
                Map.of());
    }

    /** True when a consumer understands this event shape. */
    public boolean isSupportedVersion() {
        return eventVersion == CURRENT_VERSION;
    }

    public EventEnvelope<T> withMetadata(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new EventEnvelope<>(
                eventId,
                eventType,
                eventVersion,
                occurredAt,
                correlationId,
                aggregateType,
                aggregateId,
                payload,
                merged);
    }
}
