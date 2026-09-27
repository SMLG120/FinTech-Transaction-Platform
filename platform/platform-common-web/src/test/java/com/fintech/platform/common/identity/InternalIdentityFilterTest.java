package com.fintech.platform.common.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The receiving half of ADR-0004: a service accepts an identity only when it can verify it.
 *
 * <p>The refusal cases matter more than the acceptance ones. A service that quietly permits requests
 * without a verified identity is not degraded, it is open, and nothing in its logs would say so.
 */
class InternalIdentityFilterTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private Clock clock;
    private InternalIdentityCodec codec;
    private InternalIdentityProperties properties;
    private InternalIdentityFilter filter;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        codec = InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), clock);
        properties = new InternalIdentityProperties(SIGNING_KEY, 60, null);
        filter = new InternalIdentityFilter(codec, properties);
    }

    @Test
    @DisplayName("a correctly signed identity is accepted and attached to the request")
    void accepts_a_verified_identity() throws Exception {
        MockHttpServletRequest request = requestWith(identityHeaders("CUSTOMER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Recorder recorder = run(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(recorder.reachedChain).isTrue();
        assertThat(request.getAttribute(InternalIdentityFilter.REQUEST_ATTRIBUTE))
                .isEqualTo(new InternalIdentity("sub", "user@fintech.test", List.of("CUSTOMER"), "cid", NOW));
    }

    @Test
    @DisplayName("a request with no identity headers at all is refused")
    void refuses_a_request_with_no_identity() throws Exception {
        // The bypass this design exists to close: a caller reaching the service directly, without
        // passing the gateway. Treating that as an internal call would hand over every user's authority.
        MockHttpServletResponse response = new MockHttpServletResponse();

        Recorder recorder = run(new MockHttpServletRequest("GET", "/api/accounts/1"), response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(recorder.reachedChain).isFalse();
    }

    @Test
    @DisplayName("a tampered role is refused")
    void refuses_a_tampered_role() throws Exception {
        Map<String, String> headers = new java.util.HashMap<>(identityHeaders("CUSTOMER"));
        headers.put(InternalIdentityCodec.HEADER_ROLES, "CUSTOMER,PLATFORM_ADMIN");

        MockHttpServletResponse response = new MockHttpServletResponse();
        Recorder recorder = run(requestWith(headers), response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(recorder.reachedChain).isFalse();
    }

    @Test
    @DisplayName("a signature from a different key is refused")
    void refuses_a_foreign_signature() throws Exception {
        InternalIdentityCodec attacker = InternalIdentityCodec.fromHexKey(
                "1111111111111111111111111111111111111111111111111111111111111111", Duration.ofSeconds(60), clock);
        Map<String, String> headers = attacker.headersFor(identity("CUSTOMER"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(requestWith(headers), response).reachedChain).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("an expired identity is refused")
    void refuses_a_stale_identity() throws Exception {
        InternalIdentity stale =
                new InternalIdentity("sub", "user@fintech.test", List.of("CUSTOMER"), "cid", NOW.minusSeconds(120));
        Map<String, String> headers = codec.headersFor(stale);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run(requestWith(headers), response).reachedChain).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a health probe is exempt, because Docker cannot present an identity")
    void permits_a_health_probe() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        Recorder recorder = run(new MockHttpServletRequest("GET", "/actuator/health"), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(recorder.reachedChain).isTrue();
    }

    @Test
    @DisplayName("a scrape endpoint is exempt, because Prometheus cannot present an identity")
    void permits_a_scrape_endpoint() throws Exception {
        // Excluding this would leave every service unmonitorable while the dashboards stayed green,
        // which is the failure mode that gets noticed in an incident rather than in a build.
        MockHttpServletResponse response = new MockHttpServletResponse();

        Recorder recorder = run(new MockHttpServletRequest("GET", "/actuator/prometheus"), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(recorder.reachedChain).isTrue();
    }

    @Test
    @DisplayName("the exemption does not extend to other actuator endpoints")
    void does_not_exempt_other_actuator_endpoints() throws Exception {
        // The exemption is a list, not a prefix. env, heapdump and loggers disclose how the service is
        // configured and secured, and must not become reachable to anyone on the network.
        for (String path : List.of("/actuator/env", "/actuator/heapdump", "/actuator/loggers", "/actuator/metrics")) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            Recorder recorder = run(new MockHttpServletRequest("GET", path), response);

            assertThat(response.getStatus()).as(path).isEqualTo(401);
            assertThat(recorder.reachedChain).as(path).isFalse();
        }
    }

    @Test
    @DisplayName("a refusal says nothing about why it was refused")
    void refusal_does_not_leak_the_reason() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        run(requestWith(new java.util.HashMap<>(identityHeaders("CUSTOMER"))), response);

        String body = response.getContentAsString();
        // Distinguishing "bad signature" from "expired" tells an attacker which part of a forgery to fix.
        assertThat(body).doesNotContain("signature").doesNotContain("expired").doesNotContain("older than");
    }

    @Test
    @DisplayName("the filter runs just after the correlation id filter")
    void is_ordered_after_the_correlation_id_filter() {
        // A rejection with no correlation id cannot be traced, which is the same class of problem as a
        // rejection with no cause.
        assertThat(InternalIdentityFilter.order()).isGreaterThan(CorrelationIdFilter.order());
    }

    @Test
    @DisplayName("a service with a different key refuses the gateway's identity")
    void refuses_an_identity_from_a_mismatched_service() {
        // Two services with different keys cannot impersonate one another. It also means a key
        // rotation is not optional, because until it happens one stale service accepts forged callers.
        InternalIdentityCodec other = InternalIdentityCodec.fromHexKey(
                "2222222222222222222222222222222222222222222222222222222222222222", Duration.ofSeconds(60), clock);

        assertThatThrownBy(() -> other.verify(identityHeaders("CUSTOMER")))
                .isInstanceOf(InternalIdentityVerificationException.class);
    }

    private InternalIdentity identity(String... roles) {
        return new InternalIdentity("sub", "user@fintech.test", List.of(roles), "cid", NOW);
    }

    private Map<String, String> identityHeaders(String... roles) {
        return codec.headersFor(identity(roles));
    }

    private static MockHttpServletRequest requestWith(Map<String, String> headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/1");
        headers.forEach(request::addHeader);
        return request;
    }

    private Recorder run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        Recorder recorder = new Recorder();
        filter.doFilter(request, response, recorder);
        return recorder;
    }

    /** Stands in for the rest of the application, and records whether the request got that far. */
    private static final class Recorder implements FilterChain {

        private boolean reachedChain;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            reachedChain = true;
        }
    }
}
