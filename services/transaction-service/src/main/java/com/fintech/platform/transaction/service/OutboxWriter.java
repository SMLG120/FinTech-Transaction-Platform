package com.fintech.platform.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.transaction.domain.OutboxEvent;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.persistence.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events into the outbox, in the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} is the important annotation. It means this method refuses to run
 * unless a transaction already exists, so an event cannot be written outside the transaction that made
 * the state change it describes. With the default propagation a call from a non-transactional method
 * would open its own transaction, commit the event, and leave the payment uncommitted — publishing a
 * fact that the database does not have yet, which is the exact failure the outbox exists to prevent.
 * Failing loudly is better than publishing early.
 *
 * <p>Not a service method on {@code TransactionService} on purpose: an event writer that is a method on
 * the service can be skipped by a future path, whereas one whose absence fails at runtime cannot.
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
     * Records a lifecycle event for a payment.
     *
     * <p>{@code occurredAt} is passed in rather than read from the clock here, so that the event carries
     * the same instant as the state change it describes. Reading {@code Instant.now()} at this point
     * would put a few milliseconds later on every event than on the payment, and a gap that small is
     * exactly the kind of drift that makes "what happened first?" unanswerable from the topic alone.
     *
     * @param aggregateVersion the version the caller read back from the row, and the same number it put
     *     in the payload. Taken as an argument so that the row and the body cannot disagree; it is not read
     *     from {@code transaction} here, because that field does not reliably match the row.
     * @param topic one of the {@code KafkaTopics.TRANSACTION_*} values
     * @param eventType the event type inside the envelope
     * @param payload the event body, serialised with amounts as decimal strings
     * @param occurredAt the instant of the state change
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransactionEvent(
            Transaction transaction,
            String topic,
            String eventType,
            long aggregateVersion,
            Object payload,
            Instant occurredAt) {
        String envelopeJson = serialise(
                EventEnvelope.ofAt(eventType, "Transaction", transaction.id().toString(), payload, occurredAt));

        // Keyed by the transaction id, so every event for one payment lands on one partition and a
        // consumer reads them in the order they happened. A key of the topic name or the customer would
        // spread a payment's events across partitions and hand the ordering problem to every consumer.
        outbox.save(
                OutboxEvent.forTransaction(transaction, aggregateVersion, topic, eventType, envelopeJson, occurredAt));

        log.debug("recorded {} for transaction {} v{}", eventType, transaction.id(), aggregateVersion);
    }

    /**
     * Records an event for something that is not a payment, such as a funding movement.
     *
     * <p>Takes the aggregate identity rather than a {@code Transaction}, because a deposit is a movement
     * of value with no transaction behind it. The transaction's id is nullable in the schema for the same
     * reason.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String topic,
            String eventType,
            Object payload) {
        Instant now = clock.instant();
        String envelopeJson = serialise(EventEnvelope.ofAt(eventType, aggregateType, aggregateId, payload, now));
        outbox.save(OutboxEvent.record(
                java.util.UUID.randomUUID(),
                aggregateType,
                java.util.UUID.fromString(aggregateId),
                aggregateVersion,
                topic,
                aggregateId,
                eventType,
                envelopeJson,
                now));
    }

    /**
     * Serialises the envelope, failing the caller's transaction if it cannot.
     *
     * <p>A {@code JsonProcessingException} here is a programming error — a payload that cannot be
     * serialised will never become serialisable, and swallowing it would leave a state change committed
     * with no event, which is the one thing the outbox cannot be allowed to do. Letting it propagate
     * rolls the payment back, which is the honest outcome: nothing happened, and the caller can retry.
     */
    private String serialise(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload could not be serialised: " + envelope.eventType(), e);
        }
    }
}
