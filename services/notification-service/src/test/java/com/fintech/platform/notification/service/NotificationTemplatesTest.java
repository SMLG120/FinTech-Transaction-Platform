package com.fintech.platform.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.notification.domain.NotificationKind;
import com.fintech.platform.notification.service.NotificationTemplates.Rendered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The words in every message, without a database, a broker or a clock.
 *
 * <p>The wording is the contract a support agent reads back to a caller, so it is asserted as text
 * rather than left to an end-to-end run. The amounts are the part worth pinning: a stored {@code
 * 5000} in GBP is {@code GBP 50.00}, and a template that rendered the wire's decimal string instead
 * of the stored count would tell the customer a figure the service never recorded.
 */
class NotificationTemplatesTest {

    @ParameterizedTest(name = "{0} names the figure the service recorded")
    @CsvSource({"PAYMENT_AUTHORIZED", "PAYMENT_DECLINED", "PAYMENT_SETTLED", "PAYMENT_REFUNDED"})
    @DisplayName("renders a payment message from the stored figure, never from the wire")
    void paymentNamesTheStoredFigure(NotificationKind kind) {
        Rendered rendered = NotificationTemplates.payment(kind, "50.00", "GBP", "Acme Books");

        assertThat(rendered.body()).contains("GBP 50.00").contains("Acme Books");
    }

    @Test
    @DisplayName("a declined payment says no money moved, because silence reads as a lost payment")
    void declinedSaysNoMoneyMoved() {
        Rendered rendered = NotificationTemplates.payment(NotificationKind.PAYMENT_DECLINED, "50.00", "GBP", "Acme");

        assertThat(rendered.body()).contains("No money has left your account");
    }

    @Test
    @DisplayName("a missing payee reads as 'your payee' rather than as a blank")
    void missingPayeeReadsAsYourPayee() {
        Rendered rendered = NotificationTemplates.payment(NotificationKind.PAYMENT_SETTLED, "50.00", "GBP", null);

        assertThat(rendered.body()).contains("your payee");
    }

    @Test
    @DisplayName("a fraud review asks the customer to act, without naming a score")
    void fraudReviewAsksForAction() {
        Rendered rendered = NotificationTemplates.fraud(NotificationKind.FRAUD_REVIEW, "120.00", "GBP", "Acme");

        assertThat(rendered.subject()).isEqualTo("Check your recent payment");
        assertThat(rendered.body()).contains("contact support").doesNotContain("score");
    }

    @Test
    @DisplayName("a settlement break names the period, because the email is about a period's money")
    void settlementBreakNamesThePeriod() {
        Rendered rendered = NotificationTemplates.settlement(
                NotificationKind.SETTLEMENT_BREAK, "SETTLE-2026-09-27-GBP", "expected 100.00 but declared 99.00");

        assertThat(rendered.subject()).isEqualTo("Settlement break needs attention");
        assertThat(rendered.body()).contains("SETTLE-2026-09-27-GBP");
    }

    @Test
    @DisplayName("refuses a fraud kind on the payment renderer, because the channel would be wrong")
    void paymentRefusesAFraudKind() {
        // A fraud text sent as a push about a payment is a message on the wrong channel about the
        // wrong fact; refusing here keeps the consumer's kind mapping honest.
        assertThatThrownBy(() -> NotificationTemplates.payment(NotificationKind.FRAUD_REVIEW, "50.00", "GBP", "Acme"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses an unknown currency rather than rendering a figure without one")
    void refusesAnUnknownCurrency() {
        assertThatThrownBy(
                        () -> NotificationTemplates.payment(NotificationKind.PAYMENT_SETTLED, "50.00", "XYZ", "Acme"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
