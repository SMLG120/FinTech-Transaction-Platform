package com.fintech.platform.common.identity;

import com.fintech.platform.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the identity the gateway asserted and refuses any request that arrives without one.
 *
 * <p>This filter is the receiving half of ADR-0004, and its default is refusal. A service that is
 * reachable directly — not through the gateway — will normally have no identity headers at all, and
 * the tempting behaviour is to treat that as "an internal call, carry on". That is precisely the
 * bypass the design exists to close: it lets a caller on the cluster network act as any user, and it
 * does so silently, with no failed request to notice in a log.
 *
 * <p>Only infrastructure probes are exempt, and the exemption list is explicit rather than a wildcard
 * on the path, because a probe endpoint that leaked caller data would be a disclosure of exactly the
 * information the identity protects.
 */
public class InternalIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalIdentityFilter.class);

    static final String REQUEST_ATTRIBUTE = InternalIdentityFilter.class.getName() + ".identity";
    static final String MDC_SUBJECT = "identitySubject";

    private static final String[] SIGNED_HEADERS = {
        InternalIdentityCodec.HEADER_SUBJECT,
        InternalIdentityCodec.HEADER_USERNAME,
        InternalIdentityCodec.HEADER_ROLES,
        InternalIdentityCodec.HEADER_CORRELATION_ID,
        InternalIdentityCodec.HEADER_ISSUED_AT,
        InternalIdentityCodec.HEADER_SIGNATURE
    };

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final InternalIdentityCodec codec;
    private final InternalIdentityProperties properties;

    public InternalIdentityFilter(InternalIdentityCodec codec, InternalIdentityProperties properties) {
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (isExcluded(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        InternalIdentity identity;
        try {
            identity = codec.verify(collectHeaders(request));
        } catch (InternalIdentityVerificationException e) {
            // The reason goes to the log, not the response: a caller that can distinguish an expired
            // signature from a malformed one learns which part of a forgery to correct.
            log.warn("rejected request with untrusted internal identity: {}", e.getMessage());
            MDC.remove(MDC_SUBJECT);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        request.setAttribute(REQUEST_ATTRIBUTE, identity);
        MDC.put(MDC_SUBJECT, identity.subject());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_SUBJECT);
        }
    }

    private boolean isExcluded(HttpServletRequest request) {
        String path = request.getRequestURI();
        return properties.excludedPaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    /**
     * Reads only the six headers that make up the signed set. Anything else prefixed
     * {@code X-Internal-Identity-} is not part of the contract, and passing the whole request header
     * map to the codec would make a future header addition silently part of the signed payload.
     */
    private static Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : SIGNED_HEADERS) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    /** Runs immediately after the correlation-id filter and ahead of anything that reads a caller. */
    public static int order() {
        return CorrelationIdFilter.order() + 10;
    }
}
