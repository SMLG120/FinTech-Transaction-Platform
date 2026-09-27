package com.fintech.platform.fraud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.fraud.persistence.OutboxEventEntity;
import com.fintech.platform.fraud.persistence.OutboxRepository;
import java.time.Clock;
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
 * exists, so an event cannot be written outside the transaction that made the decision it describes. With
 * the default propagation, a call from a non-transactional method would open its own transaction, commit
 * the event, and leave the decision uncommitted — announcing a fact the database does not have, which is
 * precisely what the outbox exists to prevent. Failing loudly beats publishing early.
 *
 * <p>A copy of transaction-service's writer rather than a shared component, for the reason given in
 * {@code OutboxEventEntity}: two services have this, and the third one triggers the extraction. It is not
 * a method on {@code DecisionService} on purpose — an event writer that is a method on a service can be
 * skipped by a future code path, whereas one whose absence fails at runtime cannot.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxRepository outbox;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Records an event in the caller's transaction.
     *
     * @param aggregateType {@code "RiskDecision"}, so a consumer can filter on the envelope without
     *     parsing the payload
     * @param aggregateId the payment's id, which is also the partition key
     * @param aggregateVersion the assessment's attempt number, so a consumer can order and deduplicate
     * @param occurredAt the instant of the decision, not the instant of serialisation
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
        log.debug("recorded {} for payment {}", eventType, aggregateId);
    }

    /**
     * Serialises the envelope, failing the caller's transaction if it cannot.
     *
     * <p>A payload that cannot be serialised will never become serialisable, and swallowing the error would
     * leave a decision committed with no announcement — the one thing the outbox cannot be allowed to do.
     * Letting it propagate rolls the decision back, which is honest: nothing happened, and the consumer
     * of the request can retry.
     */
    private String serialise(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload could not be serialised: " + envelope.eventType(), e);
        }
    }

    /** The clock this writer stamps events with, exposed so a relay in the same service agrees. */
    Instant now() {
        return clock.instant();
    }
}
