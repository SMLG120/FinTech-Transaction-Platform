package com.fintech.platform.gateway;

import com.fintech.platform.gateway.testsupport.JwtFixture;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Test-only replacements for the two things that would otherwise require a running Keycloak.
 *
 * <p>Imported explicitly rather than declared as a nested class, because a nested configuration that
 * fails to register does not fail loudly: the context comes up with the production beans, every request
 * is refused, and the resulting test failures say nothing about why.
 *
 * <p>What is replaced is the key source and the network hop. The validators come from the shipped
 * {@link GatewaySecurityConfiguration#jwtValidators}, so an issuer or audience regression fails the
 * suite rather than quietly passing it.
 */
// @TestConfiguration, not @Configuration: this class sits in the package the application component-
// scans, and as a plain @Configuration it was picked up by every other test context in the module. Its
// stub filter then answered /actuator/prometheus with {"reached":"downstream"} and two unrelated tests
// failed in a way that pointed nowhere near the cause. @TestConfiguration is excluded from scanning
// and is still registered when explicitly @Imported, which is exactly what is needed here.
@TestConfiguration
class GatewayTestConfiguration {

    /**
     * One key pair for the whole context. The test and the decoder must agree on it, so it is exposed
     * as a bean rather than each side generating its own.
     */
    @Bean
    JwtFixture jwtFixture() {
        return JwtFixture.create();
    }

    /**
     * Named distinctly from the production bean. Two beans sharing the name {@code jwtDecoder} collide,
     * and with bean overriding disabled the loser is a silent no-op — here meaning the chain would use a
     * decoder that cannot reach a JWKS and refuse every token, with an error message pointing nowhere
     * near the real cause.
     */
    @Bean
    @Primary
    ReactiveJwtDecoder testJwtDecoder(KeycloakProperties properties, JwtFixture tokens) {
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withPublicKey(tokens.publicKey()).build();
        decoder.setJwtValidator(GatewaySecurityConfiguration.jwtValidators(properties));
        return decoder;
    }

    /**
     * Stands in for the downstream service.
     *
     * <p>Ordered just after Spring Security's filter chain, so reaching it at all proves the policy
     * allowed the call. That is what lets the authorisation matrix assert an exact 200 instead of the
     * much weaker "not refused", which a route matching nothing would also satisfy.
     */
    @Bean
    DownstreamStub downstreamStub() {
        return new DownstreamStub();
    }

    /** Spring Security's filter chain is ordered at -100, so -90 runs only for requests it let through. */
    static final class DownstreamStub implements WebFilter, Ordered {

        @Override
        public int getOrder() {
            return -90;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            byte[] body = "{\"reached\":\"downstream\"}".getBytes(StandardCharsets.UTF_8);
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        }
    }

    /**
     * Stands in for the Redis rate limiter, which needs a Redis no test provides.
     *
     * <p>A {@code @Primary} controllable stub rather than a mock: the filter calls {@code
     * isAllowed} for real, through the real route configuration, so a miswired filter name or
     * key-resolver reference fails the suite instead of a wiring assertion. Deny is off unless a
     * test asks for it, and the asking test resets it — a leaked denial would 429 every suite
     * that runs after it in the fork.
     */
    @Bean
    @Primary
    ControllableRateLimiter testRateLimiter() {
        return new ControllableRateLimiter();
    }

    static final class ControllableRateLimiter
            implements org.springframework.cloud.gateway.filter.ratelimit.RateLimiter<Object> {

        private volatile boolean deny = false;

        void denyAll() {
            deny = true;
        }

        void allowAll() {
            deny = false;
        }

        @Override
        public Mono<org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response> isAllowed(
                String routeId, String id) {
            return Mono.just(
                    new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(!deny, Map.of()));
        }

        @Override
        public Map<String, Object> getConfig() {
            return Map.of();
        }

        @Override
        public Class<Object> getConfigClass() {
            return Object.class;
        }

        @Override
        public Object newConfig() {
            return Map.of();
        }
    }
}
