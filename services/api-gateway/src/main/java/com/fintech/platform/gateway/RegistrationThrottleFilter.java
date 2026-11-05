package com.fintech.platform.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A much tighter budget for anonymous self-registration than the platform-wide limiter.
 *
 * <p>The default limiter allows 100 requests a second per caller, which is right for an
 * authenticated customer and wildly wrong for an endpoint that creates a Keycloak user and a
 * customer record on every call: at that rate one address could create millions of accounts a day
 * and probe which email addresses are already registered. This caps {@code POST /register} at a
 * small number per client address per window.
 *
 * <p>A fixed window in Redis ({@code INCR} then {@code EXPIRE} on first hit) rather than a second
 * {@code RedisRateLimiter} bean: a second {@code RateLimiter}/{@code KeyResolver} bean makes the
 * gateway's own rate-limiter factory ambiguous. Shared across replicas for the same reason the
 * platform limiter is.
 *
 * <p><b>Fails open</b> when Redis is unavailable, exactly like the platform limiter, so a Redis
 * outage degrades protection rather than taking registration down. The client address is the TCP
 * peer; it only reflects {@code X-Forwarded-For} when {@code server.forward-headers-strategy} is
 * enabled, which must only be done behind a proxy that overwrites that header.
 */
@Component
class RegistrationThrottleFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RegistrationThrottleFilter.class);
    private static final String PATH = "/register";
    private static final String KEY_PREFIX = "gateway:registration-throttle:";
    private static final byte[] BODY = """
            {"error":"RATE_LIMITED","message":"Too many registration attempts. Try again later."}""".getBytes(StandardCharsets.UTF_8);

    private final ReactiveStringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration window;

    RegistrationThrottleFilter(
            ReactiveStringRedisTemplate redis,
            @Value("${app.gateway.registration-limit.max-attempts:5}") int maxAttempts,
            @Value("${app.gateway.registration-limit.window-seconds:600}") long windowSeconds) {
        if (maxAttempts < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("registration limit must allow at least 1 attempt per >=1s window");
        }
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())
                || !PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String key = KEY_PREFIX + clientAddress(exchange);
        return redis.opsForValue()
                .increment(key)
                .flatMap(count -> count == 1L ? redis.expire(key, window).thenReturn(count) : Mono.just(count))
                .timeout(Duration.ofMillis(500))
                .onErrorResume(error -> {
                    log.warn(
                            "registration throttle unavailable, allowing request: {}",
                            error.getClass().getSimpleName());
                    return Mono.just(0L);
                })
                .flatMap(count -> count > maxAttempts ? reject(exchange.getResponse()) : chain.filter(exchange));
    }

    private static String clientAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(window.toSeconds()));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(BODY)));
    }

    /** Before routing and before the platform limiter spends a token on a request we will refuse. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
