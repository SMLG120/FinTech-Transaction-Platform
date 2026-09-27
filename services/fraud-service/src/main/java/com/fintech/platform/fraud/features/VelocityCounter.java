package com.fintech.platform.fraud.features;

import com.fintech.platform.fraud.domain.VelocitySample;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Counts how many payments a customer has made in the last minute.
 *
 * <p><b>The whole operation is one Lua script, and that is not an optimisation.</b> A read, a trim, an
 * add and a count are four round trips, and between any two of them another payment from the same
 * customer can be recorded. The count that comes back would then be of a set that has moved on, and the
 * threshold would be compared against a number that never existed. Redis runs a script to completion
 * without interleaving anything else, so the count returned is the count of the set this payment was
 * added to.
 *
 * <p><b>The payment being scored is included in the count.</b> The sixth payment in a minute is the one
 * that trips a limit of five, so a counter that excluded the current payment would fire on the seventh
 * and the reason line would be one off the number the analyst is looking at. Two payments in the same
 * millisecond share a score and under-count, which fails towards not alerting; the alternative — a
 * fabricated tiebreaker — would over-count on a busy customer's own card being retried.
 *
 * <p><b>Keys carry a subject digest, never a subject.</b> Redis here is shared infrastructure with
 * inspectable keys, backups and a slow-query log, and a customer identifier in a key name is a customer
 * identifier in all three.
 *
 * <p><b>Fails open, and says so.</b> A Redis outage returns {@link VelocitySample#unavailable}, the
 * velocity rule does not fire, a metric counts the skip, and the decision records
 * {@code velocityAvailable=false}. The alternative — treating an unreachable counter as "no payments" or
 * as "too many" — chooses an answer to "how fast is this customer paying" on the basis of Redis's
 * availability, and one of those two choices fails every payment on the platform.
 */
@Component
public class VelocityCounter {

    private static final Logger log = LoggerFactory.getLogger(VelocityCounter.class);

    /**
     * Trim to the window, add this payment, expire, then count.
     *
     * <p>{@code ZREMRANGEBYSCORE} takes the exclusive lower bound, so a payment exactly a window ago is
     * dropped — the window is "the last N seconds", not "N seconds ago onwards".
     *
     * <p>{@code PEXPIRE} is set on every call rather than only when the key is created, so a busy
     * customer's key cannot outlive its window by however long it took them to stop paying.
     */
    private static final String SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZCARD', KEYS[1])
            """;

    /**
     * Built once. {@code DefaultRedisScript} caches its own SHA and the template's script-executing
     * methods hash the source on every call unless given a cached instance, so constructing one per
     * payment would mean re-sending the whole script body for every payment scored.
     */
    private static final RedisScript<Long> SCRIPT_INSTANCE = new DefaultRedisScript<>(SCRIPT, Long.class);

    private final StringRedisTemplate redis;

    private final int windowSeconds;

    private final Clock clock;

    public VelocityCounter(
            StringRedisTemplate redis,
            @Value("${app.fraud.velocity.window-seconds:60}") int windowSeconds,
            Clock clock) {
        this.redis = redis;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    /** The key for a customer's counter. Public so the tests can assert on it. */
    public static String keyFor(String ownerSubjectDigest) {
        return "fraud:velocity:customer:" + ownerSubjectDigest;
    }

    /**
     * Records this payment and returns the count including it.
     *
     * @param paymentId used as the sorted-set member, so a redelivered event does not double-count
     */
    public VelocitySample recordAndCount(String ownerSubjectDigest, UUID paymentId) {
        Instant now = clock.instant();
        long nowMillis = now.toEpochMilli();
        long cutoffMillis = now.minusSeconds(windowSeconds).toEpochMilli();
        try {
            Long count = redis.execute(
                    SCRIPT_INSTANCE,
                    List.of(keyFor(ownerSubjectDigest)),
                    Long.toString(cutoffMillis),
                    Long.toString(nowMillis),
                    paymentId.toString(),
                    Long.toString(windowSeconds * 1000L));
            if (count == null) {
                // A null return is Redis telling us the script did not produce an integer, which is a
                // connection or serialization problem rather than a result. Treated as unavailable,
                // not as zero.
                log.warn("Velocity script returned no count for payment {}", paymentId);
                return VelocitySample.unavailable(windowSeconds);
            }
            return VelocitySample.of(count.intValue(), windowSeconds);
        } catch (RuntimeException e) {
            log.warn("Velocity counter unavailable; continuing without a velocity signal: {}", e.toString());
            return VelocitySample.unavailable(windowSeconds);
        }
    }

    /** The count without recording, for tests and for diagnostics. Never called on the scoring path. */
    public VelocitySample peek(String ownerSubjectDigest) {
        try {
            Long count = redis.opsForZSet().zCard(keyFor(ownerSubjectDigest));
            return VelocitySample.of(count == null ? 0 : count.intValue(), windowSeconds);
        } catch (RuntimeException e) {
            return VelocitySample.unavailable(windowSeconds);
        }
    }
}
