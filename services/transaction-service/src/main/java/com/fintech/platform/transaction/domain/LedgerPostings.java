package com.fintech.platform.transaction.domain;

import java.util.List;

/**
 * The concrete postings this platform makes, written out once.
 *
 * <p>Each of these is a balanced pair, and the shape is not obvious enough to leave to a call site. A
 * hold credits {@code CUSTOMER_AVAILABLE} because the customer's spendable money goes down, and debits
 * {@code CUSTOMER_RESERVED} because held money goes up. Read as "money out of the customer's pocket"
 * both directions look backwards, which is why {@link PostingDirection} insists the direction is
 * relative to the account.
 *
 * <p>Concentrating them here has a second benefit: a reader can check the whole money movement of the
 * platform against four short methods, and a test can assert conservation across all of them, instead
 * of the behaviour being spread across a service where it is only visible by reading every call site.
 */
public final class LedgerPostings {

    private LedgerPostings() {}

    /**
     * Funds a customer account from outside the platform.
     *
     * <p>Credits {@code PLATFORM_FUNDING} and debits {@code CUSTOMER_AVAILABLE}: the world outside
     * loses the money and the customer gains it, and the platform's funding account takes the credit
     * position that a source of funds always has.
     *
     * <p>This stands in for an external funding rail. Phase 7 replaces it with a real one; until then
     * it exists so that a payment can be authorised at all, and it is deliberately the only way value
     * enters the ledger.
     */
    public static Posting funding(Money amount) {
        return Posting.balanced(
                JournalEntryKind.FUNDING,
                List.of(
                        new Posting.Leg(LedgerAccountType.PLATFORM_FUNDING, PostingDirection.CREDIT, amount),
                        new Posting.Leg(LedgerAccountType.CUSTOMER_AVAILABLE, PostingDirection.DEBIT, amount)));
    }

    /**
     * Reserves spendable money for an authorised payment.
     *
     * <p>Nothing has left the platform and the customer has spent nothing, but the amount stops being
     * spendable, which is the entire effect an authorisation is supposed to have.
     */
    public static Posting hold(Money amount) {
        return Posting.balanced(
                JournalEntryKind.HOLD,
                List.of(
                        new Posting.Leg(LedgerAccountType.CUSTOMER_AVAILABLE, PostingDirection.CREDIT, amount),
                        new Posting.Leg(LedgerAccountType.CUSTOMER_RESERVED, PostingDirection.DEBIT, amount)));
    }

    /**
     * Captures previously reserved money.
     *
     * <p>Leaves {@code PLATFORM_CLEARING}, not a revenue or settlement account, because at the moment
     * of capture the platform is holding the money and has not sent it anywhere. Phase 7 moves it.
     */
    public static Posting capture(Money amount) {
        return Posting.balanced(
                JournalEntryKind.CAPTURE,
                List.of(
                        new Posting.Leg(LedgerAccountType.CUSTOMER_RESERVED, PostingDirection.CREDIT, amount),
                        new Posting.Leg(LedgerAccountType.PLATFORM_CLEARING, PostingDirection.DEBIT, amount)));
    }

    /** Returns reserved money to spendable, for an authorisation that was never captured. */
    public static Posting release(Money amount) {
        return Posting.balanced(
                JournalEntryKind.RELEASE,
                List.of(
                        new Posting.Leg(LedgerAccountType.CUSTOMER_RESERVED, PostingDirection.CREDIT, amount),
                        new Posting.Leg(LedgerAccountType.CUSTOMER_AVAILABLE, PostingDirection.DEBIT, amount)));
    }
}
