package com.fintech.platform.transaction.domain;

/**
 * Where a transaction is in its life.
 *
 * <p>The names are the card network's, and the meaning of each is the one a merchant would give it:
 * an {@code AUTHORIZED} payment has been approved and holds the customer's money; a {@code SETTLED} one
 * has actually been paid out; a {@code REVERSED} one has been undone either before or after capture,
 * and those two are the same status for different reasons rather than two statuses for the same
 * reason.
 */
public enum TransactionStatus {

    /** Created and accepted, not yet decided. Exists briefly and is what a payment waits in. */
    PENDING,

    /** Approved, with the amount held against the customer's available balance. */
    AUTHORIZED,

    /** Refused. Terminal, and no money ever moved. */
    DECLINED,

    /** Captured out of holds and paid out. */
    SETTLED,

    /** Undone. Terminal, and the original entry it reverses is named in the journal. */
    REVERSED
}
