package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.identity.InternalIdentityFilter;
import com.fintech.platform.common.identity.InternalIdentityProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The contract between the two halves of ADR-0004, exercised across the module boundary.
 *
 * <p>Each side is already tested on its own, and that is not the same question. The gateway's tests
 * prove it produces a header set that {@link InternalIdentityCodec} can verify. The service's tests
 * prove the filter rejects anything the codec cannot verify. Neither proves the gateway's output is
 * input the service accepts, which is the only property that matters in production and the only one
 * where a header renamed on one side alone would go unnoticed by both suites.
 *
 * <p>So this test runs the real gateway filter and feeds its output to the real servlet filter, with
 * the same key on both sides that the running stack shares. Everything here is the shipped
 * implementation; the only substitution is the network hop, which cannot change a header's value.
 */
class IdentityContractTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final String SUBJECT = "1f0a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private Clock clock;
    private InternalIdentityForwardingFilter gateway;
    private InternalIdentityProperties properties;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        properties = new InternalIdentityProperties(SIGNING_KEY, 60, null);
        gateway = new InternalIdentityForwardingFilter(properties, clock);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private InternalIdentityFilter serviceFilter(Clock verificationClock, InternalIdentityProperties config) {
        return new InternalIdentityFilter(
                InternalIdentityCodec.fromHexKey(config.signingKey(), Duration.ofSeconds(60), verificationClock),
                config);
    }

    private static JwtAuthenticationToken caller(String username, String... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-03-01T09:59:00Z"),
                Instant.parse("2026-03-01T10:05:00Z"),
                Map.of("alg", "RS256"),
                Map.of("sub", SUBJECT, "preferred_username", username));
        return new JwtAuthenticationToken(jwt, authorities, SUBJECT);
    }

    /**
     * Runs the gateway filter and returns the request exactly as the downstream container receives it.
     *
     * <p>Headers are captured at the dispatch point, which is the last moment before the exchange
     * leaves the gateway, rather than inspected on the original request.
     */
    private MockHttpServletRequest forward(JwtAuthenticationToken caller, String correlationId, String... inbound) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/accounts");
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        for (int i = 0; i < inbound.length; i += 2) {
            request.header(inbound[i], inbound[i + 1]);
        }
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Map<String, String> captured = new LinkedHashMap<>();
        gateway.filter(exchange, dispatched -> {
                    dispatched.getRequest().getHeaders().forEach((name, values) -> {
                        if (!values.isEmpty()) {
                            captured.put(name, values.get(0));
                        }
                    });
                    return Mono.empty();
                })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller))
                .block();

        MockHttpServletRequest downstream = new MockHttpServletRequest("GET", "/api/accounts");
        captured.forEach(downstream::addHeader);
        return downstream;
    }

    private record ServiceOutcome(
            int status, boolean reachedApplication, java.util.Optional<InternalIdentity> identity) {}

    /**
     * Dispatches the request through the service filter, with the request context installed so that
     * {@link CurrentCaller} resolves exactly as it would inside a controller.
     */
    private ServiceOutcome callService(
            MockHttpServletRequest request, Clock verificationClock, InternalIdentityProperties config)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        serviceFilter(verificationClock, config).doFilter(request, response, chain);

        // Read the identity back through the public accessor rather than the request attribute, so the
        // accessor a service would actually use is part of what this test covers.
        return new ServiceOutcome(response.getStatus(), chain.wasReached(), new CurrentCaller().find());
    }

    private ServiceOutcome callService(MockHttpServletRequest request) throws Exception {
        return callService(request, clock, properties);
    }

    /** The servlet API hands back an Enumeration; assertions are clearer on a list. */
    private static List<String> headerValues(MockHttpServletRequest request, String name) {
        return java.util.Collections.list(request.getHeaders(name));
    }

    private static List<String> headerNames(MockHttpServletRequest request) {
        return java.util.Collections.list(request.getHeaderNames());
    }

    private static final class RecordingChain extends MockFilterChain {
        private boolean reached;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            reached = true;
        }

        boolean wasReached() {
            return reached;
        }
    }

    @Test
    @DisplayName("a caller the gateway accepts is a caller the service accepts, roles intact")
    void gateway_output_is_accepted_by_the_service() throws Exception {
        MockHttpServletRequest downstream = forward(caller("customer@fintech.test", "CUSTOMER"), "corr-1234");

        ServiceOutcome outcome = callService(downstream);

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.reachedApplication()).isTrue();
        assertThat(outcome.identity()).isPresent();
        InternalIdentity identity = outcome.identity().orElseThrow();
        assertThat(identity.subject()).isEqualTo(SUBJECT);
        assertThat(identity.username()).isEqualTo("customer@fintech.test");
        assertThat(identity.roles()).containsExactly("CUSTOMER");
        assertThat(identity.correlationId()).isEqualTo("corr-1234");
    }

    @Test
    @DisplayName("a multi-role caller survives the hop with every role")
    void multi_role_caller_survives_the_hop() throws Exception {
        MockHttpServletRequest downstream =
                forward(caller("agent@fintech.test", "CUSTOMER", "SUPPORT_AGENT", "AUDITOR"), "corr-5678");

        InternalIdentity identity = callService(downstream).identity().orElseThrow();

        // Sorted, not in token order. The signature covers a canonical form, so a caller cannot change
        // the set of roles without invalidating it, and a proxy that reorders the header changes
        // nothing. Asserting sorted order documents that the ordering is part of the contract.
        assertThat(identity.roles()).containsExactly("AUDITOR", "CUSTOMER", "SUPPORT_AGENT");
    }

    @Test
    @DisplayName("a client-supplied identity is replaced, not forwarded")
    void client_supplied_identity_is_not_forwarded() throws Exception {
        // The request the gateway receives already carries a forged admin identity. The service must
        // see the gateway's own signed version of the verified token and nothing of the forgery.
        MockHttpServletRequest downstream = forward(
                caller("attacker@evil.test", "CUSTOMER"),
                "corr-9999",
                "X-Internal-Identity-Roles",
                "PLATFORM_ADMIN",
                "X-Internal-Identity-Subject",
                "attacker",
                "X-Internal-Identity-Username",
                "admin@fintech.test",
                "X-Internal-Identity-Signature",
                "Zm9yZ2Vk",
                "X-Internal-Identity-Issued-At",
                "1700000000",
                "X-Internal-Identity-Correlation-Id",
                "forged");

        assertThat(headerValues(downstream, "X-Internal-Identity-Roles")).containsExactly("CUSTOMER");
        assertThat(headerValues(downstream, "X-Internal-Identity-Subject")).containsExactly(SUBJECT);
        assertThat(headerValues(downstream, "X-Internal-Identity-Username")).containsExactly("attacker@evil.test");
        assertThat(headerValues(downstream, "X-Internal-Identity-Correlation-Id"))
                .doesNotContain("forged");

        InternalIdentity identity = callService(downstream).identity().orElseThrow();
        assertThat(identity.roles()).as("the forged role must not survive").containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("a service keyed differently rejects the gateway's output instead of half-trusting it")
    void mismatched_keys_are_rejected() throws Exception {
        // Covers the deployment mistake this contract cannot otherwise see: one service with a stale
        // key accepts nothing. 401 on every request is the correct, loud outcome, not a fallback.
        MockHttpServletRequest downstream = forward(caller("customer@fintech.test", "CUSTOMER"), "corr-1");
        InternalIdentityProperties stale = new InternalIdentityProperties("a".repeat(64), 60, null);

        ServiceOutcome outcome = callService(downstream, clock, stale);

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.reachedApplication()).isFalse();
        assertThat(outcome.identity()).isEmpty();
    }

    @Test
    @DisplayName("an identity that ages past the freshness bound is rejected end to end")
    void a_stale_identity_is_rejected() throws Exception {
        // Signed at NOW, verified 61 seconds later: the shape of a request that queued in the network
        // for longer than the platform is willing to honour a role revocation window.
        MockHttpServletRequest downstream = forward(caller("customer@fintech.test", "CUSTOMER"), "corr-2");

        ServiceOutcome outcome = callService(downstream, Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC), properties);

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.reachedApplication()).isFalse();
    }

    @Test
    @DisplayName("the two sides agree on the header names, which is what a one-sided rename would break")
    void both_sides_use_the_same_header_names() {
        MockHttpServletRequest downstream = forward(caller("customer@fintech.test", "CUSTOMER"), "corr-3");

        // A rename on one side alone leaves both existing suites green and production returning 401 on
        // every call, so the name agreement is asserted directly rather than left to coverage.
        assertThat(headerNames(downstream))
                .contains(
                        InternalIdentityCodec.HEADER_SUBJECT,
                        InternalIdentityCodec.HEADER_USERNAME,
                        InternalIdentityCodec.HEADER_ROLES,
                        InternalIdentityCodec.HEADER_CORRELATION_ID,
                        InternalIdentityCodec.HEADER_ISSUED_AT,
                        InternalIdentityCodec.HEADER_SIGNATURE);
    }

    @Test
    @DisplayName("roles arrive in a form a service can authorise on")
    void roles_are_authorisable_downstream() throws Exception {
        // Downstream authorisation reads the identity, not the token: a service cannot verify a token,
        // and re-deriving roles from an unverifiable claim would undo the whole design.
        MockHttpServletRequest downstream = forward(caller("agent@fintech.test", "SUPPORT_AGENT"), "corr-4");

        InternalIdentity identity = callService(downstream).identity().orElseThrow();

        assertThat(identity.roles()).contains("SUPPORT_AGENT");
        assertThat(identity.roles()).doesNotContain("PLATFORM_ADMIN");
    }
}
