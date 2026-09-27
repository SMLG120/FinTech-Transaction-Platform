package com.fintech.platform.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.common.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("echoes a client-supplied correlation id so a caller can quote it in a support ticket")
    void echoesClientSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        request.addHeader(CorrelationId.HEADER, "client-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (req, res) -> assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("client-trace-123"));

        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("client-trace-123");
    }

    @Test
    @DisplayName("generates an id when the client sends none")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/transactions"), response, (req, res) -> {});

        assertThat(response.getHeader(CorrelationId.HEADER)).isNotBlank();
        assertThat(CorrelationId.isAcceptable(response.getHeader(CorrelationId.HEADER)))
                .isTrue();
    }

    @Test
    @DisplayName("discards a forged correlation id instead of writing attacker text into every log line")
    void discardsForgedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        request.addHeader(CorrelationId.HEADER, "evil\nERROR forged log entry for user bob");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = MDC.get(CorrelationId.MDC_KEY));

        assertThat(seen[0]).isNotEqualTo("evil\nERROR forged log entry for user bob");
        assertThat(CorrelationId.isAcceptable(seen[0])).isTrue();
    }

    @Test
    @DisplayName("exposes the id as a request attribute so the exception handler can read it without MDC")
    void exposesRequestAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/transactions");
        Object[] seen = new Object[1];

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> seen[0] = req.getAttribute(CorrelationId.REQUEST_ATTRIBUTE));

        assertThat(seen[0]).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("clears the MDC after the request; leaking it would cross-contaminate the next tenant's logs")
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/a"), new MockHttpServletResponse(), (req, res) -> {
            assertThat(MDC.get(CorrelationId.MDC_KEY)).isNotNull();
        });

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("clears the MDC even when the request blows up mid-chain")
    void clearsMdcOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/a");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                            throw new IllegalStateException("boom");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("runs ahead of the Spring Security filter chain so even a 401 carries a correlatable id")
    void orderPrecedesSecurityFilterChain() {
        // Spring Security registers its FilterChainProxy at SecurityProperties.DEFAULT_FILTER_ORDER (-100).
        assertThat(CorrelationIdFilter.order()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(CorrelationIdFilter.order()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }
}
