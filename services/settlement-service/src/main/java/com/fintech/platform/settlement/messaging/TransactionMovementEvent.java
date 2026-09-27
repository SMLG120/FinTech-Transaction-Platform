package com.fintech.platform.settlement.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.platform.settlement.domain.SettlementLineKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

/**
 * A {@code transaction-settled} or {@code transaction-reversed} payload.
 *
 * <p>Bound to the body transaction-service actually publishes, which is the same for both events:
 * {@code transactionId}, {@code ownerSubjectDigest}, {@code amount}, {@code currency}, {@code status},
 * {@code payeeName}, {@code occurredAt}, {@code version}. The two events differ only in {@code status},
 * so the kind is derived from that rather than carried as its own field.
 *
 * <p>{@code ownerSubjectDigest} is parsed and then ignored. It is present because transaction-service
 * publishes one payload shape for every transaction event, and the alternative to binding it here would
 * be to fail on an unknown property — but nothing in a settlement statement is per-customer, so it is
 * declared and dropped rather than stored. Storing it would put the platform's most sensitive derived
 * identifier in the one service with a staff-facing API, for no gain.
 *
 * <p>Unknown properties are ignored rather than rejected, so a producer can add a field without breaking
 * this consumer. That is a deliberate trade: a consumer that rejects what it does not understand turns
 * every producer's additive change into an outage, and the risk of tolerating an extra field is bounded
 * because the fields this class actually uses are all required.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionMovementEvent(
        UUID transactionId,
        String ownerSubjectDigest,
        String amount,
        String currency,
        String status,
        String payeeName,
        Instant occurredAt,
        long version) {

    /**
     * The line kind this event represents.
     *
     * <p>Derived from {@code status}, which is how the two events are told apart. A movement that is
     * neither {@code SETTLED} nor {@code REVERSED} is rejected rather than guessed at: this consumer
     * handles two exact facts, and a third status means a producer has changed something this service has
     * not been taught, which is a case for a human rather than a default.
     *
     * @return {@link SettlementLineKind#CAPTURE} or {@link SettlementLineKind#REVERSAL}
     * @throws IllegalArgumentException if the status is missing or is neither
     */
    public SettlementLineKind kind() {
        // Required explicitly, because switching on a null String throws a NullPointerException that says
        // nothing about which event was malformed. A payload missing its status should be refused with a
        // message naming the event, not arrive at the dead-letter topic as a stack trace with no context.
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("transaction event for " + transactionId
                    + " carries no status, so this service cannot tell a capture from a refund. Refusing it "
                    + "is deliberate: a settlement line of the wrong kind is a statement that does not "
                    + "reconcile against anything, and it would be found days later by somebody comparing "
                    + "two figures.");
        }
        return switch (status) {
            case "SETTLED" -> SettlementLineKind.CAPTURE;
            case "REVERSED" -> SettlementLineKind.REVERSAL;
            default ->
                throw new IllegalArgumentException("transaction event has status '" + status + "', which "
                        + "this service does not know how to settle. It handles exactly two facts — a capture and "
                        + "a refund — and inferring a third from an unfamiliar status is how a statement ends up "
                        + "with money in it that nobody can account for.");
        };
    }

    /**
     * The payment's amount, as a decimal string.
     *
     * <p>Always positive, even for a reversal: the payload carries the transaction's own amount, and
     * negating a refund is the consumer's job because it is the consumer that knows which direction the
     * money moved. Doing it here would mean trusting a field to carry a meaning it does not have.
     *
     * @return the amount as sent
     */
    public String amount() {
        return amount;
    }

    /**
     * The currency the amount is in.
     *
     * @return the currency code
     */
    public Currency currencyObject() {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "transaction event for " + transactionId + " carries no currency, so it has no period to "
                            + "belong to. A cycle is one currency, so a movement with no currency cannot be "
                            + "counted anywhere.");
        }
        try {
            return Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "transaction event for " + transactionId + " carries '" + currency
                            + "', which is not an ISO 4217 code. A cycle is denominated in a real currency, so there is "
                            + "nothing to sum this into.",
                    e);
        }
    }

    /**
     * The business date this movement belongs to, in the producing service's own words.
     *
     * <p>Read from the payload's {@code occurredAt} rather than from the envelope's, and in UTC, because
     * the payload is the producer's statement about when the money moved. A cycle is a named day, and a day
     * that changes depending on which clock read it is a day that cannot be reconciled.
     *
     * <p>The consequence is stated in ADR-0009: a payment settled at 23:30 UTC belongs to that date, so a
     * cycle closed in the same instant can miss it. That is a batch boundary, not a bug.
     *
     * @return the business date
     * @throws IllegalArgumentException if the payload carries no {@code occurredAt}
     */
    public LocalDate businessDate() {
        if (occurredAt == null) {
            throw new IllegalArgumentException("transaction event for " + transactionId + " carries no occurredAt, "
                    + "so it has no business date. Business dates are read from the payload rather than from the "
                    + "clock precisely so a cycle is a fixed day, and an event with no day cannot be placed in "
                    + "one.");
        }
        return occurredAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /**
     * The amount as a plain decimal, for validation before it reaches the money parser.
     *
     * @return the amount as a {@link BigDecimal}
     */
    public BigDecimal amountAsDecimal() {
        return new BigDecimal(amount);
    }
}
