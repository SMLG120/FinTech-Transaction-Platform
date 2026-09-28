package com.fintech.platform.gateway;

import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * The gateway's overload protection.
 *
 * <p>One aggressive client gets full gateway throughput without this, and the service with the
 * smallest pool pays for it. The limiter is keyed on the authenticated identity — the JWT subject
 * the security chain already verified — so one caller's burst never spends another's budget, and
 * an auditor reading the trail cannot be crowded out by a client hammering payments. Requests
 * with no identity (preflights, probes) share a bucket by source address: bounded, and never
 * empty, so a missing principal is throttled rather than refused — refusing it would break CORS
 * preflights, which carry no credentials by specification.
 *
 * <p>Redis-backed rather than in-memory, because the gateway runs more than one replica anywhere
 * past a laptop: a per-instance bucket is a limit that multiplies with the replica count. Redis
 * is already the platform's shared state, so this adds no new infrastructure.
 *
 * <p>A denial is 429 with the framework's {@code X-RateLimit-*} headers and deliberately no
 * {@code Retry-After}: tokens refill continuously at the configured rate, so any fixed delay
 * would be a fiction. The rate itself is public configuration, which is the honest retry advice.
 */
@Configuration
class RateLimitConfiguration {

    /**
     * The identity bucket, falling back to the source address when there is no principal.
     *
     * <p>Never empty: an empty key with the filter's default deny-empty-key would refuse
     * preflights, and preflights carry no credentials by specification rather than by omission.
     */
    @Bean
    KeyResolver callerKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                        .map(address -> address.getAddress().getHostAddress())
                        .defaultIfEmpty("anonymous"));
    }

    /**
     * Tokens per second and burst size, from the environment with local-dev defaults.
     *
     * <p>Generous on purpose for a loopback stack: this guards against accidental floods and
     * runaway retries, not against a determined attacker — that is the ingress's job in
     * production. Tune per route when one route's legitimate burst is known.
     */
    @Bean
    RedisRateLimiter platformRateLimiter(
            @Value("${app.gateway.rate-limit.replenish-per-second:100}") int replenishPerSecond,
            @Value("${app.gateway.rate-limit.burst:200}") int burst) {
        return new RedisRateLimiter(replenishPerSecond, burst);
    }
}
