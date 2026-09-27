package com.fintech.platform.settlement.domain;

/**
 * Why a reconciliation found a difference, classified rather than left as prose.
 *
 * <p>Each kind is a different question for whoever has to answer it, so they cannot be merged. An
 * {@link #AMOUNT_MISMATCH} is a question for finance about a figure. An {@link #ORPHAN_REVERSAL} is a
 * question for engineering about an event that arrived out of order. Treating them as one "settlement
 * break" would route both to whoever answered last.
 */
public enum BreakKind {

    /**
     * The declared actual for the period does not match the computed expected total.
     *
     * <p>The only kind produced by reconciliation itself, and the one that means the platform's picture of
     * the money and the outside world's picture of the money disagree.
     */
    AMOUNT_MISMATCH,

    /**
     * A reversal arrived for a payment this service never saw settle.
     *
     * <p>Either the capture event was lost, or the reversal is for a payment that settled before this
     * service existed, or it is for something that was never a payment. All three are real and none can
     * be resolved by guessing, so it is recorded against the cycle and escalated. Recording it as a line
     * with no counterpart would produce a statement that nets a refund against nothing.
     */
    ORPHAN_REVERSAL,

    /**
     * A movement arrived for a period that had already been closed and given out.
     *
     * <p>The condition is identical whether the movement is a capture or a refund: money moved, the period
     * is immutable, and the line cannot be added. So one kind covers both, and the detail text says which —
     * they are the same problem for whoever has to fix it, and the day the code gave them different kinds
     * is the day somebody had to check both.
     *
     * <p>It is a routine consequence rather than a defect. A cycle closed in the same instant a payment
     * settles will miss that payment, because a batch boundary is a real boundary; what is not acceptable
     * is missing it <em>quietly</em>. See ADR-0009.
     */
    PERIOD_ALREADY_CLOSED
}
