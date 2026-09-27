package com.fintech.platform.common.web;

import com.fintech.platform.common.correlation.CorrelationId;
import com.fintech.platform.common.error.ApiErrorResponse;
import com.fintech.platform.common.error.ApiErrorResponse.FieldError;
import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.error.CommonErrorCodes;
import com.fintech.platform.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place any exception becomes an HTTP response.
 *
 * <p>Design rules, in priority order:
 *
 * <ol>
 *   <li><b>No stack trace ever reaches a client.</b> The fallback handler logs the full throwable
 *       server-side and returns a fixed message. Clients get a correlation id to quote instead.
 *   <li><b>Only explicitly mapped exceptions produce specific codes.</b> Anything unmapped is a
 *       500, so a new library exception cannot accidentally leak its own message.
 *   <li><b>Client errors are not logged as errors.</b> A 400 is the caller's problem and logging it
 *       at ERROR pollutes alerting; a 5xx is the platform's problem and is logged with a stack trace.
 * </ol>
 *
 * <p>Ordering of the handler methods below is documentation, not mechanics: Spring picks the most
 * specific match by exception type.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** An error the service declared and understands. The message is caller-safe by construction. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
        ErrorCode code = exception.getErrorCode();
        ApiErrorResponse body = respond(code, exception.getMessage(), request);

        if (code.status().is5xxServerError()) {
            log.error("Handled server-side error code={} path={}", code.code(), request.getRequestURI(), exception);
        } else {
            log.debug("Handled client error code={} path={}", code.code(), request.getRequestURI());
        }
        return ResponseEntity.status(code.status()).body(body.withDetails(emptyToNull(exception.getDetails())));
    }

    /** {@code @Valid} failure on a {@code @RequestBody} argument. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), safeMessage(error.getDefaultMessage())))
                .distinct()
                .toList();

        List<FieldError> globalErrors = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldError(error.getObjectName(), safeMessage(error.getDefaultMessage())))
                .toList();

        return validationResponse(fieldErrors, globalErrors, request);
    }

    /** Validation failure on individual method parameters, e.g. {@code @PathVariable @Positive}. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldError(
                                result.getMethodParameter().getParameterName(),
                                safeMessage(error.getDefaultMessage()))))
                .distinct()
                .toList();

        return validationResponse(fieldErrors, List.of(), request);
    }

    /** {@code @Validated} on a class, surfacing {@code ConstraintViolationException}. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(lastNode(violation), safeMessage(violation.getMessage())))
                .distinct()
                .toList();

        return validationResponse(fieldErrors, List.of(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(respond(CommonErrorCodes.MISSING_REQUIRED_FIELD, exception.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(respond(CommonErrorCodes.VALIDATION_ERROR, exception.getMessage(), request));
    }

    /**
     * An unparseable or truncated JSON body. The parser's own message can quote fragments of the
     * submitted document, so the caller is told only that the body is malformed.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        log.debug("Rejected unreadable request body on {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(respond(CommonErrorCodes.MALFORMED_REQUEST, "Request body is missing or malformed", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(respond(CommonErrorCodes.METHOD_NOT_ALLOWED, exception.getMessage(), request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(respond(CommonErrorCodes.UNSUPPORTED_MEDIA_TYPE, exception.getMessage(), request));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNoHandler(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(respond(CommonErrorCodes.NOT_FOUND, "No endpoint " + request.getRequestURI(), request));
    }

    /**
     * Authorization failure raised inside a controller or service method.
     *
     * <p>Note the access-denied reason is not echoed. Telling a caller "you are missing the
     * FRAUD_ANALYST role" is free reconnaissance.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {
        log.debug("Access denied on {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(respond(CommonErrorCodes.FORBIDDEN, CommonErrorCodes.FORBIDDEN.defaultMessage(), request));
    }

    /**
     * Optimistic locking failure. Reported as a retryable 409 rather than a 500: the request was
     * valid, it simply lost a race. The payment path in particular relies on the caller being told
     * to retry instead of being told the platform is broken.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLocking(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        log.info("Optimistic lock conflict on {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(respond(CommonErrorCodes.CONCURRENT_MODIFICATION, null, request));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handlePessimisticLocking(
            PessimisticLockingFailureException exception, HttpServletRequest request) {
        log.info("Pessimistic lock conflict on {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(respond(CommonErrorCodes.CONCURRENT_MODIFICATION, null, request));
    }

    /**
     * Unique-constraint violation. The driver's message names tables, columns and index definitions,
     * so it is logged but never returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        log.info("Data integrity violation on {}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(respond(CommonErrorCodes.CONFLICT, "The request conflicts with existing data", request));
    }

    /**
     * The safety net.
     *
     * <p>Whatever this catches is a bug. It is logged with the throwable so the stack trace exists
     * somewhere, and the caller receives a correlation id and nothing else. A response body
     * assembled from an arbitrary exception's message is how SQL fragments, file paths and library
     * internals end up in a browser.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.error("Unhandled exception correlationId={} path={}", correlationId, request.getRequestURI(), exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(
                        Instant.now(),
                        500,
                        CommonErrorCodes.INTERNAL_ERROR.code(),
                        "An unexpected error occurred. Quote the correlation id when reporting this.",
                        request.getRequestURI(),
                        correlationId,
                        null,
                        null));
    }

    // ------------------------------------------------------------------------------- helpers

    private ResponseEntity<ApiErrorResponse> validationResponse(
            List<FieldError> fieldErrors, List<FieldError> globalErrors, HttpServletRequest request) {
        List<FieldError> all =
                Stream.concat(fieldErrors.stream(), globalErrors.stream()).toList();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldErrorCount", all.size());

        ApiErrorResponse body = respond(
                        CommonErrorCodes.VALIDATION_ERROR, CommonErrorCodes.VALIDATION_ERROR.defaultMessage(), request)
                .withFieldErrors(all)
                .withDetails(details);

        return ResponseEntity.badRequest().body(body);
    }

    private ApiErrorResponse respond(ErrorCode code, String message, HttpServletRequest request) {
        return new ApiErrorResponse(
                Instant.now(),
                code.httpStatus(),
                code.code(),
                message == null || message.isBlank() ? code.defaultMessage() : message,
                request.getRequestURI(),
                correlationId(request),
                null,
                null);
    }

    private String correlationId(HttpServletRequest request) {
        Object fromRequest = request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE);
        if (fromRequest instanceof String id && !id.isBlank()) {
            return id;
        }
        String fromMdc = CorrelationId.current();
        return fromMdc != null && !fromMdc.isBlank() ? fromMdc : CorrelationId.generate();
    }

    private static Map<String, Object> emptyToNull(Map<String, Object> details) {
        return details == null || details.isEmpty() ? null : details;
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "is invalid" : message;
    }

    /** Renders a bean-validation path such as {@code createCustomer.email} as just {@code email}. */
    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }
}
