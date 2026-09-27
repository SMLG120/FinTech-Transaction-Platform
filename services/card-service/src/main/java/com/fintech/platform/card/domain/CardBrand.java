package com.fintech.platform.card.domain;

/**
 * The card schemes this platform recognises.
 *
 * <p>These are platform labels, not real networks. The platform sends nothing to Visa, Mastercard or
 * anyone else, and the numbers it mints carry no scheme-identifying BIN (see {@link Pan}). The
 * distinction is kept because downstream authorisation rules genuinely differ by product: a debit
 * card draws on a balance, a credit card draws on a limit, and a card that is only ever called "a
 * card" forces every future rule to branch on a string instead of a type.
 *
 * <p>A real integration would add a scheme enum with its own prefix, length and regulatory handling
 * rather than teaching the existing values about a network.
 */
public enum CardBrand {

    /** Draws on the customer's account balance. */
    DEBIT,

    /** Draws on a credit limit and creates a liability. */
    CREDIT;

    /**
     * The digit length of a card number for this scheme.
     *
     * <p>Uniform in the simulation, but carried on the brand so that the column and the minting code
     * are already dimensioned for the real range of 12 to 19 rather than assuming 16 everywhere.
     */
    public int panLength() {
        return Pan.LENGTH;
    }
}
