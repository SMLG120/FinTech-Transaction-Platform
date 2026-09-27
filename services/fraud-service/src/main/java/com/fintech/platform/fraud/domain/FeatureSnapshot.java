package com.fintech.platform.fraud.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The counters and last-seen times the engine looked up before scoring a payment.
 *
 * <p>Every field is either a fact, a time, or a <b>nullable</b> fact, and the nulls are the design. They
 * mean "this could not be established", and a rule that needs one of them must not fire — an absent fact
 * is not a negative fact. {@code merchantSeen=false} is a real signal ("we have never seen this merchant
 * before"), whereas {@code merchantSeen=null} would mean the merchant observation could not be read, and
 * treating that as "never seen" would make a database hiccup look like every merchant on the platform was
 * new.
 *
 * <p>So the booleans that can be unknown are boxed. The ones that cannot are not: {@code otherCustomersOnDevice}
 * is an {@code int} because either the device was seen only by this customer, or the query returned a
 * count, and there is no third state — a device that was never seen is simply a count of zero.
 *
 * <p><b>Nothing here is a live lookup.</b> The snapshot is read once, before any rule runs, and passed to
 * all seven. Re-reading inside a rule would let the same payment be scored against two different values of
 * the same counter if a concurrent payment landed in between, and the decision would be a chimera of two
 * moments.
 */
public record FeatureSnapshot(
        VelocitySample velocity,
        Boolean deviceSeenOnCard,
        Boolean deviceSeenByCustomer,
        Boolean merchantSeen,
        int otherCustomersOnDevice,
        Instant cardFirstSeenAt,
        Instant lastSeenAt,
        Instant lastNetworkChangedAt,
        Instant evaluatedAt) {

    public FeatureSnapshot {
        Objects.requireNonNull(velocity, "velocity must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        if (otherCustomersOnDevice < 0) {
            throw new IllegalArgumentException("otherCustomersOnDevice must not be negative");
        }
    }

    /** A snapshot for a payment with nothing else known about it, used only in tests and degraded mode. */
    public static FeatureSnapshot empty(Instant evaluatedAt, int velocityWindowSeconds) {
        return new FeatureSnapshot(
                VelocitySample.of(0, velocityWindowSeconds), null, null, null, 0, null, null, null, evaluatedAt);
    }

    /**
     * When the customer's network last changed, or empty if it has never changed.
     *
     * <p>Derived from the observation rows rather than stored directly: it is the {@code first_seen_at} of
     * the most recent network that is not the one being used now. A network that is still the one in use
     * says nothing about a change, which is why this is a "when did it change" and not a "when was it
     * last seen" — the latter is true for every payment a customer ever makes and so would make the
     * rapid-change rule fire constantly.
     */
    public boolean hasRecentNetworkChange(Duration window) {
        if (lastNetworkChangedAt == null) {
            return false;
        }
        Duration since = Duration.between(lastNetworkChangedAt, evaluatedAt);
        return !since.isNegative() && since.compareTo(window) <= 0;
    }

    /** How long ago this customer's last observed payment was, or empty if there was none. */
    public java.util.Optional<Duration> sinceLastSeen() {
        if (lastSeenAt == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Duration.between(lastSeenAt, evaluatedAt));
    }
}
