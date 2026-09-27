package com.fintech.platform.fraud.domain;

/**
 * How many payments this customer has made recently, and whether that number is trustworthy.
 *
 * <p>The {@code available} flag is the important part. Velocity comes from Redis, which is a cache on a
 * remote host, and a fraud rule that treats "I could not ask Redis" as "this customer has made one payment
 * this minute" fails open on the one input an attacker can most easily disrupt. When the count could not be
 * obtained, the engine records that, the velocity rule declines to fire, and the decision's facts say
 * {@code velocityAvailable=false} so a later reader — or a later threshold change — can see what was not
 * checked.
 */
public record VelocitySample(int count, int windowSeconds, boolean available) {

    /** The answer when the counter is unreachable: no count, and {@code available=false}. */
    public static VelocitySample unavailable(int windowSeconds) {
        return new VelocitySample(0, windowSeconds, false);
    }

    public static VelocitySample of(int count, int windowSeconds) {
        return new VelocitySample(count, windowSeconds, true);
    }

    /** Whether this count is above {@code threshold}, which is a comparison and not a rule opinion. */
    public boolean exceeds(int threshold) {
        return available && count > threshold;
    }
}
