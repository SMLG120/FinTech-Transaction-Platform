package com.fintech.platform.audit.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.audit.messaging.AuditPayloads.AuditEventPayload;
import com.fintech.platform.audit.service.AuditService;
import com.fintech.platform.audit.service.AuditService.AuditFact;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.common.event.KafkaTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns consumed audit events into trail rows.
 *
 * <p>One listener, because one topic carries every producer's facts and the envelope already says
 * which fact it is: the {@code eventType} is the action, the aggregate names the resource, and the
 * payload carries the actor, the outcome and the detail. A listener per action would be a listener
 * per producer vocabulary, and the trail would need a deploy every time a producer found a new
 * thing worth recording.
 *
 * <p><b>A malformed event is not silently dropped.</b> Deserialization failures propagate, so the
 * listener's retry and dead-letter policy applies: after the configured attempts the event lands on
 * {@code dead-letter-events} where somebody will find it. Catching and logging would be the worst
 * outcome available — a staff action happened and the trail has a hole in it, and nothing anywhere
 * says so.
 *
 * <p><b>The action comes from the payload, cross-checked against the envelope.</b> The two travel
 * together from the producer's outbox, so a mismatch between them is a corrupted or foreign event
 * rather than a new vocabulary — and recording it under either name would file the fact where no
 * auditor will look for it.
 */
@Component
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);

    private final AuditService trail;

    private final ObjectMapper json;

    private final Counter consumed;

    private final Counter duplicates;

    private final Counter malformed;

    public AuditConsumer(AuditService trail, ObjectMapper json, MeterRegistry meters) {
        this.trail = trail;
        this.json = json;
        this.consumed = Counter.builder("audit.events.consumed")
                .description("Audit events this service recorded, after deduplication")
                .register(meters);
        this.duplicates = Counter.builder("audit.events.duplicates")
                .description("Deliveries of an event whose id has already been claimed")
                .register(meters);
        this.malformed = Counter.builder("audit.events.malformed")
                .description("Deliveries that could not be understood and will be dead-lettered")
                .register(meters);
    }

    /** A staff or system action worth keeping, from any producer. */
    @KafkaListener(topics = KafkaTopics.AUDIT_EVENTS, groupId = "${app.audit.consumer-group:audit-service}")
    public void onAuditEvent(String record) {
        EventEnvelope<JsonNode> envelope = envelope(record, KafkaTopics.AUDIT_EVENTS);
        AuditEventPayload payload = read(envelope, AuditEventPayload.class);
        payload.requireRecordable();
        if (!envelope.eventType().equals(payload.action())) {
            malformed.increment();
            // The envelope and the payload left the producer's outbox together, so they agree by
            // construction. A mismatch is a foreign producer or a corrupted payload, and either way
            // the fact is unfileable: recording it under the envelope's name files it where no
            // auditor will look, and under the payload's name trusts the half that disagrees.
            throw new IllegalArgumentException("audit envelope type '" + envelope.eventType()
                    + "' does not match payload action '" + payload.action() + "'");
        }
        UUID eventId = parseEventId(envelope.eventId());
        String metadata = metadataOf(payload);
        if (!trail.record(
                eventId,
                KafkaTopics.AUDIT_EVENTS,
                new AuditFact(
                        payload.action(),
                        payload.resourceType(),
                        payload.resourceId(),
                        payload.transactionUuidOrNull(),
                        blankToNull(payload.actorDigest()),
                        payload.result(),
                        blankToNull(envelope.correlationId()),
                        metadata,
                        envelope.occurredAt()))) {
            duplicates.increment();
            log.info("Ignoring redelivery of {} from {}; already recorded", eventId, KafkaTopics.AUDIT_EVENTS);
            return;
        }
        consumed.increment();
    }

    private String metadataOf(AuditEventPayload payload) {
        if (payload.metadata() == null || payload.metadata().isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(payload.metadata());
        } catch (JsonProcessingException e) {
            malformed.increment();
            throw new IllegalArgumentException("audit metadata is not renderable as JSON", e);
        }
    }

    private EventEnvelope<JsonNode> envelope(String record, String topic) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = json.readValue(record, new TypeReference<EventEnvelope<JsonNode>>() {});
        } catch (JsonProcessingException e) {
            malformed.increment();
            throw new IllegalArgumentException("not a readable event envelope from " + topic, e);
        }
        if (!envelope.isSupportedVersion()) {
            malformed.increment();
            throw new IllegalArgumentException(
                    "unsupported event version " + envelope.eventVersion() + " for " + envelope.eventType());
        }
        return envelope;
    }

    private <T> T read(EventEnvelope<JsonNode> envelope, Class<T> type) {
        try {
            return json.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            malformed.increment();
            throw new IllegalArgumentException("unreadable " + type.getSimpleName() + " payload", e);
        }
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            malformed.increment();
            throw new IllegalArgumentException("eventId is not a UUID: " + eventId, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
