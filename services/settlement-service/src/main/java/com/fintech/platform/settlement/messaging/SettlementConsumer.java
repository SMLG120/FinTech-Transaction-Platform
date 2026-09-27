package com.fintech.platform.settlement.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.settlement.domain.SettlementLineKind;
import com.fintech.platform.settlement.service.SettlementService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the two facts that move money after a payment is authorised: {@code transaction-settled} and
 * {@code transaction-reversed}.
 *
 * <p><b>Deduplication is claimed with an insert, not a lookup.</b> See
 * {@link SettlementService#claimAndApply} for why, and for what a duplicate costs here: a statement that
 * counts one payment twice and will not reconcile against anything.
 *
 * <p><b>Both topics share one handler</b>, because the payload is the same for both and the only
 * difference is the direction the money moved, which the payload's own {@code status} says. Two listeners
 * with two deserializers would be two places to keep a payload shape in sync, for one shape.
 *
 * <p><b>A malformed event is not silently dropped.</b> Deserialization failures propagate so the listener's
 * retry and dead-letter policy applies, and after the configured attempts the event lands on
 * {@code dead-letter-events} where somebody will find it. Catching and logging would be the worst outcome
 * available: the money moves in the ledger and never appears on a statement, and nothing anywhere says so.
 *
 * <p><b>The apply is allowed to fail.</b> If the database is unavailable the exception propagates and the
 * event is retried, which is the correct behaviour for a statement that must eventually balance. What is
 * not this consumer's job is deciding whether a payment succeeds: that has already happened, and this
 * service's job is to say how the period's money adds up.
 */
@Component
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);

    private final SettlementService settlement;

    private final ObjectMapper json;

    private final Counter applied;

    private final Counter duplicates;

    private final Counter malformed;

    public SettlementConsumer(SettlementService settlement, ObjectMapper json, MeterRegistry meters) {
        this.settlement = settlement;
        this.json = json;
        this.applied = Counter.builder("settlement.movements.applied")
                .description("Transaction movements added to a settlement cycle, after deduplication")
                .register(meters);
        this.duplicates = Counter.builder("settlement.events.duplicates")
                .description("Deliveries of an event whose id has already been claimed")
                .register(meters);
        this.malformed = Counter.builder("settlement.events.malformed")
                .description("Deliveries that could not be understood and will be dead-lettered")
                .register(meters);
    }

    /** A payment was captured. */
    @KafkaListener(
            topics = KafkaTopics.TRANSACTION_SETTLED,
            groupId = "${app.settlement.consumer-group:settlement-service}")
    public void onTransactionSettled(String record) {
        handle(record, KafkaTopics.TRANSACTION_SETTLED);
    }

    /** A payment was refunded, whether it had been captured or only held. */
    @KafkaListener(
            topics = KafkaTopics.TRANSACTION_REVERSED,
            groupId = "${app.settlement.consumer-group:settlement-service}")
    public void onTransactionReversed(String record) {
        handle(record, KafkaTopics.TRANSACTION_REVERSED);
    }

    private void handle(String record, String topic) {
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
            // fields produces a statement that looks complete because the field that would have flagged it
            // was dropped on the way in.
            malformed.increment();
            throw new IllegalArgumentException(
                    "unsupported event version " + envelope.eventVersion() + " for " + envelope.eventType());
        }
        UUID eventId = parseEventId(envelope.eventId());
        if (!settlement.claimAndApply(eventId, topic, () -> apply(envelope, topic))) {
            duplicates.increment();
            log.info("Ignoring redelivery of {} from {}; already applied", eventId, topic);
            return;
        }
        applied.increment();
    }

    private void apply(EventEnvelope<JsonNode> envelope, String topic) {
        TransactionMovementEvent movement = read(envelope, TransactionMovementEvent.class);
        // The kind comes from the payload's own status, and is validated there. A movement that is neither
        // a capture nor a refund throws rather than defaulting, because guessing here would put money in a
        // cycle on the strength of a status nobody has taught this service to read.
        if (movement.kind() == SettlementLineKind.CAPTURE) {
            settlement.applyCapture(movement);
        } else {
            settlement.applyReversal(movement);
        }
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
            // silently disables deduplication for that event — which is to say, silently double-counts it.
            throw new IllegalArgumentException("eventId is not a UUID: " + eventId, e);
        }
    }
}
