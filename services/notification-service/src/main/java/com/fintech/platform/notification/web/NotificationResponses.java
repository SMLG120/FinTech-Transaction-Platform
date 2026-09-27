package com.fintech.platform.notification.web;

import com.fintech.platform.notification.domain.Money;
import com.fintech.platform.notification.persistence.NotificationEntity;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * What the notification API returns.
 *
 * <p>Amounts are rendered as decimal strings, not numbers. The same reason transaction-service,
 * fraud-service and settlement-service do it: JSON numbers are doubles to most consumers, and a
 * figure that arrives as {@code 12.00} on one side of a support call and {@code 11.999999999} on the
 * other is a discrepancy nobody can account for.
 *
 * <p>The recipient is absent, deliberately. The row holds an owner digest — the same pseudonym
 * fraud-service holds — and a digest in a support response is a customer list entry wearing a thin
 * disguise: it joins across the two databases for anyone who can read both. A support agent answering
 * "was the customer told" needs the message and its state, not the join key. The transaction id and
 * the cycle reference stay, because they are how the agent finds the payment or the period the message
 * is about.
 */
public final class NotificationResponses {

    private NotificationResponses() {}

    /** One message the platform decided to send, and whether it went out. */
    public record NotificationView(
            UUID id,
            UUID eventId,
            String topic,
            String kind,
            String channel,
            String status,
            UUID transactionId,
            String cycleReference,
            String amount,
            String currency,
            String payeeName,
            String subject,
            String body,
            int attempts,
            String lastError,
            Instant nextAttemptAt,
            Instant sentAt,
            Instant createdAt) {

        /**
         * Renders a notification for support.
         *
         * @param notification the row
         * @return the view
         */
        public static NotificationView of(NotificationEntity notification) {
            return new NotificationView(
                    notification.getId(),
                    notification.getEventId(),
                    notification.getTopic(),
                    notification.getKind().name(),
                    notification.getChannel().name(),
                    notification.getStatus().name(),
                    notification.getTransactionId(),
                    notification.getCycleReference(),
                    amountOf(notification),
                    notification.getCurrencyCode(),
                    notification.getPayeeName(),
                    notification.getSubject(),
                    notification.getBody(),
                    notification.getAttempts(),
                    notification.getLastError(),
                    notification.getNextAttemptAt(),
                    notification.getSentAt(),
                    notification.getCreatedAt());
        }

        /**
         * The stored minor-unit count rendered in its currency, or null when the notification is
         * about a settlement cycle rather than a payment.
         *
         * <p>Rendered from the stored count rather than from the decimal string the event carried,
         * so the figure in the response is the figure this service actually recorded.
         */
        private static String amountOf(NotificationEntity notification) {
            if (notification.getAmountMinor() == null || notification.getCurrencyCode() == null) {
                return null;
            }
            Currency currency = Currency.getInstance(notification.getCurrencyCode());
            return new Money(notification.getAmountMinor(), currency).toDecimalString();
        }
    }
}
