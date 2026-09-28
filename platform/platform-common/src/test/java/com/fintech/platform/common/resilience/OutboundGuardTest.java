package com.fintech.platform.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The breaker-plus-retry discipline, without a downstream.
 *
 * <p>Each rule in {@link OutboundGuard} exists to keep one failure mode from becoming another:
 * a downed dependency must fail fast rather than pool-exhaust the caller, a definitive refusal
 * must never trip the breaker for everyone else, and an open breaker must speak the caller's
 * own fail-closed vocabulary rather than leak a library exception.
 */
class OutboundGuardTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();

    private OutboundGuard guard() {
        return OutboundGuard.of(
                "test", new OutboundGuard.Settings(50, 2, Duration.ofMinutes(1), 1, 1, Duration.ZERO), meters);
    }

    private static ResourceAccessException timeout() {
        return new ResourceAccessException("timed out", new SocketTimeoutException());
    }

    private static HttpClientErrorException forbidden() {
        return new HttpClientErrorException(HttpStatus.FORBIDDEN, "Forbidden") {};
    }

    private static HttpServerErrorException badGateway() {
        return new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway") {};
    }

    private static RuntimeException failClosed() {
        return new IllegalStateException("downstream unavailable");
    }

    @Test
    @DisplayName("passes a healthy call through untouched")
    void passesHealthyCallThrough() {
        assertThat(guard().execute(() -> "ok", OutboundGuardTest::failClosed)).isEqualTo("ok");
    }

    @Test
    @DisplayName("opens after consecutive transport failures and refuses without calling")
    void opensAndFailsFast() {
        OutboundGuard guard = guard();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.execute(
                        () -> {
                            calls.incrementAndGet();
                            throw timeout();
                        },
                        OutboundGuardTest::failClosed))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> guard.execute(
                        () -> {
                            calls.incrementAndGet();
                            throw timeout();
                        },
                        OutboundGuardTest::failClosed))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(guard.state()).isEqualTo("OPEN");

        // Fail fast: the supplier is not even invoked, and the caller gets its own
        // fail-closed exception rather than the breaker's.
        assertThatThrownBy(() -> guard.execute(
                        () -> {
                            calls.incrementAndGet();
                            return "never";
                        },
                        OutboundGuardTest::failClosed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream unavailable");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("ignores definitive 4xx answers when deciding to open")
    void ignoresDefinitiveRefusals() {
        OutboundGuard guard = guard();

        // A caller hammering a wrong id gets 403s; those are answers, not an outage,
        // and must never trip the breaker for everyone else.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> guard.execute(
                            () -> {
                                throw forbidden();
                            },
                            OutboundGuardTest::failClosed))
                    .isInstanceOf(HttpClientErrorException.class);
        }

        assertThat(guard.state()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("retries a 5xx once and returns the recovery")
    void retriesServerErrors() {
        OutboundGuard retrying = OutboundGuard.of(
                "retrying", new OutboundGuard.Settings(50, 10, Duration.ofMinutes(1), 1, 2, Duration.ZERO), meters);
        AtomicInteger calls = new AtomicInteger();

        String result = retrying.execute(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw badGateway();
                    }
                    return "recovered";
                },
                OutboundGuardTest::failClosed);

        assertThat(result).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("does not retry a definitive refusal")
    void doesNotRetryRefusals() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard().execute(
                                () -> {
                                    calls.incrementAndGet();
                                    throw forbidden();
                                },
                                OutboundGuardTest::failClosed))
                .isInstanceOf(HttpClientErrorException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("closes again after a successful half-open probe")
    void recoversHalfOpen() throws InterruptedException {
        OutboundGuard guard = OutboundGuard.of(
                "recovering", new OutboundGuard.Settings(50, 2, Duration.ofMillis(50), 1, 1, Duration.ZERO), meters);

        assertThatThrownBy(() -> guard.execute(
                        () -> {
                            throw timeout();
                        },
                        OutboundGuardTest::failClosed))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> guard.execute(
                        () -> {
                            throw timeout();
                        },
                        OutboundGuardTest::failClosed))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(guard.state()).isEqualTo("OPEN");

        Thread.sleep(100);

        assertThat(guard.execute(() -> "back", OutboundGuardTest::failClosed)).isEqualTo("back");
        assertThat(guard.state()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("counts calls, failures and rejections by guard")
    void countsOutcomes() {
        OutboundGuard guard = guard();

        guard.execute(() -> "ok", OutboundGuardTest::failClosed);
        assertThatThrownBy(() -> guard.execute(
                        () -> {
                            throw timeout();
                        },
                        OutboundGuardTest::failClosed))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(meters.get("outbound.guard.calls")
                        .tags("guard", "test", "result", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("outbound.guard.calls")
                        .tags("guard", "test", "result", "failure")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejects incoherent settings rather than running misconfigured")
    void validatesSettings() {
        assertThatThrownBy(() -> new OutboundGuard.Settings(0, 2, Duration.ofSeconds(30), 1, 2, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundGuard.Settings(50, 0, Duration.ofSeconds(30), 1, 2, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundGuard.Settings(50, 2, Duration.ofSeconds(30), 1, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
