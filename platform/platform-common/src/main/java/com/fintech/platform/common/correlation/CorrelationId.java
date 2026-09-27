package com.fintech.platform.common.correlation;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * The platform-wide correlation-id contract.
 *
 * <p>A correlation id is a single identifier that stitches together everything that happened for
 * one logical business operation: the HTTP request that entered the gateway, every service it
 * touched, every Kafka event published, every consumer that processed it, every log line written
 * and every audit record produced.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>A client may supply its own via the {@value #HEADER} request header. The value is accepted
 *       only if it looks sane (see {@link #isAcceptable(String)}) so a caller cannot inject newlines
 *       into log files or an unbounded string into every log line.
 *   <li>Otherwise the platform generates a UUIDv4.
 *   <li>The value is published in the SLF4J {@link MDC} under {@value #MDC_KEY}, which is what
 *       makes it appear in every structured log line and in Logstash-style JSON output without any
 *       per-call-site effort.
 *   <li>It is echoed on the HTTP response so a caller can quote it in a support request, and it is
 *       copied into every {@link com.fintech.platform.common.event.EventEnvelope}.
 * </ul>
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Servlet/WebFlux request attribute holding the resolved id. */
    public static final String REQUEST_ATTRIBUTE = "fintech.correlationId";

    private static final int MAX_LENGTH = 64;

    private CorrelationId() {}

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Guards against log injection and unbounded values. Accepted ids are 1-64 characters of
     * {@code [A-Za-z0-9._-]}; anything else is discarded and a fresh UUID is generated.
     */
    public static boolean isAcceptable(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** Returns the id of the work currently being done on this thread, or {@code null}. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
