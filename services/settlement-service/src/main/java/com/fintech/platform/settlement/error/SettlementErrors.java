package com.fintech.platform.settlement.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to settlement-service.
 *
 * <p>Same {@link ErrorCode} contract and wire format as every other service, so a client handles a
 * platform error without knowing which service raised it.
 *
 * <p>The split between the three is the useful part. {@link #CYCLE_NOT_FOUND} is a caller who supplied a
 * reference that does not exist, which is a 404 and never a fault. {@link #CYCLE_NOT_OPEN} is a
 * well-formed request the cycle's state forbids, which is a 409. {@link #UNBALANCED} is the one that is
 * really an exception — somebody tried to mark a period final when its money does not account for itself —
 * and it has its own code so the refusal is legible in an audit trail instead of arriving as a 500.
 */
public final class SettlementErrors {

    private SettlementErrors() {}

    /** No cycle or break matches the reference or id supplied. 404. */
    public static final ErrorCode CYCLE_NOT_FOUND = ErrorCode.of(
            "SETTLEMENT_CYCLE_NOT_FOUND", HttpStatus.NOT_FOUND, "No settlement cycle or break matches that reference");

    /**
     * The cycle is not in a state that allows the requested change. 409.
     *
     * <p>The message carries the specific reason — closed, already reconciled, difference outstanding —
     * because "conflict" on its own tells an operator nothing about which state they are in or what would
     * have to change first. 404 would be wrong: the cycle exists, and answering as though it did not would
     * hide the actual problem behind a missing-resource error.
     */
    public static final ErrorCode CYCLE_NOT_OPEN = ErrorCode.of(
            "SETTLEMENT_CYCLE_NOT_OPEN",
            HttpStatus.CONFLICT,
            "That settlement cycle is not in a state that allows this change");

    /**
     * A declared actual did not match the expected total, so the cycle cannot be marked reconciled. 409.
     */
    public static final ErrorCode UNBALANCED = ErrorCode.of(
            "SETTLEMENT_CYCLE_UNBALANCED",
            HttpStatus.CONFLICT,
            "That settlement cycle does not balance, so it cannot be reconciled");

    /** The break has not been acknowledged, so it cannot be resolved. 409. */
    public static final ErrorCode BREAK_NOT_ACKNOWLEDGED = ErrorCode.of(
            "SETTLEMENT_BREAK_NOT_ACKNOWLEDGED",
            HttpStatus.CONFLICT,
            "That break must be acknowledged before it can be resolved");

    /**
     * A currency code or amount the request carried cannot be used. 400.
     *
     * <p>Separate from {@link #CYCLE_NOT_OPEN} because the two need different things from the caller. A
     * 409 says "the period is not in a state that allows this, here is which state it is in"; a 400 says
     * "what you sent cannot be read, send something else". Answering a typo with a 409 points somebody at
     * the cycle when the cycle was never the problem — and a three-letter uppercase string passes the
     * shape check, so {@code XYZ} arrives here as a well-formed code for a currency that does not exist.
     */
    public static final ErrorCode UNUSABLE_AMOUNT = ErrorCode.of(
            "SETTLEMENT_AMOUNT_UNUSABLE", HttpStatus.BAD_REQUEST, "That amount or currency code cannot be used");
}
