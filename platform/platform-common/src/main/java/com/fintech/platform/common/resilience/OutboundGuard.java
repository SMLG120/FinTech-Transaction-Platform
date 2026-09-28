package com.fintech.platform.common.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * A circuit breaker with a bounded retry around one downstream call.
 *
 * <p>Timeouts bound how long one attempt takes; this bounds how many attempts the platform keeps
 * making against a dependency that has stopped answering. Without it a stalled downstream holds a
 * request thread per caller for the full timeout each, and the pool exhausts on requests that can
 * never be answered — one slow dependency becomes a fully unavailable service.
 *
 * <p>Three rules, each load-bearing:
 *
 * <ul>
 *   <li><b>Retry is outer, breaker is inner.</b> Each attempt is recorded by the breaker, so a
 *       downed dependency trips it in roughly half the calls the window size suggests. A retry
 *       budget of one extra attempt keeps the added latency bounded.
 *   <li><b>Only transport failures and 5xx count.</b> A 4xx from the downstream is a definitive
 *       answer ("not yours", "no such payment"), not an outage: it is neither retried nor
 *       recorded, so a caller hammering a wrong id cannot trip the breaker for everyone else.
 *   <li><b>An open breaker speaks the caller's vocabulary.</b> {@code CallNotPermittedException}
 *       is never retried and never leaks: the caller supplies the fail-closed exception, so an
 *       open breaker and a down dependency are indistinguishable downstream — same code, same
 *       503, same retry-later meaning.
 * </ul>
 *
 * <p>Programmatic rather than annotated, because the annotations elsewhere in this platform are
 * inert and a rule expressed only as an annotation is a rule that is not enforced. The wiring —
 * which exceptions count, what the fallback says — is visible in this file rather than in
 * aspect ordering.
 */
public final class OutboundGuard {

    /** Tunables with validation, bound from properties at the call site. */
    public record Settings(
            float failureRateThreshold,
            int slidingWindowSize,
            Duration openWait,
            int halfOpenCalls,
            int maxAttempts,
            Duration retryWait) {

        public Settings {
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("failureRateThreshold must be in (0, 100]");
            }
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException("slidingWindowSize must be >= 1");
            }
            Objects.requireNonNull(openWait, "openWait must not be null");
            if (openWait.isNegative() || openWait.isZero()) {
                throw new IllegalArgumentException("openWait must be positive");
            }
            if (halfOpenCalls < 1) {
                throw new IllegalArgumentException("halfOpenCalls must be >= 1");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            Objects.requireNonNull(retryWait, "retryWait must not be null");
            if (retryWait.isNegative()) {
                throw new IllegalArgumentException("retryWait must not be negative");
            }
        }
    }

    private final CircuitBreaker breaker;
    private final Retry retry;

    private OutboundGuard(CircuitBreaker breaker, Retry retry) {
        this.breaker = breaker;
        this.retry = retry;
    }

    /**
     * Builds a named guard and wires its counters.
     *
     * @param name identifies the downstream in metrics (e.g. {@code customer-eligibility})
     * @param settings thresholds and budgets
     * @param meters the service registry; counters are {@code outbound.guard.*} tagged by guard
     */
    public static OutboundGuard of(String name, Settings settings, MeterRegistry meters) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(meters, "meters must not be null");

        CircuitBreaker breaker = CircuitBreaker.of(
                name,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(settings.failureRateThreshold())
                        .slidingWindowSize(settings.slidingWindowSize())
                        // Every full window is evaluated: the default minimum (100) would
                        // otherwise let a small window fill without ever being judged.
                        .minimumNumberOfCalls(settings.slidingWindowSize())
                        .waitDurationInOpenState(settings.openWait())
                        .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                        .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                        .ignoreExceptions(HttpClientErrorException.class)
                        .build());

        Retry retry = Retry.of(
                name,
                RetryConfig.custom()
                        .maxAttempts(settings.maxAttempts())
                        .waitDuration(settings.retryWait())
                        .retryOnException(OutboundGuard::isRetryable)
                        .ignoreExceptions(CallNotPermittedException.class)
                        .build());

        Counter success = Counter.builder("outbound.guard.calls")
                .tag("guard", name)
                .tag("result", "success")
                .description("Guarded downstream calls that answered")
                .register(meters);
        Counter failure = Counter.builder("outbound.guard.calls")
                .tag("guard", name)
                .tag("result", "failure")
                .description("Guarded downstream calls that failed after retries")
                .register(meters);
        Counter rejected = Counter.builder("outbound.guard.calls")
                .tag("guard", name)
                .tag("result", "rejected")
                .description("Calls refused by an open breaker")
                .register(meters);
        Counter retried = Counter.builder("outbound.guard.retries")
                .tag("guard", name)
                .description("Retry attempts against the downstream")
                .register(meters);

        breaker.getEventPublisher().onSuccess(event -> success.increment());
        breaker.getEventPublisher().onError(event -> failure.increment());
        breaker.getEventPublisher().onCallNotPermitted(event -> rejected.increment());
        retry.getEventPublisher().onRetry(event -> retried.increment());

        return new OutboundGuard(breaker, retry);
    }

    /**
     * Runs the call under retry-then-breaker, translating an open breaker into the caller's own
     * fail-closed exception.
     */
    public <T> T execute(Supplier<T> call, Supplier<? extends RuntimeException> onOpen) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(onOpen, "onOpen must not be null");
        try {
            return retry.executeSupplier(() -> breaker.executeSupplier(call::get));
        } catch (CallNotPermittedException e) {
            throw onOpen.get();
        }
    }

    /** Current state name, for health detail and tests. */
    public String state() {
        return breaker.getState().name();
    }

    /**
     * What may be retried: a call that never got an answer (connect/read timeouts surface as
     * {@code ResourceAccessException}) or a 5xx the next attempt may not repeat. A 4xx is a
     * definitive answer and is never retried: retrying a refused request turns one "no" into
     * several, and a retry that eventually succeeds on a definitive refusal would be worse.
     */
    private static boolean isRetryable(Throwable error) {
        return error instanceof ResourceAccessException || error instanceof HttpServerErrorException;
    }
}
