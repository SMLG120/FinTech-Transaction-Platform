package com.fintech.platform.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error body shape returned by every service and by the gateway.
 *
 * <p>Example:
 *
 * <pre>{@code
 * {
 *   "timestamp": "2026-09-25T23:41:07.512Z",
 *   "status": 400,
 *   "error": "VALIDATION_ERROR",
 *   "message": "Request validation failed",
 *   "path": "/api/transactions",
 *   "correlationId": "0f3c1f0e-6f0a-4f2f-9a1e-1b2c3d4e5f60",
 *   "fieldErrors": [ { "field": "amount", "message": "must be greater than 0" } ]
 * }
 * }</pre>
 *
 * <p>Two properties are contractual and must not change:
 *
 * <ul>
 *   <li>{@code error} is a stable machine code. Clients branch on it.
 *   <li>{@code correlationId} is echoed on every response, success or failure, so a support agent
 *       can find the exact server-side log entry for a report.
 * </ul>
 *
 * <p>What is deliberately <em>absent</em>: stack traces, exception class names, SQL statements,
 * internal hostnames, upstream vendor payloads, and rejected request values. See {@link FieldError}
 * for the rationale on values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    public ApiErrorResponse {
        if (fieldErrors != null && fieldErrors.isEmpty()) {
            fieldErrors = null;
        }
        if (details != null && details.isEmpty()) {
            details = null;
        }
    }

    public static ApiErrorResponse of(int status, String error, String message, String path, String correlationId) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, correlationId, null, null);
    }

    public ApiErrorResponse withFieldErrors(List<FieldError> fieldErrors) {
        return new ApiErrorResponse(timestamp, status, error, message, path, correlationId, fieldErrors, details);
    }

    public ApiErrorResponse withDetails(Map<String, Object> details) {
        return new ApiErrorResponse(timestamp, status, error, message, path, correlationId, fieldErrors, details);
    }

    /**
     * A single rejected field.
     *
     * <p>The submitted value is intentionally never echoed back. In a payments platform request
     * bodies carry PANs, tokens, personal identifiers and credentials, and error responses end up in
     * browser history, proxy logs, support tickets and screenshots. Echoing the value would turn a
     * validation failure into a data-exfiltration event. The field name and a human-readable reason
     * are enough for a client to correct the request.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) {

        public FieldError {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
