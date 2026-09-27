package com.fintech.platform.common.web;

import com.fintech.platform.common.correlation.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation id for the request/response pair and puts it on the logging MDC.
 *
 * <p>Ordering matters and is asserted by a test: this filter must run before anything that can log
 * or throw — that means before Spring Security's filter chain and before the dispatcher servlet — so
 * that even a rejected request carries a correlatable id.
 *
 * <p>MDC is populated in {@code try/finally} because servlet containers reuse threads. Failing to
 * clear it would attach one customer's correlation id to the next customer's log lines, which in a
 * multi-tenant platform is both a data leak and an audit-trail corruption.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = resolve(request);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private String resolve(HttpServletRequest request) {
        String supplied = request.getHeader(CorrelationId.HEADER);
        return CorrelationId.isAcceptable(supplied) ? supplied : CorrelationId.generate();
    }

    /**
     * Runs at the very front of the filter chain. {@link Ordered#HIGHEST_PRECEDENCE} is the Spring
     * Security filter; the small offset puts this ahead of it without going so far ahead that the
     * filter runs before the container has populated anything.
     */
    public static int order() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
