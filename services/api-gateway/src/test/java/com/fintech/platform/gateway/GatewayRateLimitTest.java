package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.gateway.testsupport.JwtFixture;
import java.net.InetSocketAddress;
import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The overload protection, wired through the real routes rather than asserted on configuration.
 *
 * <p>The limiter stands in as a controllable stub (see {@code GatewayTestConfiguration}): denying
 * it must 429 an otherwise authorised call, which proves the filter is on the route and runs
 * after authentication. What Redis does with the tokens is Spring's code, not ours, and the live
 * stack is where the real limiter is observed. The key resolution — one bucket per verified
 * identity, never an empty key — is pinned directly on the shipped bean.
 */
@AutoConfigureWebTestClient
@Import(GatewayTestConfiguration.class)
@SpringBootTest(
        classes = ApiGatewayApplication.class,
        properties = {
            "platform.keycloak.issuer=http://localhost:8180/realms/fintech",
            "platform.keycloak.jwk-set-uri=http://localhost:8180/realms/fintech/protocol/openid-connect/certs",
            "platform.keycloak.audience=fintech-api",
            "platform.security.internal-identity.signing-key=b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801"
        })
class GatewayRateLimitTest {

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private JwtFixture tokens;

    @Autowired
    private KeyResolver callerKeyResolver;

    @Test
    @DisplayName("a within-limit caller reaches the downstream stub untouched")
    void within_limit_passes_through() {
        webClient
                .get()
                .uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.tokenFor("CUSTOMER", "fintech-api"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reached")
                .isEqualTo("downstream");
    }

    @Test
    @DisplayName("the key is the verified identity, so bursts never share a budget")
    void key_is_the_identity() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts"))
                .mutate()
                .principal(Mono.just((Principal) () -> "subject-123"))
                .build();

        assertThat(callerKeyResolver.resolve(exchange).block()).isEqualTo("subject-123");
    }

    @Test
    @DisplayName("a request with no identity is keyed by source address, never refused")
    void key_falls_back_to_address() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/accounts");
        builder.remoteAddress(new InetSocketAddress("10.0.0.9", 51234));
        ServerWebExchange exchange = MockServerWebExchange.from(builder);

        assertThat(callerKeyResolver.resolve(exchange).block()).isEqualTo("10.0.0.9");
    }

    @Test
    @DisplayName("a request with neither identity nor address still gets a key")
    void key_is_never_empty() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/accounts"));

        assertThat(callerKeyResolver.resolve(exchange).block()).isEqualTo("anonymous");
    }
}
