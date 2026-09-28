package com.fintech.platform.transaction.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import com.fintech.platform.transaction.messaging.DisputePayloads.StatusChangedPayload;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * The consumer side of the dispute-resolution event contract.
 *
 * <p>Parses the shared fixtures with the real consumer types — {@link EventEnvelope} plus {@link
 * StatusChangedPayload} — and applies the real business rules ({@code requireDecided}, {@code
 * isRefund}, {@code transactionUuid}). These are the exact steps {@code DisputeResolutionConsumer}
 * performs before touching the ledger, minus the ledger itself (covered by the service tests and
 * the live dispute lifecycle). A producer field rename parses here into nulls and fails these
 * assertions instead of silently not refunding in production. The producer side pins the same
 * bytes in dispute-service's {@code DisputeEventContractTest}.
 *
 * <p>No Spring context, no broker: the question is purely whether the bytes agree.
 */
class DisputeEventConsumerContractTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("a refunded fixture parses into a money-moving decision")
    void refundFixtureMovesMoney() throws Exception {
        StatusChangedPayload payload = payloadOf("contracts/events/dispute-status-changed.json");

        assertThatCode(payload::requireDecided).doesNotThrowAnyException();
        assertThat(payload.isRefund()).isTrue();
        assertThat(payload.transactionUuid()).isEqualTo(UUID.fromString("22222222-3333-4444-5555-666666666666"));
        assertThat(payload.disputeId()).isEqualTo("33333333-4444-5555-6666-777777777777");
    }

    @Test
    @DisplayName("a rejected fixture parses into a decision that moves nothing")
    void rejectionFixtureMovesNothing() throws Exception {
        StatusChangedPayload payload = payloadOf("contracts/events/dispute-status-rejected.json");

        assertThatCode(payload::requireDecided).doesNotThrowAnyException();
        assertThat(payload.isRefund()).isFalse();
    }

    @Test
    @DisplayName("the fixture envelope carries a supported version and a usable event id")
    void envelopeIsRoutable() throws Exception {
        String record = fixture("contracts/events/dispute-status-changed.json");

        EventEnvelope<JsonNode> envelope = JSON.readValue(record, new TypeReference<EventEnvelope<JsonNode>>() {});

        assertThat(envelope.isSupportedVersion()).isTrue();
        assertThat(envelope.eventType()).isEqualTo("dispute.resolved.refunded");
        assertThatCode(() -> UUID.fromString(envelope.eventId())).doesNotThrowAnyException();
    }

    private static StatusChangedPayload payloadOf(String name) throws Exception {
        EventEnvelope<JsonNode> envelope =
                JSON.readValue(fixture(name), new TypeReference<EventEnvelope<JsonNode>>() {});
        return JSON.treeToValue(envelope.payload(), StatusChangedPayload.class);
    }

    private static String fixture(String name) throws Exception {
        return StreamUtils.copyToString(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8);
    }
}
