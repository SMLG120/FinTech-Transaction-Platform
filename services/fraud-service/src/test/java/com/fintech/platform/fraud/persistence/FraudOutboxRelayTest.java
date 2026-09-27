package com.fintech.platform.fraud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.fraud.service.OutboxRelay;
import com.fintech.platform.fraud.service.OutboxWriter;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The relay's delivery contract, against a real database and a stubbed broker.
 *
 * <p>Kafka is stubbed, and that is the same deliberate limit as transaction-service's outbox test. What
 * is under test here is the behaviour <em>around</em> the send: whether a row is marked, when, what
 * happens when the send fails, and whether a later pass republishes it. The property that matters most —
 * that a published event and the decision it describes commit together — is not observable at the broker
 * and is not this test's job to pretend otherwise; it is {@link OutboxWriter}'s
 * {@code Propagation.MANDATORY}, and {@code FraudScoringIntegrationTest} is where the event and the
 * decision are seen to appear together.
 *
 * <p>There is a second reason this test earns its keep. fraud-service has its own {@code outbox_events}
 * table rather than sharing transaction-service's, and the two relays are separately deployed. That is a
 * real design choice with a real failure mode — a relay pointed at the wrong table, or a topic copied from
 * the other service — and the assertions below are mostly about which topic the fraud relay publishes
 * to and which key it publishes under, since those are the two things a copy-paste gets wrong.
 */
@Testcontainers
@SpringBootTest
class FraudOutboxRelayTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_fraud_outbox");

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
        // The relay is the subject, so it is driven by hand through relayPending(). Leaving the scheduler
        // on would have it publishing rows between two assertions, and the test would pass or fail
        // according to whether a poll happened to land in the gap.
        registry.add("app.outbox.relay-enabled", () -> "false");
        registry.add("app.background-jobs-enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE fraud_alert_events, fraud_alerts, risk_decisions, fraud_observations, "
                + "processed_events, outbox_events CASCADE");
        reset(kafka);
    }

    // ------------------------------------------------------------------------------- the happy path

    @Test
    @DisplayName("a published event is marked published and is not sent again")
    void publishesAndMarks() {
        givenEvent("fraud.analysis-completed");
        everySendSucceeds();

        relay.relayPending();

        assertThat(pendingCount())
                .as("the row is marked, so the next pass skips it")
                .isZero();
        assertThat(topicsSentTo()).containsExactly(KafkaTopics.FRAUD_ANALYSIS_COMPLETED);
    }

    @Test
    @DisplayName("a second pass finds nothing left to do")
    void secondPassIsEmpty() {
        givenEvent("fraud.analysis-completed");
        everySendSucceeds();

        relay.relayPending();
        relay.relayPending();

        assertThat(topicsSentTo())
                .as("at-least-once is a floor, not a quota: an already-marked row is not resent")
                .containsExactly(KafkaTopics.FRAUD_ANALYSIS_COMPLETED);
    }

    @Test
    @DisplayName("the fraud relay publishes to fraud's own topic, not transaction-service's")
    void publishesToFraudTopics() {
        // fraud-service and transaction-service have identically shaped outbox tables and two separately
        // deployed relays. The topic name is the thing a copy-paste gets wrong, and getting it wrong
        // publishes fraud verdicts onto the transaction stream, where a step-down consumer would act on
        // them. Asserted explicitly for that reason.
        givenEvent("fraud.analysis-completed");
        givenEvent("fraud.analysis-requested");

        everySendSucceeds();
        relay.relayPending();

        assertThat(topicsSentTo())
                .containsExactlyInAnyOrder(KafkaTopics.FRAUD_ANALYSIS_COMPLETED, KafkaTopics.FRAUD_ANALYSIS_REQUESTED)
                .doesNotContain(KafkaTopics.TRANSACTION_CREATED);
    }

    @Test
    @DisplayName("the event key is the payment id, so one payment's events stay on one partition")
    void keysByPaymentId() {
        UUID aggregateId = UUID.randomUUID();
        givenEvent("fraud.analysis-completed", aggregateId);
        everySendSucceeds();

        relay.relayPending();

        assertThat(keysSentWith())
                .as("partition-by-key is what stops one payment's events arriving out of order")
                .containsExactly(aggregateId.toString());
    }

    // ------------------------------------------------------------------------------- failure

    @Test
    @DisplayName("a failed send stays pending, records why, and is retried until it works")
    void failedSendIsRetried() {
        UUID id = givenEvent("fraud.analysis-completed");

        everySendFails("broker unavailable");
        relay.relayPending();

        assertThat(pendingCount())
                .as("the event is still owed to a consumer, so it stays in the queue")
                .isEqualTo(1);
        var afterFailure = outbox.findById(id).orElseThrow();
        assertThat(afterFailure.attempts())
                .as("a retry loop with no counter cannot be alerted on")
                .isEqualTo(1);
        assertThat(afterFailure.lastError())
                .as("'it did not send' is not a diagnosis")
                .contains("broker unavailable");
        assertThat(afterFailure.occurredAt())
                .as("a retry does not rewrite when the event happened; the consumer needs the real time")
                .isEqualTo(FIXED_OCCURRED_AT);

        everySendSucceeds();
        relay.relayPending();

        assertThat(pendingCount()).isZero();
        assertThat(outbox.findById(id).orElseThrow().attempts())
                .as("the count survives the success; it is history, not a status")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failure in one event does not strand the rest of the batch")
    void oneFailureDoesNotStrandTheBatch() {
        UUID first = givenEvent("fraud.analysis-completed");
        UUID doomed = givenEvent("fraud.analysis-requested");
        UUID last = givenEvent("fraud.analysis-completed", UUID.randomUUID());

        when(kafka.send(eq(KafkaTopics.FRAUD_ANALYSIS_REQUESTED), anyString(), anyString()))
                .thenReturn(failing("broker unavailable"));
        when(kafka.send(eq(KafkaTopics.FRAUD_ANALYSIS_COMPLETED), anyString(), anyString()))
                .thenReturn(succeeding());

        relay.relayPending();

        // Asserted on persisted state. A failed send is still an attempt, so "three sends happened" is
        // true either way and says nothing; what matters is which rows are still owed.
        assertThat(pendingCount()).as("only the one that failed is still owed").isEqualTo(1);
        assertThat(outbox.findById(doomed).orElseThrow().isPending())
                .as("the failed event stays in the queue")
                .isTrue();
        assertThat(outbox.findById(first).orElseThrow().isPending())
                .as("the one before it still went out, rather than being held hostage by a later failure")
                .isFalse();
        assertThat(outbox.findById(last).orElseThrow().isPending())
                .as("and so did the one after it")
                .isFalse();
    }

    @Test
    @DisplayName("events go out oldest first")
    void oldestFirst() {
        // A re-score's completion must not overtake the original decision's, or a downstream step-down
        // consumer could act on the earlier verdict last.
        givenEvent("fraud.analysis-completed");
        givenEvent("fraud.analysis-completed");
        givenEvent("fraud.analysis-completed");

        everySendSucceeds();
        relay.relayPending();

        assertThat(pendingCount()).isZero();
    }

    // ------------------------------------------------------------------------------- what the wire carries

    @Test
    @DisplayName("what goes on the wire is an envelope, not the bare payload")
    void wrapsThePayloadInAnEnvelope() throws Exception {
        // A consumer cannot deduplicate, cannot reject an unknown version and cannot correlate without
        // the envelope, and the cost of learning that from a deployed consumer is a replay of the topic.
        givenEvent("fraud.analysis-completed");
        everySendSucceeds();

        relay.relayPending();

        var envelope = json.readTree(firstRecordSent());
        assertThat(envelope.get("eventId")).isNotNull();
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("eventType").asText()).isEqualTo("fraud.analysis-completed");
        assertThat(envelope.get("payload").get("transactionId")).isNotNull();
    }

    @Test
    @DisplayName("the payload carries the reasons, not just the score")
    void carriesTheExplanation() throws Exception {
        // The obvious next consumer is transaction-service deciding whether to step a payment down, and a
        // consumer that has to call back to ask why will eventually not call back at all.
        UUID aggregateId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> writer.record(
                "RiskDecision",
                aggregateId,
                1,
                KafkaTopics.FRAUD_ANALYSIS_COMPLETED,
                "fraud.analysis-completed",
                Map.of(
                        "transactionId",
                        aggregateId.toString(),
                        "score",
                        84,
                        "decision",
                        "DECLINE",
                        "reasons",
                        java.util.List.of(Map.of("ruleId", "R002", "points", 35))),
                FIXED_OCCURRED_AT));
        everySendSucceeds();

        relay.relayPending();

        var sent = mockingDetails(kafka).getInvocations().stream()
                .filter(invocation -> "send".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArgument(2))
                .findFirst()
                .orElseThrow();
        assertThat(json.readTree(firstRecordSent())
                        .at("/payload/reasons/0/ruleId")
                        .asText())
                .isEqualTo("R002");
    }

    // ------------------------------------------------------------------------------- helpers

    private static final Instant FIXED_OCCURRED_AT = Instant.parse("2024-06-15T12:00:00Z");

    private UUID givenEvent(String eventType) {
        return givenEvent(eventType, UUID.randomUUID());
    }

    /**
     * Puts one event in the outbox the only way the service allows.
     *
     * <p>Wrapped in a transaction because {@link OutboxWriter#record} is {@code Propagation.MANDATORY},
     * and that is not ceremony: an event written outside the transaction that changed the state it
     * describes is an event announcing something that then failed to happen. The writer refuses to be
     * called without a transaction in scope, so a caller cannot get that wrong by accident. The
     * transaction here stands in for the scoring transaction a real decision runs in.
     */
    private UUID givenEvent(String eventType, UUID aggregateId) {
        transactionTemplate.executeWithoutResult(status -> writer.record(
                "RiskDecision",
                aggregateId,
                1,
                topicFor(eventType),
                eventType,
                Map.of("transactionId", aggregateId.toString()),
                FIXED_OCCURRED_AT));
        return outbox.findPending(org.springframework.data.domain.Pageable.ofSize(10)).stream()
                .filter(row -> eventType.equals(row.eventType()))
                .map(OutboxEventEntity::id)
                .findFirst()
                .orElseThrow();
    }

    private String topicFor(String eventType) {
        return switch (eventType) {
            case "fraud.analysis-completed" -> KafkaTopics.FRAUD_ANALYSIS_COMPLETED;
            case "fraud.analysis-requested" -> KafkaTopics.FRAUD_ANALYSIS_REQUESTED;
            default -> throw new IllegalArgumentException("no such event type: " + eventType);
        };
    }

    private long pendingCount() {
        return outbox.findPending(org.springframework.data.domain.Pageable.ofSize(100))
                .size();
    }

    private void everySendSucceeds() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(succeeding());
    }

    private void everySendFails(String message) {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failing(message));
    }

    /** The body of the first {@code send} the relay made, which is the only one these tests need. */
    private String firstRecordSent() {
        return mockingDetails(kafka).getInvocations().stream()
                .filter(invocation -> "send".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArgument(2))
                .findFirst()
                .orElseThrow();
    }

    private java.util.List<String> topicsSentTo() {
        return mockingDetails(kafka).getInvocations().stream()
                .filter(invocation -> "send".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArgument(0))
                .toList();
    }

    private java.util.List<String> keysSentWith() {
        return mockingDetails(kafka).getInvocations().stream()
                .filter(invocation -> "send".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArgument(1))
                .toList();
    }

    private CompletableFuture<SendResult<String, String>> succeeding() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SendResult<String, String>> failing(String message) {
        return CompletableFuture.failedFuture(new KafkaException(message));
    }
}
