package com.fintech.platform.common.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Cross-cutting error codes every service can raise.
 *
 * <p>Service-specific codes extend the same {@link ErrorCode} contract. These generic codes exist so
 * that cross-cutting concerns (auth, gateway, common web failures) respond with the same vocabulary
 * no matter which service handled the request.
 */
public final class CommonErrorCodes {

    private CommonErrorCodes() {}

    public static final ErrorCode VALIDATION_ERROR =
            ErrorCode.of("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "Request validation failed");

    public static final ErrorCode MALFORMED_REQUEST =
            ErrorCode.of("MALFORMED_REQUEST", HttpStatus.BAD_REQUEST, "Request body is missing or malformed");

    public static final ErrorCode MISSING_REQUIRED_FIELD =
            ErrorCode.of("MISSING_REQUIRED_FIELD", HttpStatus.BAD_REQUEST, "A required field is missing");

    public static final ErrorCode UNAUTHENTICATED =
            ErrorCode.of("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "Authentication is required");

    public static final ErrorCode INVALID_TOKEN =
            ErrorCode.of("INVALID_TOKEN", HttpStatus.UNAUTHORIZED, "The access token is invalid or expired");

    public static final ErrorCode FORBIDDEN =
            ErrorCode.of("FORBIDDEN", HttpStatus.FORBIDDEN, "You are not allowed to perform this action");

    public static final ErrorCode NOT_FOUND =
            ErrorCode.of("NOT_FOUND", HttpStatus.NOT_FOUND, "The requested resource was not found");

    public static final ErrorCode METHOD_NOT_ALLOWED =
            ErrorCode.of("METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported");

    public static final ErrorCode UNSUPPORTED_MEDIA_TYPE =
            ErrorCode.of("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type");

    public static final ErrorCode CONFLICT =
            ErrorCode.of("CONFLICT", HttpStatus.CONFLICT, "The request conflicts with the current resource state");

    public static final ErrorCode CONCURRENT_MODIFICATION = ErrorCode.of(
            "CONCURRENT_MODIFICATION",
            HttpStatus.CONFLICT,
            "The resource was modified concurrently; retry the request");

    public static final ErrorCode UNPROCESSABLE_ENTITY = ErrorCode.of(
            "UNPROCESSABLE_ENTITY", HttpStatus.UNPROCESSABLE_ENTITY, "The request is semantically invalid");

    public static final ErrorCode RATE_LIMITED =
            ErrorCode.of("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many requests; retry later");

    public static final ErrorCode INTERNAL_ERROR =
            ErrorCode.of("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    public static final ErrorCode SERVICE_UNAVAILABLE = ErrorCode.of(
            "SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "A downstream dependency is unavailable");

    public static List<ErrorCode> all() {
        return List.of(
                VALIDATION_ERROR,
                MALFORMED_REQUEST,
                MISSING_REQUIRED_FIELD,
                UNAUTHENTICATED,
                INVALID_TOKEN,
                FORBIDDEN,
                NOT_FOUND,
                METHOD_NOT_ALLOWED,
                UNSUPPORTED_MEDIA_TYPE,
                CONFLICT,
                CONCURRENT_MODIFICATION,
                UNPROCESSABLE_ENTITY,
                RATE_LIMITED,
                INTERNAL_ERROR,
                SERVICE_UNAVAILABLE);
    }

    public static Map<String, ErrorCode> byCode() {
        return all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ErrorCode::code, c -> c));
    }
}
