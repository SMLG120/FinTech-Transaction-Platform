package com.fintech.platform.common.error;

import java.util.Map;
import java.util.Objects;

/**
 * The single exception type services throw to produce a governed error response.
 *
 * <p>Anything thrown that is <em>not</em> an {@code ApiException} is treated as an unexpected
 * failure by the platform exception handler: it is logged with a full stack trace server-side and
 * reported to the caller as a generic {@code INTERNAL_ERROR}. This inversion is deliberate — it
 * makes accidental information disclosure the default-safe path.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), Map.of());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public ApiException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
