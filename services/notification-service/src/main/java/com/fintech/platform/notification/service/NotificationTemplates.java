package com.fintech.platform.notification.service;

import com.fintech.platform.notification.domain.Money;
import com.fintech.platform.notification.domain.NotificationKind;
import java.util.Currency;

/**
 * The words in every message this service sends.
 *
 * <p>Kept as pure functions, because the wording is the contract a support agent reads back to a
 * caller: it has to be assertable without a database, a broker or a clock. Amounts render from minor
 * units with the currency code in front ({@code GBP 12.00}) rather than a locale symbol, so a message
 * cannot confuse five thousand pounds with five hundred thousand cents the way a bare number can.
 *
 * <p>The payee name is customer-supplied display text and is rendered as text. It is never treated as
 * an identity, and no template interpolates a card token, a device reference or a subject — the
 * message must be safe to read back over a support call.
 */
public final class NotificationTemplates {

    private NotificationTemplates() {}

    /** A rendered subject and body. */
    public record Rendered(String subject, String body) {}

    public static Rendered payment(NotificationKind kind, String amountMinor, String currencyCode, String payee) {
        String money = moneyOf(toMinor(amountMinor, currencyCode), currencyCode);
        String who = payee == null || payee.isBlank() ? "your payee" : payee;
        return switch (kind) {
            case PAYMENT_AUTHORIZED ->
                new Rendered("Payment authorised", "Your payment of " + money + " to " + who + " was authorised.");
            case PAYMENT_DECLINED ->
                new Rendered(
                        "Payment declined",
                        "Your payment of " + money + " to " + who + " was declined. No money has left your account.");
            case PAYMENT_SETTLED ->
                new Rendered("Payment completed", "Your payment of " + money + " to " + who + " has completed.");
            case PAYMENT_REFUNDED ->
                new Rendered("Payment refunded", "Your payment of " + money + " to " + who + " was refunded.");
            default -> throw new IllegalArgumentException(kind + " is not a payment kind");
        };
    }

    public static Rendered fraud(NotificationKind kind, String amountMinor, String currencyCode, String payee) {
        String money = moneyOf(toMinor(amountMinor, currencyCode), currencyCode);
        String who = payee == null || payee.isBlank() ? "your payee" : payee;
        return switch (kind) {
            case FRAUD_REVIEW ->
                new Rendered(
                        "Check your recent payment",
                        "We noticed something unusual about your payment of " + money + " to " + who
                                + ". If this was not you, contact support.");
            case FRAUD_DECLINED ->
                new Rendered(
                        "Payment stopped for your protection",
                        "Your payment of " + money + " to " + who
                                + " was stopped because it looked suspicious. Contact support if this was you.");
            default -> throw new IllegalArgumentException(kind + " is not a fraud kind");
        };
    }

    public static Rendered settlement(NotificationKind kind, String cycleReference, String detail) {
        return switch (kind) {
            case SETTLEMENT_CLOSED ->
                new Rendered(
                        "Settlement period closed",
                        "Settlement period " + cycleReference + " is closed and its statement is final.");
            case SETTLEMENT_RECONCILED ->
                new Rendered(
                        "Settlement period reconciled",
                        "Settlement period " + cycleReference + " reconciled against the declared figure.");
            case SETTLEMENT_BREAK ->
                new Rendered(
                        "Settlement break needs attention",
                        "Settlement period " + cycleReference + " has a reconciliation break"
                                + (detail == null || detail.isBlank() ? "." : ": " + detail));
            default -> throw new IllegalArgumentException(kind + " is not a settlement kind");
        };
    }

    /**
     * Minor units to a {@code CODE major.minor} string.
     *
     * <p>Rendered from the stored minor-unit count rather than from the decimal string the event
     * carried, so the figure in the message is the figure this service actually recorded. A stored
     * {@code 5000} in GBP is {@code GBP 50.00}, full stop.
     */
    static String moneyOf(long amountMinor, String currencyCode) {
        if (currencyCode == null) {
            throw new IllegalArgumentException("a payment message needs a currency");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + currencyCode + "' is not an ISO 4217 currency code", e);
        }
        return currencyCode + " " + new Money(amountMinor, currency).toDecimalString();
    }

    /** Decimal strings to minor units, for callers that still hold the wire form. */
    static long toMinor(String decimal, String currencyCode) {
        if (decimal == null || currencyCode == null) {
            throw new IllegalArgumentException("a payment message needs an amount and a currency");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + currencyCode + "' is not an ISO 4217 currency code", e);
        }
        return Money.parse(decimal, currency).minorUnits();
    }
}
