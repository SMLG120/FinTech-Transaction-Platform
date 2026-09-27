package com.fintech.platform.notification.domain;

/**
 * What a notification is about.
 *
 * <p>One kind per fact worth telling somebody about, and the absence that matters most is deliberate:
 * there is no {@code FRAUD_APPROVED}. An approved payment needs no human action, so notifying on every
 * one would be noise at best and a per-payment SMS bill at worst. The engine's approval is visible in
 * fraud-service; this service only speaks when somebody has something to do.
 */
public enum NotificationKind {
    PAYMENT_AUTHORIZED,
    PAYMENT_DECLINED,
    PAYMENT_SETTLED,
    PAYMENT_REFUNDED,
    FRAUD_REVIEW,
    FRAUD_DECLINED,
    SETTLEMENT_CLOSED,
    SETTLEMENT_RECONCILED,
    SETTLEMENT_BREAK;

    /** The channel this kind is delivered on. */
    public NotificationChannel channel() {
        return switch (this) {
            case PAYMENT_AUTHORIZED, PAYMENT_DECLINED, PAYMENT_SETTLED, PAYMENT_REFUNDED -> NotificationChannel.PUSH;
            case FRAUD_REVIEW, FRAUD_DECLINED -> NotificationChannel.SMS;
            case SETTLEMENT_CLOSED, SETTLEMENT_RECONCILED, SETTLEMENT_BREAK -> NotificationChannel.EMAIL;
        };
    }

    /** True for the kinds that name a payment rather than a settlement cycle. */
    public boolean isPaymentKind() {
        return switch (this) {
            case PAYMENT_AUTHORIZED,
                    PAYMENT_DECLINED,
                    PAYMENT_SETTLED,
                    PAYMENT_REFUNDED,
                    FRAUD_REVIEW,
                    FRAUD_DECLINED -> true;
            case SETTLEMENT_CLOSED, SETTLEMENT_RECONCILED, SETTLEMENT_BREAK -> false;
        };
    }
}
