package com.fintech.platform.dispute.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to dispute-service.
 *
 * <p>Same {@link ErrorCode} contract and wire format as every other service, so a client handles a
 * platform error without knowing which service raised it.
 *
 * <p><b>No code mentions a payment's owner, a card or a digest.</b> An error message is the most
 * likely thing in a service to end up in a log, a support ticket or a monitoring label. The codes
 * describe what the caller did wrong and nothing about whose money the case is about.
 */
public final class DisputeErrors {

    private DisputeErrors() {}

    /** No dispute matches the id supplied. 404. */
    public static final ErrorCode NOT_FOUND =
            ErrorCode.of("DISPUTE_NOT_FOUND", HttpStatus.NOT_FOUND, "No dispute exists with that id");

    /**
     * No payment matches the id supplied — or none the caller may see.
     *
     * <p>One code for both, because transaction-service answers both the same way: its ownership
     * check deliberately does not reveal whether the payment exists. A 403 here would confirm the
     * payment is real to someone with no right to know it, so a reach for another customer's money
     * looks exactly like a typo — and only the caller's own audit trail could tell them apart.
     */
    public static final ErrorCode PAYMENT_NOT_FOUND =
            ErrorCode.of("DISPUTE_PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "No payment exists with that id");

    /**
     * The payment is not settled, so there is nothing to dispute yet.
     *
     * <p>422 rather than 409: the request was understood and the case is the wrong one for this
     * payment's state. A hold is money reserved, not money moved — disputing it demands a refund of
     * a payment that never completed.
     */
    public static final ErrorCode PAYMENT_NOT_SETTLED = ErrorCode.of(
            "DISPUTE_PAYMENT_NOT_SETTLED", HttpStatus.UNPROCESSABLE_ENTITY, "Only a settled payment can be disputed");

    /**
     * The payment already has an open case.
     *
     * <p>409, not a second case. Two concurrent cases on the same money are two queues working the
     * same refund, and the partial unique index behind this code refuses the second at the database
     * as well as here.
     */
    public static final ErrorCode ALREADY_OPEN =
            ErrorCode.of("DISPUTE_ALREADY_OPEN", HttpStatus.CONFLICT, "That payment already has an open dispute");

    /**
     * Evidence or a decision for a case that already has an outcome.
     *
     * <p>409. The case is history; a decision after the decision would ask the ledger to refund
     * twice, and evidence after the decision is a file that was decided before it was complete.
     */
    public static final ErrorCode NOT_OPEN =
            ErrorCode.of("DISPUTE_NOT_OPEN", HttpStatus.CONFLICT, "That dispute is already resolved");

    /**
     * The caller may not open, read, plead or decide this case.
     *
     * <p>403 rather than 404: a permissions mistake on a case that exists must not read as a
     * missing case.
     */
    public static final ErrorCode FORBIDDEN =
            ErrorCode.of("DISPUTE_FORBIDDEN", HttpStatus.FORBIDDEN, "This case is not yours to work");

    /**
     * Transaction-service could not confirm the payment.
     *
     * <p>503, not 500 and not a refusal. The dispute itself is well-formed; the dependency that
     * would make it safe is unreachable, so the answer is "try again" rather than "no". Failing
     * closed here is what keeps an outage from becoming cases opened on payments nobody verified.
     */
    public static final ErrorCode TRANSACTION_UNAVAILABLE = ErrorCode.of(
            "DISPUTE_TRANSACTION_UNAVAILABLE",
            HttpStatus.SERVICE_UNAVAILABLE,
            "The payment could not be confirmed right now");
}
