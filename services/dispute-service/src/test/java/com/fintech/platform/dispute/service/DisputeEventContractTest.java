package com.fintech.platform.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.dispute.service.DisputeService.DisputePayload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * The producer side of the dispute-resolution event contract.
 *
 * <p>transaction-service refunds on exactly one string — {@code REFUNDED} — read from a payload
 * whose field names must match this service's {@link DisputePayload}. The two records are
 * deliberately separate types (no shared domain), so a rename on either side is invisible to the
 * compiler and would otherwise surface as money silently not moving. This test serialises the
 * real payload type and the real envelope and compares them against the shared fixtures, so the
 * rename breaks here. The consumer side parses the same bytes with the real consumer types in
 * transaction-service's {@code DisputeEventConsumerContractTest}.
 *
 * <p>No Spring context, no broker: the question is purely whether the bytes agree.
 */
class DisputeEventContractTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("a refund resolution serialises to the shared refunded fixture")
    void refundMatchesFixture() throws Exception {
        JsonNode fixture = payloadOf("contracts/events/dispute-status-changed.json");

        DisputePayload produced = new DisputePayload(
                "33333333-4444-5555-6666-777777777777", "22222222-3333-4444-5555-666666666666", "FRAUD", "REFUNDED");

        // JsonNode equality ignores field order: the contract is the names and values.
        assertThat(fixture.equals(JSON.valueToTree(produced))).isTrue();
    }

    @Test
    @DisplayName("a rejection serialises to the shared rejected fixture")
    void rejectionMatchesFixture() throws Exception {
        JsonNode fixture = payloadOf("contracts/events/dispute-status-rejected.json");

        DisputePayload produced = new DisputePayload(
                "33333333-4444-5555-6666-777777777777",
                "22222222-3333-4444-5555-666666666666",
                "NOT_RECEIVED",
                "REJECTED");

        assertThat(fixture.equals(JSON.valueToTree(produced))).isTrue();
    }

    @Test
    @DisplayName("resolutions are announced on the topic the ledger listens to")
    void announcedOnTheLedgerTopic() {
        // The topic name is a second half of the contract, asserted where the producer
        // names it rather than trusted to a string both sides happened to type the same.
        // A rename here without the consumer following means decided cases without refunds.
        assertThat(KafkaTopics.DISPUTE_STATUS_CHANGED).isEqualTo("dispute-status-changed");
    }

    @Test
    @DisplayName("the envelope carries the refunded event type the consumer routes on")
    void envelopeCarriesRoutableType() throws Exception {
        EventEnvelope<DisputePayload> envelope = new EventEnvelope<>(
                "44444444-5555-6666-7777-888888888888",
                "dispute.resolved.refunded",
                EventEnvelope.CURRENT_VERSION,
                Instant.parse("2026-09-20T10:05:00Z"),
                "55555555-6666-7777-8888-999999999999",
                "Dispute",
                "33333333-4444-5555-6666-777777777777",
                new DisputePayload(
                        "33333333-4444-5555-6666-777777777777",
                        "22222222-3333-4444-5555-666666666666",
                        "FRAUD",
                        "REFUNDED"),
                null);

        String serialised = JSON.writeValueAsString(envelope);

        assertThat(serialised).contains("\"eventType\":\"dispute.resolved.refunded\"");
        assertThat(serialised).contains("\"eventVersion\":1");
    }

    private static JsonNode payloadOf(String name) throws Exception {
        String bytes = StreamUtils.copyToString(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8);
        return JSON.readTree(bytes).required("payload");
    }
}
