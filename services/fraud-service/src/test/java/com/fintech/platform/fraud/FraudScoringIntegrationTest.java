package com.fintech.platform.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.Money;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.messaging.FraudConsumer;
import com.fintech.platform.fraud.persistence.ProcessedEventRepository;
import com.fintech.platform.fraud.persistence.RiskDecisionRepository;
import com.fintech.platform.fraud.service.FraudEvents;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The scoring path end to end, against a real database and a stubbed broker and cache.
 *
 * <p>What this is really for is the two properties that only exist at the seams. The first is the
 * migration: this context starts with Flyway applying {@code V1__create_fraud.sql} and Hibernate
 * validating every entity against the result, so a column that exists in Java and not in SQL fails here
 * rather than in a deployment. The second is deduplication: the claim is an {@code ON CONFLICT}, and
 * whether a redelivery produces one score or two is a question about the database, not about Java.
 *
 * <p>Kafka and Redis are stubbed, and the limit is worth stating. A real broker would prove the
 * serializer round-trips; here the test hands the consumer the exact bytes a producer would send, which
 * tests the parsing and the transaction boundary and leaves the wire format to the outbox relay's own
 * test. Redis is stubbed to return "unavailable" by default, so the velocity rule declines and the
 * decision records {@code velocityAvailable=false} — the degraded path, which is the one that must never
 * be mistaken for a count of zero.
 */
@Testcontainers
@SpringBootTest
class FraudScoringIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_fraud_scoring");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "platform.security.internal-identity.signing-key",
                () -> "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801");
        registry.add(
                "platform.security.subject-digest.key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
        // The relay and the retention jobs are the subject of other tests and would publish rows these
        // assertions are about to count.
        registry.add("app.outbox.relay-enabled", () -> "false");
        registry.add("app.background-jobs-enabled", () -> "false");
        // This is the first service in the platform with a @KafkaListener, and a listener container that
        // starts with no broker does not fail the context — it retries in the background, logging a
        // connection warning for every attempt until the context closes. That turns a red assertion into
        // a screenful of amber noise, which is the same way it would hide a real failure later. The tests
        // call the listener method directly, which is the same entry point the container would use.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private FraudConsumer consumer;

    @Autowired
    private RiskDecisionRepository decisions;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    /** The broker. Stubbed, so the test needs no Kafka and can inspect what would have been sent. */
    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    /**
     * The velocity counter's Redis.
     *
     * <p>Stubbed to fail. That is not laziness: it is the degraded path, and running the whole suite in
     * it means the default decision in every other test on this class is a score computed without a
     * velocity check, with that recorded in the facts. A test suite that only ever sees the happy path
     * cannot tell the difference between "velocity said 1" and "velocity was never asked".
     */
    @MockitoBean
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE fraud_alert_events, fraud_alerts, risk_decisions, fraud_observations, "
                + "processed_events, outbox_events CASCADE");
        reset(kafka, redis);
        // The Redis mock is deliberately left unstubbed, so redis.execute(...) returns null. That is not
        // an accident to be tidied away: VelocityCounter treats a null return as "the counter is
        // unavailable" and refuses to read it as zero. Stubbing the mock to throw a different exception
        // would exercise the same catch block through a longer path and prove nothing extra, whereas this
        // documents that the null branch is the one real Redis failure modes reach.
    }

    // ------------------------------------------------------------------------------- the migration

    @Test
    @DisplayName("the migration creates every table the entities need")
    void schemaMatchesTheEntities() {
        // The context starting at all is most of this test: spring.jpa.hibernate.ddl-auto is validate, so
        // a column that exists in an entity and not in V1 would have failed startup. This asserts the
        // shape explicitly, because a failure inside Hibernate's validation is a much worse error message
        // than a list of what is actually there.
        assertThat(tableNames())
                .contains(
                        "risk_decisions",
                        "fraud_observations",
                        "fraud_alerts",
                        "fraud_alert_events",
                        "processed_events",
                        "outbox_events");
    }

    @Test
    @DisplayName("the outbox is this service's own table, not transaction-service's")
    void outboxIsLocalToFraudService() {
        // Two outbox tables in two databases, both named outbox_events, each with its own relay. Sharing
        // one would mean a transaction-service relay publishing a fraud event, and a failure in one
        // service stalling the other's announcements.
        assertThat(tableNames()).contains("outbox_events");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'outbox_events'",
                        Integer.class))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- scoring

    @Test
    @DisplayName("a transaction-created event produces a decision that explains its own score")
    void scoresAPayment() {
        UUID transactionId = UUID.randomUUID();

        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));

        var decision = decisions.findByTransactionId(transactionId).orElseThrow();
        assertThat(decision.amount()).isEqualTo(Money.parse("6000.00", Currency.getInstance("GBP")));
        assertThat(decision.attempt()).isEqualTo(1);
        // R001 large amount 35 + R003 new device 25 + R007 new merchant 15 = 75, over the 51 review line
        // and one point under the 76 decline line. 75 is the score because that is what those three rules
        // are worth, not because 75 was chosen — and it is the closest this payment comes to a decline.
        assertThat(decision.reasons())
                .as("R001 fired and the row explains itself")
                .contains("R001", "R003", "R007")
                .doesNotContain("R002");
        assertThat(decision.score()).isEqualTo(75);
        assertThat(decision.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(decision.band()).isEqualTo(RiskBand.HIGH);
    }

    @Test
    @DisplayName("the velocity fact records that the counter was never reached")
    void recordsTheDegradedVelocity() {
        UUID transactionId = UUID.randomUUID();

        consumer.onTransactionCreated(envelopeFor(transactionId, "10.00", "GBP"));

        // This is the assertion that distinguishes "the counter said one" from "the counter was not
        // asked". Without it a Redis outage and a quiet minute look identical in the stored decision.
        // The value is a quoted string because facts is a Map<String,String> — see RiskEngine.facts.
        assertThat(decisions.findByTransactionId(transactionId).orElseThrow().facts())
                .contains("\"velocityAvailable\":\"false\"");
    }

    @Test
    @DisplayName("a payment is declined on a large amount and a velocity the counter confirms")
    void declinesAPayment() {
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(9);

        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));

        var decision = decisions.findByTransactionId(transactionId).orElseThrow();
        // R001 large amount 35 + R002 velocity 35 + R003 new device 25 + R007 new merchant 15 = 110, which
        // the cap holds at 100. R007 fires because the observation store is empty, so this payee is a new
        // one to the service — worth stating, because a fraud rule that keys on first-sight means the
        // clearest new-payment signal is also the one an empty database manufactures.
        assertThat(decision.reasons())
                .as("all four rules that fire are named on the row")
                .contains("R001", "R002", "R003", "R007");
        assertThat(decision.score()).as("110 raw points, capped at 100").isEqualTo(100);
        assertThat(decision.decision()).isEqualTo(FraudDecision.DECLINE);
        assertThat(decision.band()).isEqualTo(RiskBand.CRITICAL);
        assertThat(decision.facts()).contains("\"velocityAvailable\":\"true\"");
    }

    @Test
    @DisplayName("an unavailable counter lowers the score rather than failing the payment")
    void anUnavailableCounterUnderScores() {
        // The same payment, once with the counter answering and once with it not answering. This is the
        // cost of the fail-open choice made in ADR-0008, written as a test so that the choice is visible
        // in the diff when somebody revisits it: with Redis down, this payment is approved.
        UUID withCounter = UUID.randomUUID();
        whenVelocityIs(9);
        consumer.onTransactionCreated(envelopeFor(withCounter, "6000.00", "GBP"));

        UUID withoutCounter = UUID.randomUUID();
        reset(redis);
        consumer.onTransactionCreated(envelopeFor(withoutCounter, "6000.00", "GBP"));

        var declined = decisions.findByTransactionId(withCounter).orElseThrow();
        var approved = decisions.findByTransactionId(withoutCounter).orElseThrow();
        assertThat(declined.decision()).isEqualTo(FraudDecision.DECLINE);
        assertThat(approved.decision())
                .as("a cache outage must not refuse payments; it must lose the signal")
                .isEqualTo(FraudDecision.REVIEW);
        assertThat(approved.reasons())
                .as("R002 is the rule the outage cost, and only R002")
                .doesNotContain("R002")
                .contains("R001");
        assertThat(approved.score()).isLessThan(declined.score());
        assertThat(declined.facts()).contains("\"velocityAvailable\":\"true\"");
        assertThat(approved.facts()).contains("\"velocityAvailable\":\"false\"");
    }

    @Test
    @DisplayName("a redelivery of the same event scores nothing a second time")
    void aRedeliveryIsDiscarded() {
        UUID transactionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String record = envelopeWithId(eventId, transactionId, "6000.00", "GBP");

        consumer.onTransactionCreated(record);
        consumer.onTransactionCreated(record);
        consumer.onTransactionCreated(record);

        assertThat(decisions.findByTransactionId(transactionId))
                .as("at-least-once delivery, exactly-once processing: one row however many deliveries")
                .isPresent();
        assertThat(processedEvents.count()).isEqualTo(1);
        assertThat(decisions.count()).isEqualTo(1);
        assertThat(attemptsFor(transactionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a second publish of one payment under a new event id does not score it twice")
    void aDuplicatePublishDoesNotDoubleScore() {
        // At-least-once delivery and idempotency are different layers. A redelivery carries the same
        // eventId and is stopped by the processed_events claim. This is the other case: a producer that
        // genuinely published twice, so two different eventIds naming one payment. The claim cannot catch
        // that, and it would double the alert. DecisionService.record is the second line of defence, and
        // the payment is not rescored here — a fresh transaction-created event is not a request to look
        // again, it is a duplicate announcement.
        UUID transactionId = UUID.randomUUID();

        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));
        consumer.onTransactionCreated(envelopeFor(transactionId, "9000.00", "GBP"));

        var decision = decisions.findByTransactionId(transactionId).orElseThrow();
        assertThat(decisions.count()).isEqualTo(1);
        assertThat(decision.attempt())
                .as("a duplicate publish is not a re-score")
                .isEqualTo(1);
        assertThat(decision.amount())
                .as("the first assessment stands")
                .isEqualTo(Money.parse("6000.00", Currency.getInstance("GBP")));
        assertThat(processedEvents.count())
                .as("both event ids are claimed, so neither is retried as new")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a re-score request replaces the score and raises the attempt count")
    void aRescoreRequestReplacesTheScore() {
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(9);
        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));
        assertThat(decisions.findByTransactionId(transactionId).orElseThrow().decision())
                .isEqualTo(FraudDecision.DECLINE);

        // The counter now answers 0: a customer who was not actually moving fast. This is what a re-score
        // is for — the features are re-collected, not the old numbers replayed.
        reset(redis);
        whenVelocityIs(0);
        consumer.onAnalysisRequested(rescoreEnvelopeFor(transactionId));

        var decision = decisions.findByTransactionId(transactionId).orElseThrow();
        assertThat(decision.attempt()).isEqualTo(2);
        assertThat(decision.decision())
                .as("the row holds the new assessment, not the original")
                .isEqualTo(FraudDecision.REVIEW);
        assertThat(decision.reasons())
                .as("R002 is gone because the counter now says the customer is not moving fast")
                .doesNotContain("R002");
    }

    @Test
    @DisplayName("a re-score request for an unscored payment is refused")
    void refusesARescoreOfNothing() {
        // A re-score re-collects features from the stored decision. With no decision there is nothing to
        // collect from, and inventing a decision with no features would put a score in the database that
        // no rule had a chance to contribute to.
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.onAnalysisRequested(rescoreEnvelopeFor(unknown)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no decision exists");
        assertThat(decisions.count()).isZero();
    }

    @Test
    @DisplayName("the same re-score request twice is discarded as a redelivery")
    void aRepeatedRescoreRequestIsDiscarded() {
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(0);
        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));
        String request = rescoreEnvelopeWithId(UUID.randomUUID(), transactionId);

        consumer.onAnalysisRequested(request);
        consumer.onAnalysisRequested(request);

        assertThat(decisions.findByTransactionId(transactionId).orElseThrow().attempt())
                .as("an analyst clicking twice does not look twice")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a payment the rules decline opens exactly one alert")
    void aDeclinedPaymentOpensOneAlert() {
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(9);

        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));

        Integer alerts = jdbc.queryForObject(
                "SELECT count(*) FROM fraud_alerts WHERE transaction_id = ?", Integer.class, transactionId);
        assertThat(alerts).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment nothing fires on opens no alert")
    void aCleanPaymentOpensNoAlert() {
        UUID transactionId = UUID.randomUUID();

        consumer.onTransactionCreated(envelopeFor(transactionId, "10.00", "GBP"));

        Integer alerts = jdbc.queryForObject(
                "SELECT count(*) FROM fraud_alerts WHERE transaction_id = ?", Integer.class, transactionId);
        assertThat(alerts).isZero();
    }

    // ------------------------------------------------------------------------------- refusal

    @Test
    @DisplayName("an event with no transaction id is refused rather than scored as something")
    void refusesAnEventWithNoId() {
        // A decision keyed on a null transaction id is not a decision about a payment, and a row like that
        // would be un-findable by every legitimate route into the data.
        var payload = new FraudEvents.TransactionCreatedPayload(
                null, FraudFixtures.OWNER_DIGEST, "10.00", "GBP", "Shop", "ref", null, FraudFixtures.NOW.toString());
        assertThatThrownBy(() -> consumer.onTransactionCreated(envelopeOf(UUID.randomUUID(), payload)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(decisions.count()).isZero();
    }

    @Test
    @DisplayName("an event with no occurredAt is refused, not dated as if it had just arrived")
    void refusesAnEventWithNoTimestamp() {
        // Every one of the three time-window rules is measured against this. Defaulting it to "now" would
        // place a two-day-old payment inside the current velocity window, which is the one thing a fraud
        // engine must not invent.
        var payload = new FraudEvents.TransactionCreatedPayload(
                UUID.randomUUID().toString(), FraudFixtures.OWNER_DIGEST, "10.00", "GBP", "Shop", "ref", null, null);
        assertThatThrownBy(() -> consumer.onTransactionCreated(envelopeOf(UUID.randomUUID(), payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
        assertThat(decisions.count()).isZero();
    }

    @Test
    @DisplayName("an unsupported event version is refused before its payload is read")
    void refusesAnUnknownVersion() {
        // A v2 payload parsed into v1 fields produces a payment that looks legitimate because the new
        // field that would have flagged it was dropped on the way in. The version number is the defence.
        var payload = new FraudEvents.TransactionCreatedPayload(
                UUID.randomUUID().toString(),
                FraudFixtures.OWNER_DIGEST,
                "10.00",
                "GBP",
                "Shop",
                "ref",
                null,
                FraudFixtures.NOW.toString());
        EventEnvelope<FraudEvents.TransactionCreatedPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "transaction.created",
                2,
                FraudFixtures.NOW,
                "test-correlation",
                "Transaction",
                UUID.randomUUID().toString(),
                payload,
                Map.of());
        assertThatThrownBy(() -> consumer.onTransactionCreated(write(envelope)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("a card token in the payload is ignored rather than stored")
    void ignoresACardToken() {
        // The payload record has no cardToken field and ignores unknown properties, so a producer that
        // emitted one anyway has it dropped. The assertion is that the decision row holds no column
        // carrying it — the point being that a card credential never reaches this database.
        UUID transactionId = UUID.randomUUID();
        String record = """
                {"eventId":"%s","eventType":"transaction.created","eventVersion":1,
                 "occurredAt":"%s","correlationId":"test","aggregateType":"Transaction",
                 "aggregateId":"%s","metadata":{},
                 "payload":{"transactionId":"%s","ownerSubjectDigest":"%s","amount":"6000.00",
                 "currency":"GBP","payeeName":"Shop","payeeReference":"ref","occurredAt":"%s",
                 "cardToken":"tok_live_should_never_arrive"}}
                """.formatted(
                        UUID.randomUUID(),
                        FraudFixtures.NOW,
                        transactionId,
                        transactionId,
                        FraudFixtures.OWNER_DIGEST,
                        FraudFixtures.NOW)
                .replace("\n", "");

        consumer.onTransactionCreated(record);

        assertThat(decisions.findByTransactionId(transactionId)).isPresent();
        assertThat(columnNamesOf("risk_decisions"))
                .as("no column could hold a card token even if the payload carried one")
                .noneMatch(name -> name.contains("card_token"));
    }

    // ------------------------------------------------------------------------------- the alert timeline

    @Test
    @DisplayName("a re-score is recorded on the alert's timeline with who asked and why")
    void aRescoreIsRecordedOnTheAlertTimeline() {
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(9);
        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));

        UUID alertId = singleAlertIdFor(transactionId);
        assertThat(timelineFor(alertId))
                .as("the alert opened with a RAISED entry and nothing else")
                .containsExactly("RAISED");

        whenVelocityIs(0);
        consumer.onAnalysisRequested(
                rescoreEnvelopeFor(transactionId, FraudFixtures.OWNER_DIGEST, "customer queried it"));

        assertThat(timelineFor(alertId))
                .as("RESCORED is in the enum and the CHECK constraint; this is what produces it")
                .containsExactly("RAISED", "RESCORED");
        String entry = jdbc.queryForObject(
                "SELECT note FROM fraud_alert_events WHERE alert_id = ? AND action = 'RESCORED'",
                String.class,
                alertId);
        assertThat(entry)
                .as("a re-score that records nothing about who asked is an override with no trail")
                .contains(FraudFixtures.OWNER_DIGEST)
                .contains("customer queried it");
    }

    @Test
    @DisplayName("a re-score that comes back clean does not close the alert")
    void aRescoreDoesNotResolveTheAlert() {
        // The alert is a human's queue. A re-score is a number produced without any of the context the
        // person holding it has, and a service that closed their alert would take work away from them.
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(9);
        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));
        UUID alertId = singleAlertIdFor(transactionId);

        whenVelocityIs(0);
        consumer.onAnalysisRequested(rescoreEnvelopeFor(transactionId, FraudFixtures.OWNER_DIGEST, "looked again"));

        assertThat(alertState(alertId))
                .as("the alert stays open; the analyst closes it")
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a re-score by a job with no subject to attribute is recorded honestly")
    void aRescoreWithNoRequesterIsStillRecorded() {
        // A scheduled job sends the same event with no actor. Requiring one would mean inventing a fake
        // subject to satisfy a NOT NULL, and a fabricated actor in an audit column is worse than a null.
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(9);
        consumer.onTransactionCreated(envelopeFor(transactionId, "6000.00", "GBP"));
        UUID alertId = singleAlertIdFor(transactionId);

        whenVelocityIs(0);
        consumer.onAnalysisRequested(rescoreEnvelopeFor(transactionId, null, null));

        String entry = jdbc.queryForObject(
                "SELECT note FROM fraud_alert_events WHERE alert_id = ? AND action = 'RESCORED'",
                String.class,
                alertId);
        assertThat(entry)
                .as("with neither an actor nor a reason, the note says so rather than implying an analyst")
                .isNotNull()
                .contains("no requester recorded");
        assertThat(jdbc.queryForObject(
                        "SELECT actor_digest FROM fraud_alert_events WHERE alert_id = ? AND action = 'RESCORED'",
                        String.class,
                        alertId))
                .as("no actor invented to fill the column")
                .isNull();
    }

    @Test
    @DisplayName("a payment with no alert gets no re-score entry")
    void aRescoreOfAnUnalertedPaymentRecordsNothing() {
        // Nothing to attach it to, and inventing an alert for a re-score nobody is looking at would put a
        // clean payment into the fraud queue.
        UUID transactionId = UUID.randomUUID();
        whenVelocityIs(0);
        consumer.onTransactionCreated(envelopeFor(transactionId, "10.00", "GBP"));

        consumer.onAnalysisRequested(rescoreEnvelopeFor(transactionId, FraudFixtures.OWNER_DIGEST, "routine sweep"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fraud_alert_events", Integer.class))
                .isZero();
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Makes the velocity counter answer, which is the difference between the signal being present and the
     * service having degraded without saying so.
     */
    private void whenVelocityIs(int countIncludingThisPayment) {
        when(redis.execute(
                        any(RedisScript.class),
                        anyList(),
                        any(String.class),
                        any(String.class),
                        any(String.class),
                        any(String.class)))
                .thenReturn((long) countIncludingThisPayment);
    }

    private String rescoreEnvelopeFor(UUID transactionId) {
        return rescoreEnvelopeFor(transactionId, FraudFixtures.OWNER_DIGEST, "manual review");
    }

    private String rescoreEnvelopeFor(UUID transactionId, String requestedBy, String reason) {
        return rescoreEnvelopeWithId(UUID.randomUUID(), transactionId, requestedBy, reason);
    }

    private String rescoreEnvelopeWithId(UUID eventId, UUID transactionId) {
        return rescoreEnvelopeWithId(eventId, transactionId, FraudFixtures.OWNER_DIGEST, "manual review");
    }

    private String rescoreEnvelopeWithId(UUID eventId, UUID transactionId, String requestedBy, String reason) {
        var payload = new FraudEvents.RescoreRequestPayload(
                transactionId.toString(), requestedBy, reason, FraudFixtures.NOW.toString());
        return write(new EventEnvelope<>(
                eventId.toString(),
                "fraud.analysis-requested",
                EventEnvelope.CURRENT_VERSION,
                FraudFixtures.NOW,
                "test-correlation",
                "Transaction",
                transactionId.toString(),
                payload,
                Map.of()));
    }

    private String envelopeFor(UUID transactionId, String amount, String currency) {
        var context = new FraudEvents.TransactionCreatedPayload.FraudContextPayload(
                FraudFixtures.CARD_DIGEST, "WEB", FraudFixtures.DEVICE_DIGEST, FraudFixtures.NETWORK_DIGEST);
        var payload = new FraudEvents.TransactionCreatedPayload(
                transactionId.toString(),
                FraudFixtures.OWNER_DIGEST,
                amount,
                currency,
                "Coffee Bar",
                "merchant-1",
                context,
                FraudFixtures.NOW.toString());
        return envelopeOf(UUID.randomUUID(), payload);
    }

    private String envelopeWithId(UUID eventId, UUID transactionId, String amount, String currency) {
        var context = new FraudEvents.TransactionCreatedPayload.FraudContextPayload(
                FraudFixtures.CARD_DIGEST, "WEB", FraudFixtures.DEVICE_DIGEST, FraudFixtures.NETWORK_DIGEST);
        var payload = new FraudEvents.TransactionCreatedPayload(
                transactionId.toString(),
                FraudFixtures.OWNER_DIGEST,
                amount,
                currency,
                "Coffee Bar",
                "merchant-1",
                context,
                FraudFixtures.NOW.toString());
        return envelopeOf(eventId, payload);
    }

    private String envelopeOf(UUID eventId, FraudEvents.TransactionCreatedPayload payload) {
        return write(new EventEnvelope<>(
                eventId.toString(),
                "transaction.created",
                EventEnvelope.CURRENT_VERSION,
                FraudFixtures.NOW,
                "test-correlation",
                "Transaction",
                payload.transactionId() == null ? "00000000-0000-0000-0000-000000000000" : payload.transactionId(),
                payload,
                Map.of()));
    }

    private String write(EventEnvelope<?> envelope) {
        try {
            return json.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("test could not serialise an envelope", e);
        }
    }

    private UUID singleAlertIdFor(UUID transactionId) {
        return jdbc.queryForObject("SELECT id FROM fraud_alerts WHERE transaction_id = ?", UUID.class, transactionId);
    }

    private String alertState(UUID alertId) {
        return jdbc.queryForObject("SELECT state FROM fraud_alerts WHERE id = ?", String.class, alertId);
    }

    private java.util.List<String> timelineFor(UUID alertId) {
        return jdbc.queryForList(
                "SELECT action FROM fraud_alert_events WHERE alert_id = ? ORDER BY occurred_at, action",
                String.class,
                alertId);
    }

    private java.util.List<String> tableNames() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
    }

    private java.util.List<String> columnNamesOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?", String.class, table);
    }

    private int attemptsFor(UUID transactionId) {
        Integer attempt = jdbc.queryForObject(
                "SELECT attempt FROM risk_decisions WHERE transaction_id = ?", Integer.class, transactionId);
        return attempt == null ? 0 : attempt;
    }
}
