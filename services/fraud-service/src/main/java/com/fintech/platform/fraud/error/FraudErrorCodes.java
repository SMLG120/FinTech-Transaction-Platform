package com.fintech.platform.fraud.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to fraud-service.
 *
 * <p>Same {@link ErrorCode} contract and the same wire format as every other service, so a client handles
 * a platform error without knowing which service raised it. Codes are {@code SCREAMING_SNAKE_CASE} without
 * a numeric prefix: the prefix groups them in a log, and the number a previous draft used would have been
 * renumbered the first time a code was inserted in the middle.
 *
 * <p><b>No code mentions a card, a device or a network.</b> An error message is the most likely thing in a
 * service to end up in a log, a support ticket or a monitoring label, and this platform has no way to
 * review all three. The codes describe what the caller did wrong and nothing about the payment.
 */
public final class FraudErrorCodes {

    private FraudErrorCodes() {}

    /** The payment has no decision, so there is nothing to read. 404. */
    public static final ErrorCode DECISION_NOT_FOUND =
            ErrorCode.of("FRAUD_DECISION_NOT_FOUND", HttpStatus.NOT_FOUND, "No fraud decision exists for that payment");

    /**
     * The caller is neither a fraud analyst nor a compliance officer.
     *
     * <p>403 rather than 404, for the same reason as the equivalent code in transaction-service: an
     * authenticated caller who lacks the role is entitled to be told the endpoint exists and that they may
     * not use it. Answering 404 would make a permissions mistake look like a missing resource, and would
     * leave a genuine support question unanswerable.
     */
    public static final ErrorCode FRAUD_FORBIDDEN = ErrorCode.of(
            "FRAUD_FORBIDDEN", HttpStatus.FORBIDDEN, "This endpoint is for fraud analysts and compliance officers");

    /** The alert id does not exist. 404. */
    public static final ErrorCode ALERT_NOT_FOUND =
            ErrorCode.of("FRAUD_ALERT_NOT_FOUND", HttpStatus.NOT_FOUND, "No fraud alert exists with that id");

    /**
     * Somebody else holds the alert, or it is already closed.
     *
     * <p>409, not 403 and not 404. It is a conflict between two analysts working the same queue, the
     * request was understood, and retrying after checking the current state is the correct response. The
     * details carry the current owner so the loser can see who has it without a second request.
     */
    public static final ErrorCode ALERT_NOT_CLAIMABLE = ErrorCode.of(
            "ALERT_NOT_CLAIMABLE",
            HttpStatus.CONFLICT,
            "That alert is claimed by another analyst or is already closed");

    /**
     * An analyst's score is above the configured cap.
     *
     * <p>422 rather than 403: the analyst is entitled to change a score, and this particular change is one
     * the workflow does not permit yet. The message says what would be needed, because a 422 with no
     * explanation is the kind of thing a team works around rather than reports.
     */
    public static final ErrorCode MANUAL_SCORE_ABOVE_CAP = ErrorCode.of(
            "MANUAL_SCORE_ABOVE_CAP",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "A manual score above the cap needs a second approver, which this deployment does not have");

    /** A manual adjustment without a reason. 422. */
    public static final ErrorCode MANUAL_REASON_REQUIRED = ErrorCode.of(
            "MANUAL_REASON_REQUIRED",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "A manual adjustment needs a reason; it is the only record of why the engine was overruled");

    /** A re-score was asked for a payment this service has never scored. 404. */
    public static final ErrorCode RESCORE_UNKNOWN_PAYMENT = ErrorCode.of(
            "RESCORE_UNKNOWN_PAYMENT",
            HttpStatus.NOT_FOUND,
            "No decision exists for that payment, so there is nothing to re-score");

    /** The page size is outside what this service will serve. 400. */
    public static final ErrorCode INVALID_PAGE =
            ErrorCode.of("INVALID_PAGE", HttpStatus.BAD_REQUEST, "Page size must be between 1 and 200");
}
