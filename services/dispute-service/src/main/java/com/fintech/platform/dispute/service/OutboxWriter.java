package com.fintech.platform.dispute.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.dispute.persistence.OutboxEventEntity;
import com.fintech.platform.dispute.persistence.OutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events into this service's outbox, in the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} is the point. The method refuses to run unless a transaction
 * already exists, so a dispute cannot be opened without its announcement and resolved without the
 * event that moves the money. With the default propagation, a call from a non-transactional method
 * would open its own transaction, commit the event, and leave the dispute uncommitted — announcing
 * a fact the database does not have, which is exactly what the outbox exists to prevent.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxRepository outbox;

    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an event in the caller's transaction.
     *
     * @param aggregateType {@code "Dispute"}, so a consumer can filter without parsing the payload
     * @param aggregateId the dispute's id
     * @param aggregateVersion the dispute's status ordinal at the time, so a consumer can order the
     *     case's own lifecycle
     * @param eventKey the partition key, the dispute's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String topic,
            String eventType,
            Object payload,
            Instant occurredAt) {
        String envelopeJson =
                serialise(EventEnvelope.ofAt(eventType, aggregateType, aggregateId.toString(), payload, occurredAt));
        outbox.save(OutboxEventEntity.pending(
                aggregateType,
                aggregateId,
                aggregateVersion,
                topic,
                aggregateId.toString(),
                eventType,
                envelopeJson,
                occurredAt));
        log.debug("recorded {} for dispute {}", eventType, aggregateId);
    }

    /**
     * Serialises the envelope, failing the caller's transaction if it cannot.
     *
     * <p>A payload that cannot be serialised will never become serialisable, and swallowing the error
     * would leave a dispute resolved with no announcement of it. Letting it propagate rolls the whole
     * thing back, which is honest: nothing happened, and the caller can retry.
     */
    private String serialise(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload could not be serialised: " + envelope.eventType(), e);
        }
    }
}
