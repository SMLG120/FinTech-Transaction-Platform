package com.fintech.platform.transaction.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.transaction.messaging.DisputePayloads.StatusChangedPayload;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refund trigger rules, without a broker or a ledger.
 *
 * <p>What moves money here is one string — {@code REFUNDED} — so the tests pin exactly which
 * strings do and do not. An outcome this service has never heard of must refuse rather than
 * default, because defaulting the wrong way on an unknown outcome is either a kept refund or a
 * given one, and neither is acceptable as a guess.
 */
class DisputePayloadsTest {

    @Test
    @DisplayName("a refund outcome moves money and parses its payment")
    void refundMovesMoney() {
        StatusChangedPayload event = new StatusChangedPayload(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "FRAUD", "REFUNDED");

        assertThat(event.isRefund()).isTrue();
        assertThat(event.transactionUuid()).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        assertThatCode(event::requireDecided).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rejection moves nothing")
    void rejectionMovesNothing() {
        StatusChangedPayload event =
                new StatusChangedPayload("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", null, "FRAUD", "REJECTED");

        assertThat(event.isRefund()).isFalse();
        assertThatCode(event::requireDecided).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses an outcome nobody decided, rather than guessing which way the money goes")
    void refusesAnUnknownOutcome() {
        StatusChangedPayload event =
                new StatusChangedPayload("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", null, "FRAUD", "MAYBE");

        assertThatThrownBy(event::requireDecided).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a missing outcome, because an undecided event is not a decision")
    void refusesAMissingOutcome() {
        StatusChangedPayload event =
                new StatusChangedPayload("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", null, "FRAUD", null);

        assertThatThrownBy(event::requireDecided).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a transaction id that is not a UUID rather than refunding a broken reference")
    void refusesABrokenTransactionId() {
        StatusChangedPayload event =
                new StatusChangedPayload("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "not-a-uuid", "FRAUD", "REFUNDED");

        assertThatThrownBy(event::transactionUuid).isInstanceOf(IllegalArgumentException.class);
    }
}
