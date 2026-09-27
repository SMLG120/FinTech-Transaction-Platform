package com.fintech.platform.transaction.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fintech.platform.transaction.domain.OutboxEvent;
import com.fintech.platform.transaction.service.OutboxRelay;
import java.time.Instant;
import java.util.List;
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
 * <p>Kafka is stubbed, and that is a deliberate limit rather than an oversight. What is under test is
 * the relay's behaviour <em>around</em> the send: whether a row is marked, when it is marked, what
 * happens when the send fails, and whether a later pass republishes. A real broker would add a
 * producer, a topic and a consumer group to say the same thing more slowly, and would make the test
 * depend on a running Kafka.
 *
 * <p>What a stub cannot show is the property that matters most, and it is not this test's job to pretend
 * otherwise: the event and the state change it describes commit together. That is
 * {@code OutboxWriter}'s {@code Propagation.MANDATORY}, and it is tested where it can be observed — at
 * the transaction boundary, not at the broker.
 */
@Testcontainers
@SpringBootTest
class OutboxRelayTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_transactions_outbox");

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
        // The relay is the subject here, so it is driven by hand through relayPending(). Leaving the
        // scheduler on would have it publishing rows the assertions are about to publish themselves, and
        // the test would pass or fail according to whether a poll landed between two statements.
        registry.add("app.outbox.relay-enabled", () -> "false");
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outbox;

    /**
     * The producer, stubbed.
     *
     * <p>A {@code @MockitoBean} rather than a real {@code KafkaTemplate} so the test needs no broker and
     * can make a send fail on demand — which is the half of the contract that matters and the half that
     * is otherwise impossible to observe, because a healthy broker never fails.
     */
    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE journal_lines, journal_entries, outbox_events, "
                + "idempotency_keys, transactions, ledger_accounts CASCADE");
        reset(kafka);
    }

    @Test
    @DisplayName("a published event is marked published")
    void publishesAndMarks() {
        givenEvent("transaction.created");

        everySendSucceeds();

        relay.relayPending();

        assertThat(outbox.countPending())
                .as("the row is marked, so the next pass does not pick it up again")
                .isZero();
        assertThat(topicsSentTo()).containsExactly("transaction.created");
    }

    @Test
    @DisplayName("a second pass finds nothing left to do")
    void secondPassIsEmpty() {
        givenEvent("transaction.created");
        everySendSucceeds();

        relay.relayPending();
        relay.relayPending();

        assertThat(topicsSentTo())
                .as("at-least-once is a floor, not a quota: an already-marked row is not resent")
                .containsExactly("transaction.created");
    }

    @Test
    @DisplayName("a failed send stays pending, records why, and is retried until it works")
    void failedSendIsRetried() {
        OutboxEvent event = givenEvent("transaction.created");

        everySendFails("broker unavailable");
        relay.relayPending();

        assertThat(outbox.countPending())
                .as("the event is still owed to a consumer, so it stays in the queue")
                .isEqualTo(1);
        OutboxEvent afterFailure = outbox.findById(event.id()).orElseThrow();
        assertThat(afterFailure.attempts())
                .as("a retry loop with no counter cannot be alerted on")
                .isEqualTo(1);
        assertThat(afterFailure.lastError())
                .as("and the reason is kept, because 'it did not send' is not a diagnosis")
                .contains("broker unavailable");

        everySendSucceeds();
        relay.relayPending();

        assertThat(outbox.countPending()).isZero();
        assertThat(outbox.findById(event.id()).orElseThrow().attempts())
                .as("the count survives the success; it is history, not a status")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failure in one event does not strand the rest of the batch")
    void oneFailureDoesNotStrandTheBatch() {
        OutboxEvent first = givenEvent("transaction.created");
        OutboxEvent doomed = givenEvent("transaction.declined");
        OutboxEvent last = givenEvent("transaction.settled");

        // Only one topic fails. A relay that abandoned the batch on the first error would stall every
        // later event behind one poison message, which is how a single bad row becomes an outage.
        when(kafka.send(eq("transaction.declined"), anyString(), anyString()))
                .thenReturn(failing("broker unavailable"));
        when(kafka.send(
                        argThat(topic -> topic != null && !topic.equals("transaction.declined")),
                        anyString(),
                        anyString()))
                .thenReturn(succeeding());

        relay.relayPending();

        // Asserted on the persisted state rather than on the sends. A failed send is still an attempt, so
        // "three sends happened" is true either way and says nothing; what matters is which rows the
        // relay is still obliged to deliver.
        assertThat(outbox.countPending())
                .as("only the one that failed is still owed")
                .isEqualTo(1);
        assertThat(outbox.findById(doomed.id()).orElseThrow().isPending())
                .as("the failed event stays in the queue")
                .isTrue();
        assertThat(outbox.findById(first.id()).orElseThrow().isPending())
                .as("the one before it still went out, rather than being held hostage by a later failure")
                .isFalse();
        assertThat(outbox.findById(last.id()).orElseThrow().isPending())
                .as("and so did the one after it")
                .isFalse();
    }

    @Test
    @DisplayName("events go out oldest first, so a settlement cannot overtake its authorisation")
    void oldestFirst() {
        // Created in this order, and the query is what guarantees it comes back this way. Ordering by
        // anything else would let transaction-settled reach a consumer before transaction-authorized,
        // and the consumer would have to resolve that itself — the problem the event key exists to
        // prevent.
        givenEvent("transaction.created");
        givenEvent("transaction.authorized");
        givenEvent("transaction.settled");

        everySendSucceeds();
        relay.relayPending();

        assertThat(topicsSentTo())
                .containsExactly("transaction.created", "transaction.authorized", "transaction.settled");
    }

    @Test
    @DisplayName("the stored payload is sent verbatim, not re-serialised")
    void payloadIsSentVerbatim() {
        // The bytes written beside the state change are the bytes that reach the topic. Re-serialising
        // at publish time could produce different JSON if the event shape changed in between, and the
        // event in the topic would stop being the event that was recorded with the payment. The odd
        // spacing is the point: JSON allows it, and a round trip would not preserve it.
        String exact = "{\"eventType\":\"transaction.created\",\"odd spacing\":  1}";
        givenEvent("transaction.created", UUID.randomUUID(), exact);

        everySendSucceeds();
        relay.relayPending();

        verify(kafka).send(eq("transaction.created"), anyString(), eq(exact));
    }

    @Test
    @DisplayName("the partition key is the aggregate, so one payment's events stay in order")
    void keyedByAggregate() {
        UUID aggregate = UUID.randomUUID();
        givenEvent("transaction.created", aggregate);
        givenEvent("transaction.authorized", aggregate);

        everySendSucceeds();
        relay.relayPending();

        verify(kafka, times(2)).send(anyString(), eq(aggregate.toString()), anyString());
    }

    @Test
    @DisplayName("an empty outbox is a no-op, not an error")
    void emptyOutbox() {
        everySendSucceeds();

        relay.relayPending();

        verifyNoInteractions(kafka);
        assertThat(relay.pendingCount()).isZero();
    }

    // ---------------------------------------------------------------------------- helpers

    private OutboxEvent givenEvent(String eventType) {
        return givenEvent(eventType, UUID.randomUUID());
    }

    private OutboxEvent givenEvent(String eventType, UUID aggregateId) {
        return givenEvent(eventType, aggregateId, "{\"eventType\":\"" + eventType + "\"}");
    }

    private OutboxEvent givenEvent(String eventType, UUID aggregateId, String payload) {
        return outbox.save(OutboxEvent.record(
                UUID.randomUUID(),
                "Transaction",
                aggregateId,
                0L,
                eventType,
                aggregateId.toString(),
                eventType,
                payload,
                Instant.now()));
    }

    private void everySendSucceeds() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(succeeding());
    }

    private void everySendFails(String reason) {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failing(reason));
    }

    private CompletableFuture<SendResult<String, String>> succeeding() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SendResult<String, String>> failing(String reason) {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new KafkaException(reason));
        return failed;
    }

    /** The topics the relay sent to, in the order it sent them. */
    private List<String> topicsSentTo() {
        return mockingDetails(kafka).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .map(invocation -> invocation.<String>getArgument(0))
                .toList();
    }
}
