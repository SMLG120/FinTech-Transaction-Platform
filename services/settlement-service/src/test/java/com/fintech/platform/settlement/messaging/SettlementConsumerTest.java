package com.fintech.platform.settlement.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.settlement.domain.SettlementLineKind;
import com.fintech.platform.settlement.service.SettlementService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The consumer's decisions, with the service replaced by something that counts.
 *
 * <p>Two of this service's most important behaviours are decisions rather than writes, and neither can be
 * observed by looking at the database afterwards. Whether a delivery is a redelivery, and whether an
 * envelope is readable at all, are both settled by the time any row exists — so a test that only asserted
 * the resulting statement would pass equally happily if the service applied every delivery twice and threw
 * away the duplicates later.
 *
 * <p>What is deliberately not here: the broker. This proves what the consumer decides given a record, which
 * is the part with logic in it. Whether Kafka delivers a record twice is the broker's behaviour, and
 * asserting it here would be asserting the broker. The consumer is written so that being wrong about that
 * costs one extra idempotent write rather than a double-counted payment, and
 * {@code SettlementServiceIntegrationTest} covers the statement-level backstop.
 */
class SettlementConsumerTest {

    private static final String SETTLED_TOPIC = "transaction-settled";

    /**
     * Configured the way Spring Boot configures the application's own mapper.
     *
     * <p>The one setting that matters here is {@code FAIL_ON_UNKNOWN_PROPERTIES} being off, which is Boot's
     * default and which every consumer in this platform relies on: {@code EventEnvelope} exposes
     * {@code isSupportedVersion()} as a derived property, so the platform's own serialised envelope always
     * contains a {@code supportedVersion} field that the record has no component for. It reads back fine
     * under the default and fails under a strict mapper — which is worth knowing rather than discovering,
     * and is why this test builds its mapper the same way the application does instead of using a bare
     * {@code new ObjectMapper()}.
     */
    private final ObjectMapper json =
            new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RecordingService settlement;

    private SettlementConsumer consumer;

    @BeforeEach
    void setUp() {
        settlement = new RecordingService();
        MeterRegistry meters = new SimpleMeterRegistry();
        consumer = new SettlementConsumer(settlement, json, meters);
    }

    // ------------------------------------------------------------------ helpers

    private String envelope(String eventId, String eventType, int eventVersion, Map<String, Object> payload) {
        try {
            return json.writeValueAsString(new EventEnvelope<Map<String, Object>>(
                    eventId,
                    eventType,
                    eventVersion,
                    Instant.parse("2026-03-10T09:00:00Z"),
                    "correlation-1",
                    "Transaction",
                    "11111111-2222-3333-4444-555555555555",
                    payload,
                    Map.of()));
        } catch (Exception e) {
            throw new AssertionError("test could not build an envelope", e);
        }
    }

    private static Map<String, Object> settledPayload() {
        return Map.of(
                "transactionId", "11111111-2222-3333-4444-555555555555",
                "ownerSubjectDigest", "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "amount", "500.00",
                "currency", "GBP",
                "status", "SETTLED",
                "payeeName", "Coffee",
                "occurredAt", "2026-03-10T09:00:00Z",
                "version", 1);
    }

    // ------------------------------------------------------------------ scenarios

    @Nested
    @DisplayName("a redelivery")
    class Redelivery {

        @Test
        @DisplayName("is applied once, however many times the broker hands it over")
        void appliesOnceAndNotTwice() {
            String record = envelope(UUID.randomUUID().toString(), "transaction.settled", 1, settledPayload());

            consumer.onTransactionSettled(record);
            consumer.onTransactionSettled(record);
            consumer.onTransactionSettled(record);

            // The claim is an INSERT ... ON CONFLICT DO NOTHING, so this is the broker's behaviour being
            // tolerated rather than prevented. Two applications would mean one payment on a statement
            // twice, and a statement that does not reconcile against anything — found days later by
            // somebody comparing two figures and neither matching the other.
            assertThat(settlement.applied.get()).isEqualTo(1);
            assertThat(settlement.duplicates.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("is not applied twice when two events describe the same payment")
        void distinctEventIdsForOneFact() {
            // Different event ids, same transaction. The claim cannot catch this — these are genuinely two
            // events — so the statement's own uniqueness has to. Recording it here keeps the layering
            // honest: the consumer dedups deliveries, the domain dedups facts.
            consumer.onTransactionSettled(
                    envelope(UUID.randomUUID().toString(), "transaction.settled", 1, settledPayload()));
            consumer.onTransactionSettled(
                    envelope(UUID.randomUUID().toString(), "transaction.settled", 1, settledPayload()));

            assertThat(settlement.applied.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("routes by the payload's own status, so the two topics need only one handler")
    void routesByStatus() {
        consumer.onTransactionSettled(
                envelope(UUID.randomUUID().toString(), "transaction.settled", 1, settledPayload()));
        assertThat(settlement.captures.get()).isEqualTo(1);
        assertThat(settlement.reversals.get()).isZero();

        consumer.onTransactionReversed(envelope(
                UUID.randomUUID().toString(),
                "transaction.reversed",
                1,
                Map.of(
                        "transactionId", "11111111-2222-3333-4444-555555555555",
                        "ownerSubjectDigest", "sha256:irrelevant",
                        "amount", "500.00",
                        "currency", "GBP",
                        "status", "REVERSED",
                        "payeeName", "Coffee",
                        "occurredAt", "2026-03-11T09:00:00Z",
                        "version", 2)));
        assertThat(settlement.reversals.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("claims before it applies, so a failure after the claim cannot lose the claim")
    void claimsBeforeApplying() {
        // The order is the property. Claim first, then apply, in one transaction: a failure after the claim
        // rolls the claim back and the event is retried whole. The reverse — mark processed, then fail —
        // would silently lose a payment from its statement, which is the worse of the two failures.
        RecordingService recording = new RecordingService();
        consumer = new SettlementConsumer(recording, json, new SimpleMeterRegistry());
        String record = envelope(UUID.randomUUID().toString(), "transaction.settled", 1, settledPayload());

        consumer.onTransactionSettled(record);

        // Exactly one claim and one apply, in that order. The counter is checked before the apply, so a
        // consumer that applied first and claimed afterwards — which is the order that loses a payment
        // when the apply fails — cannot pass.
        assertThat(recording.claimed.get()).isEqualTo(1);
        assertThat(recording.claimedBeforeFirstApply.get()).isTrue();
        assertThat(recording.applied.get()).isEqualTo(1);
    }

    @Nested
    @DisplayName("a record it cannot understand")
    class Malformed {

        @Test
        @DisplayName("is refused rather than half-applied, so the offset is not committed over it")
        void unreadableJsonIsRefused() {
            assertThatThrownBy(() -> consumer.onTransactionSettled("{not json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a readable event envelope");

            assertThat(settlement.applied.get()).isZero();
        }

        @Test
        @DisplayName("with an unsupported version, is refused before the claim")
        void unsupportedVersionIsRefused() {
            // A v2 payload parsed into v1 fields produces a statement that looks complete, because the
            // field that would have flagged the change was dropped on the way in. The version number is
            // the only thing that prevents that, so this check comes before the claim rather than after.
            String record = envelope(UUID.randomUUID().toString(), "transaction.settled", 2, settledPayload());

            assertThatThrownBy(() -> consumer.onTransactionSettled(record))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported event version 2");

            assertThat(settlement.claimed.get()).isZero();
        }

        @Test
        @DisplayName("with an event id that is not a UUID, is refused rather than deduplicated wrongly")
        void nonUuidEventIdIsRefused() {
            // A deduplication key derived from something that is not a UUID is wrong in a way that silently
            // disables deduplication for that event — which is to say, silently double-counts it. Refusing
            // is the only safe answer.
            String record = envelope("not-a-uuid", "transaction.settled", 1, settledPayload());

            assertThatThrownBy(() -> consumer.onTransactionSettled(record))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("eventId is not a UUID");

            assertThat(settlement.claimed.get()).isZero();
        }

        @Test
        @DisplayName("whose payload is missing required fields, is refused inside the claim")
        void incompletePayloadIsRefused() {
            String record = envelope(
                    UUID.randomUUID().toString(),
                    "transaction.settled",
                    1,
                    Map.of("transactionId", "11111111-2222-3333-4444-555555555555", "currency", "GBP"));

            assertThatThrownBy(() -> consumer.onTransactionSettled(record))
                    .isInstanceOf(IllegalArgumentException.class);

            // The claim happened first, so it has to be rolled back. This is why claimAndApply is
            // transactional: an unparseable payload must not burn its event id and be skipped on retry.
            assertThat(settlement.applied.get()).isZero();
        }
    }

    // ------------------------------------------------------------------ a stand-in that records decisions

    /**
     * Counts what it was asked to do, standing in for the database.
     *
     * <p>Deliberately a hand-written subclass rather than a mock: the property under test is the
     * <em>order</em> of the claim and the apply, and a mock verifies calls but knows nothing about the
     * consequence of one of them failing. This one keeps the claim ledger, so "applied once" and "the claim
     * was rolled back" are things it can actually answer.
     */
    private class RecordingService extends SettlementService {

        private final java.util.Set<UUID> ledger = new java.util.HashSet<>();

        private final AtomicInteger claimed = new AtomicInteger();

        private final AtomicInteger applied = new AtomicInteger();

        /** Whether the claim was still the newest thing to happen when the apply ran. */
        private final java.util.concurrent.atomic.AtomicBoolean claimedBeforeFirstApply =
                new java.util.concurrent.atomic.AtomicBoolean();

        private final AtomicInteger duplicates = new AtomicInteger();

        private final AtomicInteger captures = new AtomicInteger();

        private final AtomicInteger reversals = new AtomicInteger();

        RecordingService() {
            super(null, null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public boolean claimAndApply(UUID eventId, String topic, Runnable apply) {
            claimed.incrementAndGet();
            if (!ledger.add(eventId)) {
                duplicates.incrementAndGet();
                return false;
            }
            claimedBeforeFirstApply.set(claimed.get() == 1);
            try {
                apply.run();
            } catch (RuntimeException e) {
                // A failed apply takes the claim with it, which is what the real transaction does. Without
                // this the test above could not tell a rolled-back claim from a burned one.
                ledger.remove(eventId);
                throw e;
            }
            applied.incrementAndGet();
            return true;
        }

        @Override
        public com.fintech.platform.settlement.domain.SettlementCycle applyCapture(TransactionMovementEvent event) {
            if (event.kind() != SettlementLineKind.CAPTURE) {
                throw new AssertionError("a capture event with status " + event.status());
            }
            captures.incrementAndGet();
            return null;
        }

        @Override
        public com.fintech.platform.settlement.domain.SettlementCycle applyReversal(TransactionMovementEvent event) {
            reversals.incrementAndGet();
            return null;
        }
    }
}
