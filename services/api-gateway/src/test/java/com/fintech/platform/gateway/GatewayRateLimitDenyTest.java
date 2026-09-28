package com.fintech.platform.gateway;

import com.fintech.platform.gateway.testsupport.JwtFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The denial half of the rate limiter, against real Redis and the real routes.
 *
 * <p>Separate context from {@code GatewayRateLimitTest} on purpose, twice over: that suite's
 * downstream stub answers every request before the route filters run, so a denial there would
 * never execute — and its limiter stub stands in for Redis, so it cannot prove the real one
 * denies. Here the bucket holds exactly one token: the first authorised call passes the
 * limiter (and then fails downstream, where nothing listens), and the immediate second call
 * is 429 before routing. An unauthenticated call is still 401 first, proving authentication
 * runs before throttling.
 */
@Testcontainers
@AutoConfigureWebTestClient
@Import(GatewayRateLimitDenyTest.DenyConfiguration.class)
@SpringBootTest(
        classes = ApiGatewayApplication.class,
        properties = {
            "platform.keycloak.issuer=http://localhost:8180/realms/fintech",
            "platform.keycloak.jwk-set-uri=http://localhost:8180/realms/fintech/protocol/openid-connect/certs",
            "platform.keycloak.audience=fintech-api",
            "platform.security.internal-identity.signing-key=b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801",
            "app.gateway.rate-limit.replenish-per-second=1",
            "app.gateway.rate-limit.burst=1"
        })
class GatewayRateLimitDenyTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.6.7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class DenyConfiguration {

        @Bean
        JwtFixture jwtFixture() {
            return JwtFixture.create();
        }

        @Bean
        @Primary
        ReactiveJwtDecoder testJwtDecoder(KeycloakProperties properties, JwtFixture tokens) {
            NimbusReactiveJwtDecoder decoder =
                    NimbusReactiveJwtDecoder.withPublicKey(tokens.publicKey()).build();
            decoder.setJwtValidator(GatewaySecurityConfiguration.jwtValidators(properties));
            return decoder;
        }
    }

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private JwtFixture tokens;

    @Test
    @DisplayName("the second immediate call is 429 while the first passes the limiter")
    void second_immediate_call_is_429() {
        String token = tokens.tokenFor("CUSTOMER", "fintech-api");

        // Spends the single token, then fails downstream where nothing listens —
        // which is the point: it got past the limiter.
        webClient
                .get()
                .uri("/api/v1/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .is5xxServerError();

        webClient
                .get()
                .uri("/api/v1/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isEqualTo(429);
    }

    @Test
    @DisplayName("a caller with no token is still 401 first, not 429")
    void missing_token_is_still_401() {
        webClient.get().uri("/api/v1/customers/me").exchange().expectStatus().isUnauthorized();
    }
}
