package com.fintech.platform.transaction.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The kinds of account a posting can touch, and what each one is allowed to do.
 *
 * <p>Two of these are the customer's money and two are the platform's relationship with the outside
 * world, and they are not the same kind of thing:
 *
 * <ul>
 *   <li>{@code CUSTOMER_AVAILABLE} — spendable now. This is the balance a payment is authorised
 *       against, and the one that must never go negative.
 *   <li>{@code CUSTOMER_RESERVED} — authorised but not yet captured. Held rather than debited so that
 *       "available" and "held" are two numbers rather than one number and a flag, which is what makes
 *       "how much can I still spend?" a single indexed read.
 *   <li>{@code PLATFORM_FUNDING} — the source of money entering the platform. Expected to carry a
 *       credit (negative) balance, because that is what a source of funds looks like from inside.
 *   <li>{@code PLATFORM_CLEARING} — where captured money waits. It is deliberately not "revenue" or
 *       "the bank": settlement in Phase 7 is what moves it out to a real rail, and until then the
 *       honest description of where the money is, is that the platform is holding it.
 * </ul>
 *
 * <p>Whether an account may go negative is a property of the type, not a global rule. A single global
 * "no negatives anywhere" would forbid the platform from being a source of funds at all; a single
 * global "anybody may" would permit a customer to overdraw. The rule belongs here where the difference
 * is visible.
 */
public enum LedgerAccountType {

    /** Spendable customer money. May not go negative: this is the limit that actually matters. */
    CUSTOMER_AVAILABLE(true),

    /** Customer money authorised but not captured. May not go negative. */
    CUSTOMER_RESERVED(true),

    /** The world outside the platform, as a source of funds. Expected to hold a credit balance. */
    PLATFORM_FUNDING(false),

    /** Money captured and awaiting settlement out to a real rail. */
    PLATFORM_CLEARING(false);

    private final boolean mustStayNonNegative;

    LedgerAccountType(boolean mustStayNonNegative) {
        this.mustStayNonNegative = mustStayNonNegative;
    }

    /**
     * Whether a posting that would leave this account below zero must be refused.
     *
     * <p>This is the overdraft rule. It is checked inside the same database transaction that holds the
     * account's row lock, which is the only place it can be checked and still be correct: read the
     * balance, compare, write, and do all three under one lock, or two concurrent authorisations both
     * see funds that only one of them can have.
     */
    public boolean mustStayNonNegative() {
        return mustStayNonNegative;
    }

    /** Whether this account belongs to a customer rather than to the platform. */
    public boolean isCustomerAccount() {
        return this == CUSTOMER_AVAILABLE || this == CUSTOMER_RESERVED;
    }

    /** The types held for a customer, in the order a posting should resolve them. */
    public static Set<LedgerAccountType> customerTypes() {
        return EnumSet.of(CUSTOMER_AVAILABLE, CUSTOMER_RESERVED);
    }
}
