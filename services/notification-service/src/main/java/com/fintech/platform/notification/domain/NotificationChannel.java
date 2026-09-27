package com.fintech.platform.notification.domain;

/**
 * The simulated delivery channel of a notification.
 *
 * <p>No real provider is ever called: the sender logs the rendered message and records the delivery.
 * The channel still matters, because it is the routing decision — a payment confirmation is a push, a
 * fraud review is a text, a statement is an email — and a routing decision that is wrong is caught here
 * rather than in a provider integration that does not exist.
 */
public enum NotificationChannel {
    EMAIL,
    SMS,
    PUSH
}
