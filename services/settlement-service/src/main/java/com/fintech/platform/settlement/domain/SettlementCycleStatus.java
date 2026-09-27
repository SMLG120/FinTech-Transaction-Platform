package com.fintech.platform.settlement.domain;

/**
 * Where a cycle is in its life.
 *
 * <p>Four states and no way back, which is the point. {@code OPEN} accepts lines, {@code CLOSED} has
 * frozen them, and the two terminal states differ only in whether the frozen total was confirmed against
 * an independently declared figure. There is no transition out of {@code BROKEN}: a break is resolved by
 * accounting for the money in a later period, never by reopening a period and editing its totals, because
 * the totals are what the statement said.
 */
public enum SettlementCycleStatus {

    /**
     * Accepting lines.
     *
     * <p>The only state in which the cycle's contents can change. Every immutability rule in this service
     * is this state check.
     */
    OPEN,

    /**
     * The period is over and its lines are frozen, but no actual has been declared for it yet.
     *
     * <p>Separate from {@code RECONCILED} so that "we have finished counting" and "the money agrees" are
     * distinguishable, which is the entire difference between a settlement process and a counting process.
     */
    CLOSED,

    /**
     * Closed, and the declared actual matched the computed expected total.
     *
     * <p>Terminal. A reconciled cycle is a fact about money that has moved, and no event can make it
     * otherwise.
     */
    RECONCILED,

    /**
     * Closed, and the declared actual did not match.
     *
     * <p>Terminal for the cycle and explicitly not an error. The difference is the finding; a cycle that
     * refuses to record one is a cycle that has decided the money agrees before anybody has checked.
     */
    BROKEN
}
