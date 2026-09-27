package com.fintech.platform.transaction.domain;

import java.util.Locale;

/**
 * Where a payment was initiated from.
 *
 * <p>Collected, not enforced. A channel is one of the facts a fraud rule weighs — a first payment from
 * an ATM is not the same event as the fifth from a phone that day — and it is also the first thing
 * anyone asks for when a customer disputes a payment. Neither of those is a reason to refuse a payment
 * over a missing or unrecognised value, so this is recorded when the client supplies it and left
 * {@code null} when it does not. The fraud engine treats an absent channel as an unknown fact and skips
 * the rules that need it, rather than guessing a default that would then be scored as if it were true.
 *
 * <p>Client-supplied and therefore not evidence of anything. Anyone can put {@code WEB} on a request
 * made from a compromised phone. It is used to make a score more precise, never to relax one: no rule
 * awards a <em>lower</em> score for a reassuring channel.
 */
public enum PaymentChannel {

    /** A browser. */
    WEB,

    /** A native mobile application. */
    MOBILE,

    /** A card-present terminal belonging to a merchant. */
    POS,

    /** A cash machine. */
    ATM,

    /**
     * A server-to-server call from a merchant's own integration.
     *
     * <p>The one channel here where the caller is not the customer, which is why it is named separately
     * rather than folded into {@code API}: a merchant's integration is a different trust relationship,
     * and a rule that treats "somebody's server said so" like "the person in front of the till said
     * so" is a rule that will eventually be wrong about a stolen merchant credential.
     */
    MERCHANT_API;

    /**
     * Parses a channel, or returns {@code null} when the value is absent or unrecognised.
     *
     * <p>Never throws. A payment is not refused because a client sent a channel this build has never
     * heard of — an older client against a newer platform is the normal direction of that mismatch, and
     * turning it into a 400 teaches the caller to stop sending the field at all. The value is simply
     * not recorded, and the fact that it is missing is visible to the fraud engine as a missing fact.
     */
    public static PaymentChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
