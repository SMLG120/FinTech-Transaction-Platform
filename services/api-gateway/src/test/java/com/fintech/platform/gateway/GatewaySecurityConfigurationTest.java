package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Locks in the gateway's Phase 1 boundary.
 *
 * <p>Every case here is a regression that was observed rather than a restatement of the
 * configuration file. The Prometheus case exists because Spring Boot's default security chain
 * answers 401 for {@code /actuator/prometheus}, so a correctly configured Prometheus scrapes
 * nothing at all and still reports the target as up.
 *
 * <p>{@link AutoConfigureObservability} is required rather than incidental: Boot's
 * {@code DisableObservabilityContextCustomizer} sets {@code
 * management.defaults.metrics.export.enabled=false} for every {@code @SpringBootTest}, which
 * removes the {@code prometheus} endpoint from the context entirely. Without it this test would
 * pass a 404 and call it a reachable endpoint.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "platform.keycloak.issuer=http://localhost:8180/realms/fintech",
            "platform.keycloak.jwk-set-uri=http://localhost:8180/realms/fintech/protocol/openid-connect/certs",
            "platform.keycloak.audience=fintech-api",
            "platform.security.internal-identity.signing-key=b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801"
        })
@AutoConfigureObservability
class GatewaySecurityConfigurationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void prometheusEndpointIsReachableWithoutATokenAndServesMetrics() {
        webTestClient
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                // A 200 with an empty body would also satisfy isOk(), so assert real series are present.
                .value(body -> assertThat(body).contains("jvm_memory_used_bytes"));
    }

    @Test
    void healthEndpointIsReachableWithoutAToken() {
        // Redis is not running in a unit test, so the aggregate health is DOWN. Either status proves
        // the point of this test, which is reachability without a token; 401 would not.
        webTestClient
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).as("health status").isIn(200, 503))
                .expectBody()
                .jsonPath("$.status")
                .exists();
    }

    @Test
    void applicationTrafficIsRejectedWithUnauthorized() {
        // 401 rather than 403: a missing token has to tell the caller to obtain one.
        webTestClient
                .get()
                .uri("/transactions")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentTypeCompatibleWith("application/json");
    }

    @Test
    void sensitiveActuatorEndpointsStayClosed() {
        // env, configprops, heapdump and loggers disclose configuration and secrets. They are absent
        // from the exposure list, and the security chain rejects the path before routing could serve
        // them, so the answer is 401 rather than a 404 that would read as "no such endpoint".
        webTestClient.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void unauthenticatedRejectionCarriesThePlatformErrorContract() {
        // The 401 body is the same JSON shape every service returns, so a client parses one error
        // format instead of branching on a framework-rendered page.
        webTestClient
                .get()
                .uri("/transactions")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("AUTHENTICATION_REQUIRED");
    }
}
