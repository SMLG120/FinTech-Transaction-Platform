package com.fintech.platform.fraud.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.fraud.service.FraudEvents;
import com.fintech.platform.fraud.service.FraudScoringService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code transaction-created} and scores the payment.
 *
 * <p><b>Deduplication is claimed with an insert, not a lookup.</b> The claim is an
 * {@code ON CONFLICT DO NOTHING} and the boolean it returns is the only thing that decides whether
 * this consumer acts. A read-then-write would have a window between the two statements in which a second
 * consumer thread sees no row and processes the same event again, and the symptom of that is a duplicate
 * score and a duplicate alert rather than an error — the kind of bug that is found by a customer who was
 * told twice.
 *
 * <p><b>The claim and the decision are one transaction.</b> The claim is written by the scoring service
 * inside the transaction that persists the decision, so there is no window in which the event is marked
 * processed and the decision is lost. The reverse order — mark first, then fail — would silently drop a
 * payment; fail first, then mark — would double-process.
 *
 * <p><b>A malformed event is not silently dropped.</b> Deserialization failures propagate, so the
 * listener's retry and dead-letter configuration applies: after the configured attempts the event lands
 * on {@code dead-letter-events} where somebody will find it. Catching and logging would be the worst
 * outcome available — the payment is never scored and nobody is told.
 *
 * <p><b>The scoring itself is allowed to fail.</b> If the database or the rules are unavailable, the
 * exception propagates and the event is retried. What is <em>not</em> allowed to fail the payment is the
 * fraud result: the payment was already accepted, and this consumer's job is to record what was noticed,
 * not to decide whether money moves.
 */
@Component
public class FraudConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudConsumer.class);

    private static final String TRANSACTION_CREATED = "transaction-created";

    private static final String ANALYSIS_REQUESTED = "fraud-analysis-requested";

    private final FraudScoringService scoring;

    private final ObjectMapper json;

    private final Counter scored;

    private final Counter duplicates;

    private final Counter malformed;

    public FraudConsumer(FraudScoringService scoring, ObjectMapper json, MeterRegistry meters) {
        this.scoring = scoring;
        this.json = json;
        this.scored = Counter.builder("fraud.payments.scored")
                .description("Payments this service has scored, after deduplication")
                .register(meters);
        this.duplicates = Counter.builder("fraud.events.duplicates")
                .description("Deliveries of an event whose id has already been claimed")
                .register(meters);
        this.malformed = Counter.builder("fraud.events.malformed")
                .description("Deliveries that could not be understood and will be dead-lettered")
                .register(meters);
    }

    /**
     * Scores one payment.
     *
     * @param record the raw envelope; deserialized here so a shape this service does not understand fails
     *     loudly enough for the dead-letter policy to see it
     */
    @KafkaListener(topics = "transaction-created", groupId = "${app.fraud.consumer-group:fraud-service}")
    public void onTransactionCreated(String record) {
        handle(record, TRANSACTION_CREATED);
    }

    /**
     * Re-scores on request, from the analyst API and from anything else that asks.
     *
     * <p>Separate listener and separate group member rather than one listener on two topics, because the
     * two carry different payloads and a single deserializer would have to guess. The re-score is
     * idempotent through the same {@code processed_events} claim, so an analyst clicking twice produces two
     * events and one of them is discarded.
     */
    @KafkaListener(topics = "fraud-analysis-requested", groupId = "${app.fraud.consumer-group:fraud-service}")
    public void onAnalysisRequested(String record) {
        handle(record, ANALYSIS_REQUESTED);
    }

    /**
     * The shared body, because the two topics differ only in what they carry and in what the handler does
     * with it.
     */
    private void handle(String record, String topic) {
        EventEnvelope<JsonNode> envelope;
        try {
            // Typed, and the difference is not cosmetic. EventEnvelope<T> erases T, so reading it as the
            // raw EventEnvelope.class leaves nothing for Jackson to bind the payload to and it arrives as
            // a LinkedHashMap — the JsonNode this method then casts it to would fail on the first event
            // the service ever consumed. The TypeReference carries the T through to the deserializer.
            envelope = json.readValue(record, new TypeReference<EventEnvelope<JsonNode>>() {});
        } catch (JsonProcessingException e) {
            malformed.increment();
            throw new IllegalArgumentException("not a readable event envelope from " + topic, e);
        }
        if (!envelope.isSupportedVersion()) {
            // Refusing an unknown version is what the version number is for. Parsing a v2 payload into v1
            // fields produces a payment that looks legitimate because the new field that would have
            // flagged it was dropped on the way in.
            malformed.increment();
            throw new IllegalArgumentException(
                    "unsupported event version " + envelope.eventVersion() + " for " + envelope.eventType());
        }
        UUID eventId = parseEventId(envelope.eventId());
        if (!scoring.claimAndApply(eventId, topic, () -> apply(envelope, topic))) {
            duplicates.increment();
            log.info("Ignoring redelivery of {} from {}; already processed", eventId, topic);
            return;
        }
        scored.increment();
    }

    private void apply(EventEnvelope<JsonNode> envelope, String topic) {
        if (TRANSACTION_CREATED.equals(topic)) {
            scoring.scoreTransactionCreated(read(envelope, FraudEvents.TransactionCreatedPayload.class));
        } else {
            scoring.rescoreRequested(read(envelope, FraudEvents.RescoreRequestPayload.class));
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
            // Envelopes are built by this platform and always carry a UUID. One that does not is either a
            // foreign producer or a corrupted payload, and a deduplication key derived from it would be
            // wrong in a way that silently disables deduplication for that event.
            throw new IllegalArgumentException("eventId is not a UUID: " + eventId, e);
        }
    }
}
