package com.fintech.platform.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.audit.messaging.AuditPayloads.AuditEventPayload;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payload rules, without a broker or a database.
 *
 * <p>What a recorder must refuse is as important as what it records: a row with no action is a
 * fact filed where no auditor will look for it, so the check dead-letters the event rather than
 * writing a row that says nothing.
 */
class AuditPayloadsTest {

    private static AuditEventPayload payload() {
        return new AuditEventPayload(
                "fraud-alert-claimed",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "fraud-alert",
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "digest-held-by-service",
                "SUCCESS",
                Map.of("alertId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    }

    @Test
    @DisplayName("accepts a complete staff-action payload and parses its payment")
    void acceptsACompletePayload() {
        AuditEventPayload event = payload();

        assertThat(event.transactionUuidOrNull()).isEqualTo(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        org.assertj.core.api.Assertions.assertThatCode(event::requireRecordable).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing payment is null rather than a failure, for facts that are not about one")
    void missingPaymentIsNull() {
        AuditEventPayload event = new AuditEventPayload(
                "settlement-cycle-closed", "ref", "settlement-cycle", null, null, "SUCCESS", null);

        assertThat(event.transactionUuidOrNull()).isNull();
        org.assertj.core.api.Assertions.assertThatCode(event::requireRecordable).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a payload with no action, rather than filing it nowhere")
    void refusesAnActionlessPayload() {
        AuditEventPayload event = new AuditEventPayload(null, "id", "type", null, null, "SUCCESS", null);

        assertThatThrownBy(event::requireRecordable).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a payload with no result, because an outcome nobody recorded is not an outcome")
    void refusesAResultlessPayload() {
        AuditEventPayload event = new AuditEventPayload("fraud-alert-claimed", "id", "type", null, null, null, null);

        assertThatThrownBy(event::requireRecordable).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a transaction id that is not a UUID rather than storing a broken join")
    void refusesABrokenTransactionId() {
        AuditEventPayload event =
                new AuditEventPayload("fraud-alert-claimed", "id", "type", "not-a-uuid", null, "SUCCESS", null);

        assertThatThrownBy(event::transactionUuidOrNull).isInstanceOf(IllegalArgumentException.class);
    }
}
