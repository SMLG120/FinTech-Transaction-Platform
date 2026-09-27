package com.fintech.platform.transaction.error;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.error.ErrorCode;
import com.fintech.platform.transaction.domain.TransactionStatus;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to transaction-service.
 *
 * <p>Same {@link ErrorCode} contract and the same wire format as every other service, so a client
 * handles a platform error without knowing which service raised it.
 *
 * <p>No code in this file ever mentions a card number, a card token or a holder name. An error message
 * is the most likely thing in a service to end up in a log, a support ticket or a monitoring label, and
 * the platform has no way to review all of those, so the codes describe what the caller did wrong and
 * nothing about stored payment data.
 *
 * <p>{@link #INSUFFICIENT_FUNDS} is the one that a card network declines rather than a platform
 * failure, and it is 422 rather than 409 for that reason: the request was well formed and understood,
 * and the answer is no.
 */
public final class TransactionErrorCodes {

    private TransactionErrorCodes() {}

    public static final ErrorCode TRANSACTION_NOT_FOUND =
            ErrorCode.of("TRANSACTION_NOT_FOUND", HttpStatus.NOT_FOUND, "No transaction exists with that reference");

    /**
     * The caller is not the payer and holds no role that permits acting on the transaction.
     *
     * <p>403 rather than 404, for the same reason as the equivalent code in card-service: a caller who
     * is authenticated but neither the owner nor staff is entitled to know the transaction is not
     * theirs, and hiding that behind 404 would make a support agent's mistake look like a
     * non-existent transaction.
     */
    public static final ErrorCode NOT_THE_PAYER =
            ErrorCode.of("NOT_THE_PAYER", HttpStatus.FORBIDDEN, "This transaction belongs to another customer");

    public static final ErrorCode TRANSACTION_INVALID_TRANSITION = ErrorCode.of(
            "TRANSACTION_INVALID_TRANSITION",
            HttpStatus.CONFLICT,
            "The requested transaction operation is not allowed from the current state");

    /**
     * The payer does not have enough spendable money.
     *
     * <p>422 rather than 409, because nothing about the request was malformed and nothing is racing:
     * the request was understood completely and the answer is that there is not enough money. A 409
     * would tell the caller to retry, and retrying a payment that has no funds fails identically every
     * time.
     */
    public static final ErrorCode INSUFFICIENT_FUNDS = ErrorCode.of(
            "INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY, "The available balance will not cover this amount");

    /** An amount of zero, or negative, or more precise than the currency allows. */
    public static final ErrorCode AMOUNT_NOT_ALLOWED = ErrorCode.of(
            "AMOUNT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY, "The amount is not a valid positive payment amount");

    /** Above the configured per-transaction ceiling. */
    public static final ErrorCode AMOUNT_ABOVE_LIMIT = ErrorCode.of(
            "AMOUNT_ABOVE_LIMIT", HttpStatus.UNPROCESSABLE_ENTITY, "The amount is above the permitted maximum");

    /** Would push the payer's spending today past the configured daily limit. */
    public static final ErrorCode DAILY_LIMIT_EXCEEDED = ErrorCode.of(
            "DAILY_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY, "The daily spending limit would be exceeded");

    /**
     * The referenced account does not exist for the payer's currency.
     *
     * <p>Usually means a customer has never been funded in that currency, which is a real condition
     * and not a caller error, but it is reported rather than silently creating an account: an
     * auto-created account would turn "this customer was never funded" into a zero balance that looks
     * like an ordinary empty one.
     */
    public static final ErrorCode ACCOUNT_NOT_FOUND = ErrorCode.of(
            "ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND, "No ledger account exists for this customer and currency");

    public static final ErrorCode CARD_NOT_ELIGIBLE = ErrorCode.of(
            "CARD_CARD_NOT_ELIGIBLE",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "The referenced card cannot be used for a payment");

    public static final ErrorCode CURRENCY_NOT_SUPPORTED =
            ErrorCode.of("CURRENCY_NOT_SUPPORTED", HttpStatus.UNPROCESSABLE_ENTITY, "That currency is not supported");

    // ---------------------------------------------------------------- idempotency

    /**
     * A money-moving request arrived with no {@code Idempotency-Key}.
     *
     * <p>400 rather than a default of "generate one for me". Generating a key on the caller's behalf
     * means a client that retries after a timeout without a key gets a second payment, which is the
     * one failure this whole mechanism exists to prevent, and it would be a failure the API made on
     * their behalf rather than one they could have avoided.
     */
    public static final ErrorCode IDEMPOTENCY_KEY_REQUIRED = ErrorCode.of(
            "IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST, "This operation requires an Idempotency-Key header");

    /**
     * The key was used before, with a different request.
     *
     * <p>409, and deliberately not 400. The key itself is well formed and the request is well formed;
     * what conflicts is that the two together have been used with two meanings, and answering that
     * with a 400 would tell the caller to fix something that is not wrong. This is the case that proves
     * a client is sending something different from what it sent the first time, and it must be loud:
     * silently treating the new request as a replay would execute it against the first one's stored
     * response and hand back a result for a payment that was never made.
     */
    public static final ErrorCode IDEMPOTENCY_KEY_REUSED = ErrorCode.of(
            "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT, "This Idempotency-Key was used with a different request");

    /**
     * A request with this key is already running, and the first attempt has not committed.
     *
     * <p>409 with a {@code Retry-After}, not 425 and not a wait. Two concurrent requests with one key
     * are a client that sent the same intent twice, and one of them is about to commit a payment. The
     * second must not proceed and must not block until the first finishes, because the second's whole
     * purpose is to find out what the first concluded.
     */
    public static final ErrorCode IDEMPOTENT_REQUEST_IN_PROGRESS = ErrorCode.of(
            "IDEMPOTENT_REQUEST_IN_PROGRESS",
            HttpStatus.CONFLICT,
            "A request with this Idempotency-Key is still in progress");

    /** An internal failure, reported to the caller as a retryable 503. */
    public static final ErrorCode LEDGER_POSTING_FAILED = ErrorCode.of(
            "LEDGER_POSTING_FAILED", HttpStatus.SERVICE_UNAVAILABLE, "The payment could not be applied to the ledger");

    /** Builds the transition error with both states attached, so a client can branch on them. */
    public static ApiException invalidStateTransition(TransactionStatus from, TransactionStatus to) {
        return TRANSACTION_INVALID_TRANSITION.exception(
                "A " + from + " transaction cannot become " + to, Map.of("from", from.name(), "to", to.name()));
    }
}
