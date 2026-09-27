package com.fintech.platform.transaction.domain;

/**
 * Which side of a journal line an amount sits on.
 *
 * <p>Debit increases the account it names, credit decreases it. That is the convention, and it is
 * worth stating explicitly because the alternative reading — "debit means money out" — is intuitive
 * from a bank customer's perspective and produces a ledger that is exactly backwards.
 *
 * <p>The intuition is about a *customer's* current account, where a debit is money leaving it. Here
 * the debit lands on whichever account the line names, and for the money inside a payment that is
 * usually {@code CUSTOMER_AVAILABLE}: authorising £10 credits that account, because the customer's
 * spendable money goes down by £10. The direction is relative to the account, never to the payer.
 *
 * @see LedgerPostings for the concrete postings this produces
 */
public enum PostingDirection {
    DEBIT,
    CREDIT;

    /** The effect of this direction on the named account's balance. */
    public long applyTo(long balanceMinor, long amountMinor) {
        return this == DEBIT ? balanceMinor + amountMinor : balanceMinor - amountMinor;
    }

    public PostingDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
