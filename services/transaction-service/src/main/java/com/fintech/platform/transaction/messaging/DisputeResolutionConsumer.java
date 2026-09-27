package com.fintech.platform.transaction.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.transaction.messaging.DisputePayloads.StatusChangedPayload;
import com.fintech.platform.transaction.service.TransactionService;
import com.fintech.platform.transaction.service.TransactionService.RefundOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Refunds payments whose disputes were resolved in the customer's favour.
 *
 * <p>This service's first consumer, and the direction of the dependency is the point:
 * dispute-service announces what was decided, and the ledger — which owns the money — decides what
 * that means for the payment. The alternative, dispute-service calling back into a reversal
 * endpoint, would need an identity entitled to move somebody else's money, which is exactly the
 * confused deputy the forwarded identity is meant to prevent. Here the only input is the payment's
 * own stored owner digest, which never leaves this service.
 *
 * <p><b>A rejection moves nothing.</b> It is read, validated and ignored — the early return writes
 * nothing, so a redelivered rejection re-parses and returns again, idempotent by construction. The
 * same shape as notification-service silencing fraud approvals: no human action, no message, no
 * row.
 *
 * <p><b>A malformed event is not silently dropped.</b> Deserialization failures propagate to the
 * dead-letter policy: a resolution nobody can read is money whose fate is unknown, and unknown is
 * what the dead-letter topic is for.
 */
@Component
public class DisputeResolutionConsumer {

    private static final Logger log = LoggerFactory.getLogger(DisputeResolutionConsumer.class);

    private final TransactionService transactions;

    private final ObjectMapper json;

    private final Counter refunded;

    private final Counter converged;

    private final Counter duplicates;

    private final Counter ignored;

    private final Counter malformed;

    public DisputeResolutionConsumer(TransactionService transactions, ObjectMapper json, MeterRegistry meters) {
        this.transactions = transactions;
        this.json = json;
        this.refunded = Counter.builder("transaction.dispute.refunded")
                .description("Payments refunded on dispute resolutions")
                .register(meters);
        this.converged = Counter.builder("transaction.dispute.converged")
                .description("Resolutions for payments already refunded: no money moved, outcome already correct")
                .register(meters);
        this.duplicates = Counter.builder("transaction.dispute.duplicates")
                .description("Redelivered resolutions whose id has already been claimed")
                .register(meters);
        this.ignored = Counter.builder("transaction.dispute.ignored")
                .description("Dispute rejections: read, validated, and moving nothing")
                .register(meters);
        this.malformed = Counter.builder("transaction.dispute.malformed")
                .description("Deliveries that could not be understood and will be dead-lettered")
                .register(meters);
    }

    /** A dispute was decided. Only a refund moves money here. */
    @KafkaListener(
            topics = KafkaTopics.DISPUTE_STATUS_CHANGED,
            groupId = "${app.transaction.consumer-group:transaction-service}")
    public void onDisputeStatusChanged(String record) {
        EventEnvelope<JsonNode> envelope = envelope(record, KafkaTopics.DISPUTE_STATUS_CHANGED);
        StatusChangedPayload payload = read(envelope, StatusChangedPayload.class);
        payload.requireDecided();
        if (!payload.isRefund()) {
            ignored.increment();
            return;
        }
        UUID eventId = parseEventId(envelope.eventId());
        UUID transactionId = payload.transactionUuid();
        UUID disputeId = payload.disputeUuid();
        RefundOutcome outcome =
                transactions.refundForDisputeResolution(eventId, KafkaTopics.DISPUTE_STATUS_CHANGED, transactionId);
        switch (outcome) {
            case REFUNDED -> {
                refunded.increment();
                log.info("Refunded payment {} on dispute {}", transactionId, disputeId);
            }
            case ALREADY_REFUNDED -> {
                converged.increment();
                log.info("Payment {} for dispute {} was already refunded; converging", transactionId, disputeId);
            }
            case DUPLICATE -> {
                duplicates.increment();
                log.info(
                        "Ignoring redelivery of {} from {}; already applied",
                        eventId,
                        KafkaTopics.DISPUTE_STATUS_CHANGED);
            }
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
}
