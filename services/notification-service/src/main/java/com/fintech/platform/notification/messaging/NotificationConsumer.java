package com.fintech.platform.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.notification.domain.NotificationKind;
import com.fintech.platform.notification.messaging.NotificationPayloads.FraudCompletedPayload;
import com.fintech.platform.notification.messaging.NotificationPayloads.SettlementPayload;
import com.fintech.platform.notification.messaging.NotificationPayloads.TransactionPayload;
import com.fintech.platform.notification.service.NotificationService;
import com.fintech.platform.notification.service.NotificationService.FraudNotice;
import com.fintech.platform.notification.service.NotificationService.PaymentNotice;
import com.fintech.platform.notification.service.NotificationService.SettlementNotice;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns consumed facts into messages.
 *
 * <p>One listener per topic, because the topics carry different payloads and a single deserializer
 * would have to guess. The handler for each is the same shape: parse the envelope, refuse an unknown
 * version, claim the event id, and let the service record and send. The claim and the notification
 * are one transaction inside the service, so there is no window in which the event is marked
 * processed and the message is lost.
 *
 * <p><b>A malformed event is not silently dropped.</b> Deserialization failures propagate, so the
 * listener's retry and dead-letter policy applies: after the configured attempts the event lands on
 * {@code dead-letter-events} where somebody will find it. Catching and logging would be the worst
 * outcome available — the money moved and nobody was told, and nothing anywhere says so.
 *
 * <p><b>An approved payment notifies nobody.</b> {@code fraud-analysis-completed} with
 * {@code APPROVE} returns before any write: the payment needs no human action, and a message per
 * approval would be noise. The early return is before the claim, which is safe because the branch
 * writes nothing — a redelivered approval re-parses and returns again, idempotent by construction.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationService notifications;

    private final ObjectMapper json;

    private final Counter consumed;

    private final Counter duplicates;

    private final Counter malformed;

    private final Counter silenced;

    public NotificationConsumer(NotificationService notifications, ObjectMapper json, MeterRegistry meters) {
        this.notifications = notifications;
        this.json = json;
        this.consumed = Counter.builder("notification.events.consumed")
                .description("Events this service turned into a notification, after deduplication")
                .register(meters);
        this.duplicates = Counter.builder("notification.events.duplicates")
                .description("Deliveries of an event whose id has already been claimed")
                .register(meters);
        this.malformed = Counter.builder("notification.events.malformed")
                .description("Deliveries that could not be understood and will be dead-lettered")
                .register(meters);
        this.silenced = Counter.builder("notification.events.silenced")
                .description("Fraud approvals that warranted no message")
                .register(meters);
    }

    /** A payment was authorised. */
    @KafkaListener(
            topics = KafkaTopics.TRANSACTION_AUTHORIZED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onTransactionAuthorized(String record) {
        handlePayment(record, KafkaTopics.TRANSACTION_AUTHORIZED);
    }

    /** A payment was declined. The customer is told, because silence reads as a lost payment. */
    @KafkaListener(
            topics = KafkaTopics.TRANSACTION_DECLINED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onTransactionDeclined(String record) {
        handlePayment(record, KafkaTopics.TRANSACTION_DECLINED);
    }

    /** A payment was captured. */
    @KafkaListener(
            topics = KafkaTopics.TRANSACTION_SETTLED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onTransactionSettled(String record) {
        handlePayment(record, KafkaTopics.TRANSACTION_SETTLED);
    }

    /** A payment was refunded. */
    @KafkaListener(
            topics = KafkaTopics.TRANSACTION_REVERSED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onTransactionReversed(String record) {
        handlePayment(record, KafkaTopics.TRANSACTION_REVERSED);
    }

    /** The fraud engine reached a conclusion worth texting about — unless it approved. */
    @KafkaListener(
            topics = KafkaTopics.FRAUD_ANALYSIS_COMPLETED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onFraudAnalysisCompleted(String record) {
        EventEnvelope<JsonNode> envelope = envelope(record, KafkaTopics.FRAUD_ANALYSIS_COMPLETED);
        FraudCompletedPayload payload = read(envelope, FraudCompletedPayload.class);
        NotificationKind kind = payload.kindOrNull();
        if (kind == null) {
            silenced.increment();
            return;
        }
        payload.requireNotifiable();
        UUID eventId = parseEventId(envelope.eventId());
        if (!notifications.notifyFraud(
                eventId,
                KafkaTopics.FRAUD_ANALYSIS_COMPLETED,
                new FraudNotice(
                        kind,
                        payload.ownerSubjectDigest(),
                        payload.transactionUuid(),
                        payload.amount(),
                        payload.currency(),
                        payload.payeeName()))) {
            duplicates.increment();
            log.info(
                    "Ignoring redelivery of {} from {}; already notified",
                    eventId,
                    KafkaTopics.FRAUD_ANALYSIS_COMPLETED);
            return;
        }
        consumed.increment();
    }

    /**
     * A settlement cycle stopped growing, reconciled, or broke.
     *
     * <p>The kind comes from the payload's own {@code eventType}, not from the topic: one topic
     * carries three different facts, and the event type is what tells them apart. An unfamiliar type
     * is refused rather than guessed at, because guessing here would email an operator the wrong
     * conclusion about a period's money.
     */
    @KafkaListener(
            topics = KafkaTopics.SETTLEMENT_CYCLE_FINALISED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onSettlementCycleFinalised(String record) {
        EventEnvelope<JsonNode> envelope = envelope(record, KafkaTopics.SETTLEMENT_CYCLE_FINALISED);
        SettlementPayload payload = read(envelope, SettlementPayload.class);
        payload.requireReference();
        NotificationKind kind = switch (envelope.eventType()) {
            case "settlement.cycle.closed" -> NotificationKind.SETTLEMENT_CLOSED;
            case "settlement.cycle.reconciled" -> NotificationKind.SETTLEMENT_RECONCILED;
            case "settlement.cycle.broken" -> NotificationKind.SETTLEMENT_BREAK;
            default ->
                throw new IllegalArgumentException(
                        "settlement event type '" + envelope.eventType() + "' is not one this service notifies on");
        };
        UUID eventId = parseEventId(envelope.eventId());
        if (!notifications.notifySettlement(
                eventId,
                KafkaTopics.SETTLEMENT_CYCLE_FINALISED,
                new SettlementNotice(kind, payload.reference(), payload.detailOrNull()))) {
            duplicates.increment();
            log.info(
                    "Ignoring redelivery of {} from {}; already notified",
                    eventId,
                    KafkaTopics.SETTLEMENT_CYCLE_FINALISED);
            return;
        }
        consumed.increment();
    }

    /** A reconciliation difference was recorded against a cycle. */
    @KafkaListener(
            topics = KafkaTopics.SETTLEMENT_BREAK_DETECTED,
            groupId = "${app.notification.consumer-group:notification-service}")
    public void onSettlementBreakDetected(String record) {
        EventEnvelope<JsonNode> envelope = envelope(record, KafkaTopics.SETTLEMENT_BREAK_DETECTED);
        SettlementPayload payload = read(envelope, SettlementPayload.class);
        payload.requireReference();
        UUID eventId = parseEventId(envelope.eventId());
        if (!notifications.notifySettlement(
                eventId,
                KafkaTopics.SETTLEMENT_BREAK_DETECTED,
                new SettlementNotice(NotificationKind.SETTLEMENT_BREAK, payload.reference(), payload.detailOrNull()))) {
            duplicates.increment();
            log.info(
                    "Ignoring redelivery of {} from {}; already notified",
                    eventId,
                    KafkaTopics.SETTLEMENT_BREAK_DETECTED);
            return;
        }
        consumed.increment();
    }

    private void handlePayment(String record, String topic) {
        EventEnvelope<JsonNode> envelope = envelope(record, topic);
        TransactionPayload payload = read(envelope, TransactionPayload.class);
        payload.requireNotifiable();
        UUID eventId = parseEventId(envelope.eventId());
        if (!notifications.notifyPayment(
                eventId,
                topic,
                new PaymentNotice(
                        payload.kindFor(topic),
                        payload.ownerSubjectDigest(),
                        payload.transactionUuid(),
                        payload.amount(),
                        payload.currency(),
                        payload.payeeName()))) {
            duplicates.increment();
            log.info("Ignoring redelivery of {} from {}; already notified", eventId, topic);
            return;
        }
        consumed.increment();
    }

    private EventEnvelope<JsonNode> envelope(String record, String topic) {
        EventEnvelope<JsonNode> envelope;
        try {
            // Typed, because EventEnvelope<T> erases T. Reading it as the raw class leaves nothing for
            // Jackson to bind the payload to and it arrives as a LinkedHashMap, which the JsonNode cast
            // below would fail on for the first event this service ever consumed.
            envelope = json.readValue(record, new TypeReference<EventEnvelope<JsonNode>>() {});
        } catch (JsonProcessingException e) {
            malformed.increment();
            throw new IllegalArgumentException("not a readable event envelope from " + topic, e);
        }
        if (!envelope.isSupportedVersion()) {
            // Refusing an unknown version is what the version number is for. Parsing a v2 payload into v1
            // fields produces a message about a payment whose new field — the one that would have changed
            // the message — was dropped on the way in.
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
            // Envelopes built by this platform always carry a UUID. One that does not is a foreign producer
            // or a corrupted payload, and a deduplication key derived from it would be wrong in a way that
            // silently disables deduplication for that event — which is to say, silently tells the customer
            // twice.
            throw new IllegalArgumentException("eventId is not a UUID: " + eventId, e);
        }
    }
}
