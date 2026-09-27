package com.fintech.platform.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.platform.notification.domain.NotificationKind;
import java.util.UUID;

/**
 * The event bodies this service reads.
 *
 * <p>Records with every field defaulted, because a consumer's first obligation is to survive a
 * payload that is missing a field rather than to reject it with a confusing error.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} does the same for fields it has never heard
 * of, which is what lets transaction-service add a field to {@code transaction-settled} without
 * breaking a deployed notification service. The versioning in the envelope is the real defence; this
 * is the courtesy that makes a rolling deploy possible.
 *
 * <p><b>There is deliberately no card token, device reference or network reference field
 * anywhere.</b> The producing services digest those before an event is built, and the only thing
 * this service may correlate on is the owner digest. Naming a token field here would be the first
 * step towards somebody using it, and the ignore-unknown rule means a producer that emitted one
 * anyway would have it dropped rather than read.
 */
public final class NotificationPayloads {

    private NotificationPayloads() {}

    /**
     * A {@code transaction-authorized}, {@code transaction-declined}, {@code transaction-settled} or
     * {@code transaction-reversed} payload, as published by transaction-service.
     *
     * <p>Only the fields a message needs: whose payment, how much, in what currency, to whom. The
     * payload's own {@code status} is not read — the topic already says what happened, and deriving
     * the kind from the topic keeps one source of truth for what each subscription means.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionPayload(
            String transactionId,
            String ownerSubjectDigest,
            String amount,
            String currency,
            String status,
            String payeeName,
            String occurredAt,
            long version) {

        /** The notification kind for a payment event on the given topic. */
        public NotificationKind kindFor(String topic) {
            return switch (topic) {
                case "transaction-authorized" -> NotificationKind.PAYMENT_AUTHORIZED;
                case "transaction-declined" -> NotificationKind.PAYMENT_DECLINED;
                case "transaction-settled" -> NotificationKind.PAYMENT_SETTLED;
                case "transaction-reversed" -> NotificationKind.PAYMENT_REFUNDED;
                default ->
                    throw new IllegalArgumentException(
                            "topic '" + topic + "' is not a payment topic this service notifies on");
            };
        }

        public UUID transactionUuid() {
            if (transactionId == null || transactionId.isBlank()) {
                throw new IllegalArgumentException("transaction event carries no transactionId");
            }
            try {
                return UUID.fromString(transactionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("transactionId is not a UUID: " + transactionId, e);
            }
        }

        public void requireNotifiable() {
            if (ownerSubjectDigest == null || ownerSubjectDigest.isBlank()) {
                throw new IllegalArgumentException("transaction event carries no ownerSubjectDigest");
            }
            if (amount == null || amount.isBlank()) {
                throw new IllegalArgumentException("transaction event carries no amount");
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("transaction event carries no currency");
            }
        }
    }

    /**
     * A {@code fraud-analysis-completed} payload, as published by fraud-service.
     *
     * <p>Only the fields a text needs. The score, band, reasons and facts travel with the event for
     * consumers that assess risk; this service texts the customer, and a text carries the outcome,
     * not the arithmetic.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FraudCompletedPayload(
            String transactionId,
            String ownerSubjectDigest,
            String amount,
            String currency,
            String payeeName,
            String decision,
            String occurredAt) {

        /**
         * The notification kind, or null when no message is warranted.
         *
         * <p>An {@code APPROVE} needs no human action and therefore no message: notifying on every
         * approved payment would be noise at best. Anything unrecognised is refused rather than
         * defaulted, because guessing here would text a customer about a decision the engine never
         * made.
         */
        public NotificationKind kindOrNull() {
            if (decision == null || decision.isBlank()) {
                throw new IllegalArgumentException("fraud-analysis-completed carries no decision");
            }
            return switch (decision) {
                case "APPROVE" -> null;
                case "REVIEW" -> NotificationKind.FRAUD_REVIEW;
                case "DECLINE" -> NotificationKind.FRAUD_DECLINED;
                default ->
                    throw new IllegalArgumentException(
                            "fraud decision '" + decision + "' is not one this service notifies on");
            };
        }

        public UUID transactionUuid() {
            if (transactionId == null || transactionId.isBlank()) {
                throw new IllegalArgumentException("fraud-analysis-completed carries no transactionId");
            }
            try {
                return UUID.fromString(transactionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("transactionId is not a UUID: " + transactionId, e);
            }
        }

        public void requireNotifiable() {
            if (ownerSubjectDigest == null || ownerSubjectDigest.isBlank()) {
                throw new IllegalArgumentException("fraud-analysis-completed carries no ownerSubjectDigest");
            }
            if (amount == null || amount.isBlank()) {
                throw new IllegalArgumentException("fraud-analysis-completed carries no amount");
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("fraud-analysis-completed carries no currency");
            }
        }
    }

    /**
     * A {@code settlement-cycle-finalised} or {@code settlement-break-detected} payload, as published
     * by settlement-service.
     *
     * <p>Every settlement payload carries the cycle's {@code reference} — the human-facing name that
     * appears on the statement — so that is what the email names. The figures travel as decimals for
     * context; the email quotes them as text rather than parsing them, because a notification that
     * fails to parse a figure the settlement service already recorded would be a message about money
     * that refuses to name it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettlementPayload(
            String cycleId,
            String reference,
            String businessDate,
            String currency,
            String expected,
            String actual,
            String status,
            String breakId,
            String kind,
            String detail,
            String eventType) {

        public void requireReference() {
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("settlement event carries no cycle reference");
            }
        }

        /** A human fragment for a break email, or null when the payload names no figures. */
        public String detailOrNull() {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            boolean hasExpected = expected != null && !expected.isBlank();
            boolean hasActual = actual != null && !actual.isBlank();
            if (hasExpected && hasActual) {
                String code = currency == null || currency.isBlank() ? "" : " " + currency;
                return "expected " + expected + code + " but the declared figure was " + actual + code;
            }
            return null;
        }
    }
}
