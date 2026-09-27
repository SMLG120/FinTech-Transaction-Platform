package com.fintech.platform.fraud.domain;

/**
 * A single number between 0 and 100, with the band it falls into.
 *
 * <p>A type rather than an {@code int} so that a score cannot travel through the system without its band
 * being computed, and cannot be compared against a threshold without both sides agreeing what "above
 * 50" means. The two values are derived together in one constructor, so there is no state in which a
 * score of 80 is labelled MEDIUM.
 *
 * <p><b>The cap is applied here, at construction, and not by the caller.</b> Seven rules that each
 * contribute points can add up to far more than 100, and a score of 160 is not a more severe finding —
 * it is a number no band can hold and no threshold table can be written against. Clamping at the boundary
 * means every consumer downstream can assume 0..100, and the *reasons* list keeps the full detail of what
 * fired, so clamping loses no information an analyst needs.
 */
public record RiskScore(int value, RiskBand band) {

    /** The published range. Outside it there is no band. */
    public static final int MIN = 0;

    public static final int MAX = 100;

    public RiskScore {
        if (value < MIN) {
            throw new IllegalArgumentException("a risk score cannot be negative: " + value);
        }
        if (value > MAX) {
            throw new IllegalArgumentException("a risk score cannot exceed " + MAX + ": " + value);
        }
    }

    /**
     * A score, clamped into range.
     *
     * @param rawPoints the sum of every rule that fired, which may exceed 100
     */
    public static RiskScore of(int rawPoints) {
        int clamped = Math.clamp(rawPoints, MIN, MAX);
        return new RiskScore(clamped, RiskBand.of(clamped));
    }

    /** The score a payment with nothing to say about it gets. */
    public static RiskScore none() {
        return new RiskScore(MIN, RiskBand.LOW);
    }

    public boolean isAtLeast(RiskBand other) {
        return band.isAtLeast(other);
    }

    @Override
    public String toString() {
        return value + "/" + MAX + " (" + band + ")";
    }
}
