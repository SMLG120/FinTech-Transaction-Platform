package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RegistrationThrottleFilterTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);

    private final AtomicBoolean forwarded = new AtomicBoolean();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(true);
        return Mono.empty();
    };
    private RegistrationThrottleFilter filter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        filter = new RegistrationThrottleFilter(redis, 5, 600);
    }

    @Test
    @DisplayName("the first attempt in a window starts the window and is let through")
    void firstAttemptPasses() {
        when(values.increment(anyString())).thenReturn(Mono.just(1L));

        MockServerWebExchange exchange = register();
        filter.filter(exchange, chain).block();

        assertThat(forwarded).isTrue();
        verify(redis).expire("gateway:registration-throttle:203.0.113.7", Duration.ofSeconds(600));
    }

    @Test
    @DisplayName("an attempt past the budget is a 429 and never reaches auth-service")
    void overBudgetIsRefused() {
        when(values.increment(anyString())).thenReturn(Mono.just(6L));

        MockServerWebExchange exchange = register();
        filter.filter(exchange, chain).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("600");
    }

    @Test
    @DisplayName("a Redis outage degrades protection rather than taking registration down")
    void failsOpen() {
        when(values.increment(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

        filter.filter(register(), chain).block();

        assertThat(forwarded).isTrue();
    }

    @Test
    @DisplayName("other routes are not counted at all")
    void otherPathsUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions").remoteAddress(address()));

        filter.filter(exchange, chain).block();

        assertThat(forwarded).isTrue();
        verify(redis, never()).opsForValue();
    }

    private static MockServerWebExchange register() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/register").remoteAddress(address()));
    }

    private static InetSocketAddress address() {
        return new InetSocketAddress("203.0.113.7", 51515);
    }
}
