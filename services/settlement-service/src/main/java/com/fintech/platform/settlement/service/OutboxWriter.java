package com.fintech.platform.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.settlement.persistence.OutboxEventEntity;
import com.fintech.platform.settlement.persistence.OutboxRepository;
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
 * <p>{@link Propagation#MANDATORY} is the point. The method refuses to run unless a transaction already
 * exists, so a cycle cannot be closed without its announcement and a break cannot be recorded without the
 * event that wakes somebody up about it. With the default propagation, a call from a non-transactional
 * method would open its own transaction, commit the event, and leave the cycle uncommitted — announcing a
 * fact the database does not have, which is exactly what the outbox exists to prevent.
 *
 * <p>The third copy of this class; see {@code OutboxEventEntity} for why the shared extraction ADR-0008
 * planned did not happen.
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
     * @param aggregateType {@code "SettlementCycle"}, so a consumer can filter without parsing the payload
     * @param aggregateId the cycle's id
     * @param aggregateVersion the cycle's status ordinal at the time, so a consumer can order the
     *     cycle's own lifecycle
     * @param eventKey the partition key, the cycle's reference
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
        log.debug("recorded {} for cycle {}", eventType, aggregateId);
    }

    /**
     * Serialises the envelope, failing the caller's transaction if it cannot.
     *
     * <p>A payload that cannot be serialised will never become serialisable, and swallowing the error
     * would leave a cycle reconciled with no announcement of it. Letting it propagate rolls the whole
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
