package com.fintech.platform.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.correlation.CorrelationId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class EventEnvelopeTest {

    private record TransactionCreatedPayload(String transactionId, long amountMinorUnits) {}

    @Test
    @DisplayName("stamps a fresh eventId, which is what consumers deduplicate redeliveries on")
    void assignsIdentity() {
        EventEnvelope<TransactionCreatedPayload> envelope = EventEnvelope.of(
                KafkaTopics.TRANSACTION_CREATED, "Transaction", "TXN-1", new TransactionCreatedPayload("TXN-1", 500));

        assertThat(envelope.eventId()).isNotBlank();
        assertThat(envelope.eventType()).isEqualTo("transaction-created");
        assertThat(envelope.aggregateType()).isEqualTo("Transaction");
        assertThat(envelope.aggregateId()).isEqualTo("TXN-1");
        assertThat(envelope.isSupportedVersion()).isTrue();
        assertThat(envelope.metadata()).isEmpty();
    }

    @Test
    @DisplayName("two events for the same business fact get different eventIds")
    void eventIdsAreUnique() {
        TransactionCreatedPayload payload = new TransactionCreatedPayload("TXN-1", 500);

        assertThat(EventEnvelope.of(KafkaTopics.TRANSACTION_CREATED, "Transaction", "TXN-1", payload)
                        .eventId())
                .isNotEqualTo(EventEnvelope.of(KafkaTopics.TRANSACTION_CREATED, "Transaction", "TXN-1", payload)
                        .eventId());
    }

    @Test
    @DisplayName("inherits the correlation id of the request that caused the event")
    void inheritsCorrelationId() {
        MDC.put(CorrelationId.MDC_KEY, "corr-42");
        try {
            EventEnvelope<TransactionCreatedPayload> envelope = EventEnvelope.of(
                    KafkaTopics.TRANSACTION_CREATED,
                    "Transaction",
                    "TXN-1",
                    new TransactionCreatedPayload("TXN-1", 500));

            assertThat(envelope.correlationId()).isEqualTo("corr-42");
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    @Test
    @DisplayName("generates its own correlation id when there is no request context, so the field is never null")
    void synthesisesCorrelationIdOutsideRequestContext() {
        EventEnvelope<TransactionCreatedPayload> envelope = EventEnvelope.of(
                KafkaTopics.TRANSACTION_CREATED, "Transaction", "TXN-1", new TransactionCreatedPayload("TXN-1", 500));

        assertThat(envelope.correlationId()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("a hostile correlation id in the MDC is replaced rather than propagated to Kafka")
    void sanitisesCorrelationId() {
        MDC.put(CorrelationId.MDC_KEY, "bad id with spaces");
        try {
            EventEnvelope<TransactionCreatedPayload> envelope = EventEnvelope.of(
                    KafkaTopics.TRANSACTION_CREATED,
                    "Transaction",
                    "TXN-1",
                    new TransactionCreatedPayload("TXN-1", 500));

            assertThat(CorrelationId.isAcceptable(envelope.correlationId())).isTrue();
            assertThat(envelope.correlationId()).isNotEqualTo("bad id with spaces");
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    @Test
    @DisplayName("withMetadata returns a new envelope, keeping the original immutable")
    void withMetadataIsNonDestructive() {
        EventEnvelope<TransactionCreatedPayload> original = EventEnvelope.of(
                KafkaTopics.TRANSACTION_CREATED, "Transaction", "TXN-1", new TransactionCreatedPayload("TXN-1", 500));

        EventEnvelope<TransactionCreatedPayload> enriched = original.withMetadata("channel", "web");

        assertThat(original.metadata()).isEmpty();
        assertThat(enriched.metadata()).containsExactly(Map.entry("channel", "web"));
        assertThat(enriched.eventId()).isEqualTo(original.eventId());
        assertThat(enriched.occurredAt()).isEqualTo(original.occurredAt());
    }

    @Test
    @DisplayName("rejects an envelope that could not be routed or deduplicated")
    void rejectsUnroutableEnvelopes() {
        TransactionCreatedPayload payload = new TransactionCreatedPayload("TXN-1", 500);

        assertThatThrownBy(() -> new EventEnvelope<>(
                        null, "transaction-created", 1, Instant.now(), "c", "Transaction", "TXN-1", payload, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new EventEnvelope<>(
                        "id", "transaction-created", 0, Instant.now(), "c", "Transaction", "TXN-1", payload, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EventEnvelope<>(
                        "id", "transaction-created", 1, Instant.now(), "c", "Transaction", "", payload, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a future envelope version is reported as unsupported so consumers can skip it safely")
    void detectsUnsupportedVersion() {
        EventEnvelope<TransactionCreatedPayload> future = new EventEnvelope<>(
                "id",
                "transaction-created",
                2,
                Instant.now(),
                "c",
                "Transaction",
                "TXN-1",
                new TransactionCreatedPayload("TXN-1", 500),
                null);

        assertThat(future.isSupportedVersion()).isFalse();
    }

    @Test
    @DisplayName("the topic catalogue is part of the contract: names are unique and past tense")
    void topicCatalogueIsSane() {
        assertThat(KafkaTopics.ALL).doesNotHaveDuplicates();
        assertThat(KafkaTopics.ALL).allSatisfy(name -> assertThat(name).matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$"));
        assertThat(KafkaTopics.ALL).contains(KafkaTopics.DEAD_LETTER_EVENTS);
    }
}
