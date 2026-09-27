package com.fintech.platform.fraud.domain;

/**
 * The four bands a risk score falls into.
 *
 * <p>The boundaries are the ones the platform documents, and they are the reason the score is capped at
 * 100: a band a reader cannot recite is a band that does not get communicated. {@code LOW} through
 * {@code CRITICAL} with {@code 25/50/75} between them means a score can be described in a sentence
 * without looking it up, which is the whole requirement for a number that appears on a screen next to
 * "approve" or "decline".
 *
 * <p>Boundaries are inclusive at the bottom of each band and exclusive at the top, so every integer from
 * 0 to 100 lands in exactly one band and none is left over. {@code 25} is LOW and {@code 26} is MEDIUM,
 * matching the published table rather than rounding {@code 25.5} in some direction nobody wrote down.
 */
public enum RiskBand {

    /** 0 to 25. Nothing fired, or nothing meaningful did. */
    LOW(0, 25),

    /** 26 to 50. Worth recording; not worth an analyst's time. */
    MEDIUM(26, 50),

    /** 51 to 75. An alert is raised and a human is expected to look. */
    HIGH(51, 75),

    /** 76 to 100. The decision is a decline and the payment is expected to be stepped down. */
    CRITICAL(76, 100);

    private final int lowestInclusive;
    private final int highestInclusive;

    RiskBand(int lowestInclusive, int highestInclusive) {
        this.lowestInclusive = lowestInclusive;
        this.highestInclusive = highestInclusive;
    }

    public int lowestInclusive() {
        return lowestInclusive;
    }

    public int highestInclusive() {
        return highestInclusive;
    }

    /**
     * @throws IllegalArgumentException if the score is outside 0..100. Refusing rather than clamping is
     *     deliberate: a score above 100 means the cap in {@link RiskScore} was bypassed, and silently
     *     turning that into {@code CRITICAL} would hide a bug in the arithmetic that produced it.
     */
    public static RiskBand of(int score) {
        for (RiskBand band : values()) {
            if (score >= band.lowestInclusive && score <= band.highestInclusive) {
                return band;
            }
        }
        throw new IllegalArgumentException("a risk score must be between 0 and 100, got " + score);
    }

    /** Whether an alert should be raised for anything in this band. Driven by configuration, not by this. */
    public boolean isAtLeast(RiskBand other) {
        return ordinal() >= other.ordinal();
    }
}
