package com.fintech.platform.common.error;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * A stable, machine-readable error identity.
 *
 * <p>The {@link #code()} is part of the public API contract: clients branch on it, so it must
 * never be reworded. The {@link #defaultMessage()} is a human-readable fallback that is safe to
 * return to an untrusted caller, i.e. it must never embed internal detail such as SQL, class
 * names, hostnames or stack traces.
 *
 * <p>Each service declares its own enum implementing this contract (for example {@code
 * CustomerErrorCode}) so that domain error codes are discoverable by tooling and exhaustiveness is
 * checked at compile time, while the wire format stays identical across the platform.
 */
public record ErrorCode(String code, HttpStatus status, String defaultMessage) {

    /**
     * The error code is part of the published client contract, so the shape is enforced at
     * construction rather than trusted. Clients switch on these strings; a code containing a space or
     * a lowercase letter would be a breaking change discovered in production.
     */
    private static final java.util.regex.Pattern CODE_PATTERN = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public ErrorCode {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(defaultMessage, "defaultMessage must not be null");
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("error code must be SCREAMING_SNAKE_CASE: " + code);
        }
    }

    public static ErrorCode of(String code, HttpStatus status, String defaultMessage) {
        return new ErrorCode(code, status, defaultMessage);
    }

    public int httpStatus() {
        return status.value();
    }

    /** Creates an exception carrying this code and its default message. */
    public ApiException exception() {
        return new ApiException(this);
    }

    /** Creates an exception carrying this code and a caller-safe message. */
    public ApiException exception(String message) {
        return new ApiException(this, message);
    }

    /** Creates an exception carrying this code, a caller-safe message and structured detail. */
    public ApiException exception(String message, java.util.Map<String, Object> details) {
        return new ApiException(this, message, details);
    }
}
