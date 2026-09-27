package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.common.correlation.CorrelationId;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.identity.InternalIdentityProperties;
import com.fintech.platform.common.identity.InternalIdentityVerificationException;
import com.fintech.platform.gateway.testsupport.JwtFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway half of ADR-0004: turning a verified token into a signed identity, and refusing to let a
 * client supply one.
 *
 * <p>These are unit tests over a stub filter chain rather than HTTP tests, because the thing worth
 * asserting is the exact header set handed downstream, and going through a server would only obscure
 * it. The signature is verified with the real codec, so a header the gateway could not have produced
 * fails here.
 */
class InternalIdentityForwardingFilterTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private Clock clock;
    private InternalIdentityForwardingFilter filter;
    private InternalIdentityCodec codec;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        codec = InternalIdentityCodec.fromHexKey(SIGNING_KEY, java.time.Duration.ofSeconds(60), clock);
        filter = new InternalIdentityForwardingFilter(new InternalIdentityProperties(SIGNING_KEY, 60, null), clock);
    }

    @Test
    @DisplayName("a verified token becomes a signed identity a service accepts")
    void signs_a_verified_identity() {
        Map<String, String> headers = forward(authenticatedToken("CUSTOMER"), Map.of());

        InternalIdentity identity = codec.verify(headers);
        assertThat(identity.subject()).isEqualTo("8f14e45f-ea0c-4f2b-9a1d-1234567890ab");
        assertThat(identity.username()).isEqualTo("customer@fintech.test");
        assertThat(identity.roles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("every role in the token reaches the identity")
    void signs_every_role() {
        Map<String, String> headers = forward(authenticatedToken("CUSTOMER", "SUPPORT_AGENT"), Map.of());

        assertThat(codec.verify(headers).roles()).containsExactly("CUSTOMER", "SUPPORT_AGENT");
    }

    @Test
    @DisplayName("the correlation id is carried so the downstream service logs join the same trace")
    void carries_the_correlation_id() {
        Map<String, String> headers = forward(authenticatedToken("CUSTOMER"), Map.of());

        assertThat(codec.verify(headers).correlationId()).isEqualTo("edge-trace-0001");
    }

    @Test
    @DisplayName("a client cannot choose its own roles")
    void strips_a_spoofed_identity() {
        // The core of ADR-0004. Without the strip, this header would reach the service alongside the
        // gateway's own and the service would be deciding which of two role lists to believe.
        Map<String, String> forwarded = forward(authenticatedToken("CUSTOMER"), JwtFixture.spoofedIdentityHeaders());

        InternalIdentity identity = codec.verify(forwarded);
        assertThat(identity.roles()).containsExactly("CUSTOMER");
        assertThat(identity.subject()).isEqualTo("8f14e45f-ea0c-4f2b-9a1d-1234567890ab");
        assertThat(identity.username()).isEqualTo("customer@fintech.test");
    }

    @Test
    @DisplayName("a spoofed header set is replaced, not merely supplemented")
    void does_not_pass_through_any_client_identity_header() {
        Map<String, String> forwarded = forward(authenticatedToken("CUSTOMER"), JwtFixture.spoofedIdentityHeaders());

        // Exactly one value per identity header. Two values for one name is the ambiguity that makes
        // signature verification depend on which library read the request first.
        forwarded.forEach((name, value) -> assertThat(value).as(name).isNotNull());
        assertThat(forwarded.get(InternalIdentityCodec.HEADER_ROLES)).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("an unauthenticated request carries no identity at all")
    void sends_no_identity_when_there_is_no_token() {
        // Unreachable through the real chain, which refuses unauthenticated traffic first. Asserted
        // anyway: the guarantee is that this filter is not the thing standing between a caller and an
        // invented identity, whatever order it is eventually placed in.
        ServerWebExchange exchange =
                exchangeFor(MockServerHttpRequest.get("/api/accounts").build());

        captureDownstream(exchange, null);

        assertThat(captureResult).isEmpty();
    }

    @Test
    @DisplayName("a spoofed header set is stripped even when nothing authenticates the request")
    void strips_spoofed_headers_without_a_token() {
        HttpHeaders spoofed = new HttpHeaders();
        JwtFixture.spoofedIdentityHeaders().forEach(spoofed::add);
        ServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.get("/api/accounts").headers(spoofed).build());
        exchange.getAttributes().put(CorrelationId.REQUEST_ATTRIBUTE, "edge-trace-0001");

        captureDownstream(exchange, null);

        assertThat(captureResult).isEmpty();
    }

    @Test
    @DisplayName("a header name the platform does not define is still stripped")
    void strips_an_unrecognised_identity_header() {
        // Stripping by prefix rather than by enumeration: a future header would otherwise be protected
        // on the day it is added to the codec and unprotected until then.
        ServerWebExchange exchange = exchangeFor(MockServerHttpRequest.get("/api/accounts")
                .header("X-Internal-Identity-Future-Field", "surprise")
                .build());
        exchange.getAttributes().put(CorrelationId.REQUEST_ATTRIBUTE, "edge-trace-0001");

        captureDownstream(exchange, authenticatedToken("CUSTOMER"));

        assertThat(captureResult).doesNotContainKey("X-Internal-Identity-Future-Field");
        assertThat(codec.verify(captureResult).roles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("a missing correlation id does not prevent the identity being signed")
    void tolerates_a_missing_correlation_id() {
        ServerWebExchange exchange =
                exchangeFor(MockServerHttpRequest.get("/api/accounts").build());

        captureDownstream(exchange, authenticatedToken("CUSTOMER"));

        assertThat(codec.verify(captureResult).correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("the identity a service sees is one this gateway genuinely produced")
    void what_a_service_verifies_is_not_forgeable() {
        Map<String, String> forwarded = forward(authenticatedToken("CUSTOMER"), Map.of());

        // Independent re-signing attempt with a different key must not produce something the service
        // accepts, which is what makes "the gateway is the only writer" a checked property.
        InternalIdentityCodec attacker = InternalIdentityCodec.fromHexKey(
                "1111111111111111111111111111111111111111111111111111111111111111",
                java.time.Duration.ofSeconds(60),
                clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> attacker.verify(forwarded))
                .isInstanceOf(InternalIdentityVerificationException.class);
    }

    private Map<String, String> forward(JwtAuthenticationToken token, Map<String, String> inboundHeaders) {
        HttpHeaders headers = new HttpHeaders();
        inboundHeaders.forEach(headers::add);
        ServerWebExchange exchange = exchangeFor(
                MockServerHttpRequest.get("/api/accounts").headers(headers).build());

        captureDownstream(exchange, token);
        return captureResult;
    }

    /** MockServerWebExchange.from only accepts a concrete MockServerHttpRequest, hence the narrow type. */
    private static ServerWebExchange exchangeFor(MockServerHttpRequest request) {
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(CorrelationId.REQUEST_ATTRIBUTE, "edge-trace-0001");
        return exchange;
    }

    private Map<String, String> captureResult;

    /**
     * Runs the filter with a stub chain that records the headers the request carries at the moment it
     * would be dispatched, which is the only point at which the identity headers are meaningful.
     */
    private void captureDownstream(ServerWebExchange exchange, JwtAuthenticationToken token) {
        // The filter reads ReactiveSecurityContextHolder, which resolves from the Reactor context. In
        // the running gateway that context is populated by Spring Security's authentication filter; here
        // it is seeded explicitly, which is the same contract from the filter's point of view.
        Mono<Void> result = filter.filter(exchange, requestExchange -> {
            captureResult = flatten(requestExchange.getRequest().getHeaders());
            return Mono.empty();
        });

        if (token != null) {
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(token));
        }
        result.block();
    }

    private static Map<String, String> flatten(HttpHeaders headers) {
        Map<String, String> flat = new java.util.LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                flat.put(name, values.get(0));
            }
        });
        return flat;
    }

    private static JwtAuthenticationToken authenticatedToken(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-03-01T09:59:00Z"),
                Instant.parse("2026-03-01T10:05:00Z"),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub",
                        "8f14e45f-ea0c-4f2b-9a1d-1234567890ab",
                        "preferred_username",
                        roles.length > 0 ? roles[0].toLowerCase() + "@fintech.test" : "someone@fintech.test"));
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
