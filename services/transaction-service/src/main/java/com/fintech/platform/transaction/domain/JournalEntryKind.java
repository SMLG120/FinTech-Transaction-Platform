package com.fintech.platform.transaction.domain;

/**
 * Why a set of journal lines exists.
 *
 * <p>Recorded on the entry rather than inferred from its legs, because a ledger whose entries are only
 * legible by interpreting their own account types cannot be read by a human. {@code HOLD} and
 * {@code RELEASE} move money between the same two customer accounts in opposite directions, and
 * without this field the only way to tell a hold from its release is to notice which one is negative.
 */
public enum JournalEntryKind {

    /** Money entering the platform from outside, credited to the customer's available balance. */
    FUNDING,

    /**
     * An authorisation: money moved out of spendable and into held, so it can no longer be spent and
     * has not yet left the platform.
     */
    HOLD,

    /** A capture: held money leaves the customer and waits in clearing for settlement. */
    CAPTURE,

    /** A hold released without capture, returning the money to spendable. */
    RELEASE,

    /** The mirror of an earlier entry, naming it. */
    REVERSAL
}
