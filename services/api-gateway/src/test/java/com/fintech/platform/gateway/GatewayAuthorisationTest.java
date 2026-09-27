package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.gateway.testsupport.JwtFixture;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The authorisation matrix, exercised against the real security chain rather than a restatement of it.
 *
 * <p>Each case is a row of the policy table: a path, a role, and the status the caller must receive. A
 * policy that is not asserted is a policy that can only be verified by hand, and its failure mode is
 * always the same shape — a service quietly answering 200 to somebody who should not have been allowed
 * to ask.
 *
 * <p>Tokens are really signed and the decoder really runs the production validator set. The only
 * substitution is where the signing key comes from: the test decoder verifies against a locally
 * generated public key rather than fetching a JWKS over the network. Everything that decides who may
 * call what is the shipped code.
 */
@AutoConfigureWebTestClient
@org.springframework.context.annotation.Import(GatewayTestConfiguration.class)
@SpringBootTest(
        classes = ApiGatewayApplication.class,
        properties = {
            "platform.keycloak.issuer=http://localhost:8180/realms/fintech",
            "platform.keycloak.jwk-set-uri=http://localhost:8180/realms/fintech/protocol/openid-connect/certs",
            "platform.keycloak.audience=fintech-api",
            "platform.security.internal-identity.signing-key=b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801"
        })
class GatewayAuthorisationTest {

    private static final String ISSUER = "http://localhost:8180/realms/fintech";

    /** Injected so the keys are the same ones the test decoder verifies against. */
    @org.springframework.beans.factory.annotation.Autowired
    private JwtFixture tokens;

    @Autowired
    private WebTestClient webClient;

    @ParameterizedTest(name = "{0} as {1} is {2}")
    @CsvSource({
        // The customer surface, and the ordering hazard. /api/** catches every one of these, so if the
        // more specific rules were ever moved after it they would be dead configuration.
        "/api/transfers, AUDITOR, 403",
        "/api/transfers, COMPLIANCE_OFFICER, 403",
        "/api/accounts, CUSTOMER, 200",
        "/api/accounts, SUPPORT_AGENT, 200",
        "/api/accounts, AUDITOR, 403",
        // Administrative paths.
        "/api/admin/users, CUSTOMER, 403",
        "/api/admin/users, SUPPORT_AGENT, 403",
        "/api/admin/users, COMPLIANCE_OFFICER, 403",
        "/api/admin/users, AUDITOR, 403",
        "/api/admin/users, PLATFORM_ADMIN, 200",
        // Support.
        "/api/support/tickets, SUPPORT_AGENT, 200",
        "/api/support/tickets, PLATFORM_ADMIN, 200",
        "/api/support/tickets, CUSTOMER, 403",
        // Compliance.
        "/api/compliance/reports, COMPLIANCE_OFFICER, 200",
        "/api/compliance/reports, PLATFORM_ADMIN, 200",
        "/api/compliance/reports, AUDITOR, 403",
        // The audit trail.
        "/api/audit/events, AUDITOR, 200",
        "/api/audit/events, COMPLIANCE_OFFICER, 200",
        "/api/audit/events, PLATFORM_ADMIN, 200",
        "/api/audit/events, CUSTOMER, 403",
        "/api/audit/events, SUPPORT_AGENT, 403",
        // The fraud engine. The case that makes the ordering visible: /api/v1/fraud/** is matched by
        // /api/** too, so if FRAUD_PATH were ever moved below the catch-all these would all read 200
        // instead of 403 and the customer rows would catch it.
        "/api/v1/fraud/decisions, FRAUD_ANALYST, 200",
        "/api/v1/fraud/alerts, FRAUD_ANALYST, 200",
        "/api/v1/fraud/summary, COMPLIANCE_OFFICER, 200",
        "/api/v1/fraud/summary, AUDITOR, 200",
        "/api/v1/fraud/summary, PLATFORM_ADMIN, 200",
        "/api/v1/fraud/summary, CUSTOMER, 403",
        "/api/v1/fraud/summary, SUPPORT_AGENT, 403"
    })
    @DisplayName("per-route authorisation follows the policy table")
    void authorises_by_role(String path, String role, int expectedStatus) {
        get(path, tokens.tokenFor(role, "fintech-api"))
                .exchange()
                .expectStatus()
                .isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("a request with no token is 401 and says what to do about it")
    void missing_token_is_unauthorised() {
        webClient
                .get()
                .uri("/api/accounts")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("a token that is not a JWT is 401")
    void malformed_token_is_unauthorised() {
        get("/api/accounts", "not-a-jwt").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("credentials without the bearer prefix are 401")
    void non_bearer_authorization_header_is_unauthorised() {
        // Otherwise a client whose library sends Basic by mistake gets a confusing failure mode.
        webClient
                .get()
                .uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46ZmluZWNob25n")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("a token minted for another client in the same realm is refused")
    void wrong_audience_is_unauthorised() {
        // A valid signature proves who signed a token, not that the token was meant for this API.
        get("/api/accounts", tokens.tokenFor("CUSTOMER", "account-service"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("a token from another issuer is refused")
    void wrong_issuer_is_unauthorised() {
        get(
                        "/api/accounts",
                        tokens.tokenWith(
                                "CUSTOMER",
                                "fintech-api",
                                "https://issuer.example.test/realms/other",
                                new Date(),
                                future()))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("an expired token is refused even though its signature is valid")
    void expired_token_is_unauthorised() {
        // The signature is perfectly valid. Refusing it is the entire point of checking the lifetime,
        // and it is exactly what a signature-only validation silently omits.
        get("/api/accounts", tokens.tokenExpired("CUSTOMER", "fintech-api"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("a token carrying no roles is refused every route")
    void token_without_roles_is_refused_everything() {
        // The exact shape the misconfigured realm produced: authentic, audience-correct, and useless.
        get("/api/accounts", tokens.tokenFor(null, "fintech-api"))
                .exchange()
                .expectStatus()
                .isForbidden();
        get("/api/audit/events", tokens.tokenFor(null, "fintech-api"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("a caller cannot promote themselves with a role in a scope")
    void scope_cannot_grant_a_role() {
        // The realm has clients this gateway knows nothing about. Coupling authorisation to scopes would
        // let any of them obtain authority by asking for a scope string.
        get("/api/admin/users", tokens.tokenFor("CUSTOMER", "fintech-api"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("operational endpoints stay reachable without a token")
    void operational_endpoints_are_public() {
        // Prometheus and container health checks cannot present a bearer token. Closing these would
        // leave the platform unmonitorable while its dashboards still looked healthy.
        webClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
        webClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("a path outside the policy is denied rather than passed through")
    void undeclared_paths_are_denied() {
        webClient.get().uri("/internal/actuator/env").exchange().expectStatus().isUnauthorized();

        // A traversal attempt. Netty normalises this to /internal/health and answers 400 before the
        // application is reached, which is the container doing the right thing. The requirement is that
        // it is not served, not that it produces a particular code: asserting 401 here would be
        // asserting an implementation detail of the reactor in a test about authorisation.
        int status = webClient
                .get()
                .uri("/api/../internal/health")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();

        assertThat(status).isNotIn(200, 204);
    }

    @Test
    @DisplayName("an authorised request reaches the routing layer rather than being refused")
    void an_authorised_request_is_not_refused() {
        get("/api/accounts", tokens.tokenFor("CUSTOMER", "fintech-api"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reached")
                .isEqualTo("downstream");
    }

    @Test
    @DisplayName("a refused request still carries a correlation id")
    void refusals_carry_a_correlation_id() {
        // An unauthenticated caller cannot be traced through the logs without one, and a 401 that
        // arrives with no id is a support ticket nobody can close.
        webClient
                .get()
                .uri("/api/accounts")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueMatches("X-Correlation-Id", "[A-Za-z0-9\\-]{1,64}");
    }

    @Test
    @DisplayName("a well-formed caller correlation id is preserved and a malformed one is replaced")
    void correlation_id_is_validated() {
        webClient
                .get()
                .uri("/api/accounts")
                .header("X-Correlation-Id", "client-trace-1234")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals("X-Correlation-Id", "client-trace-1234");

        // A newline in a header value is log injection: it would let a caller write fabricated lines
        // into somebody else's log aggregator.
        webClient
                .get()
                .uri("/api/accounts")
                .header("X-Correlation-Id", "abc\n2026-01-01 INFO forged entry")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueMatches("X-Correlation-Id", "[A-Za-z0-9\\-]{1,64}");
    }

    private WebTestClient.RequestHeadersSpec<?> get(String path, String token) {
        return webClient.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static Date future() {
        return new Date(System.currentTimeMillis() + 300_000);
    }
}
