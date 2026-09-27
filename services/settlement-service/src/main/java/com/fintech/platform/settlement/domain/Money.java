package com.fintech.platform.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money in one currency, as an integer count of minor units.
 *
 * <p>The same representation and wire format as transaction-service's and fraud-service's {@code Money},
 * and a deliberate third implementation rather than a shared class, for the reason ADR-0007 gives: a
 * service that shares its money arithmetic with another service's jar cannot change its own rounding
 * without a coordinated release.
 *
 * <p>What makes this one different from fraud-service's is arithmetic. A statement is a sum, a
 * difference between two figures is a subtraction, and a fee is a percentage of a gross — so unlike the
 * fraud engine, which must never have an opinion about the value of a payment, this service is
 * constantly doing arithmetic on money. That is the whole job, and it is why the operations live here
 * rather than in the caller: {@code add} and {@code subtract} refuse to cross currencies, which is the
 * mistake a statement total silently makes when two currencies meet in one period.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    /**
     * A sanity bound, matching the other services'. An amount past this is an overflow or a unit
     * confusion, and a statement carrying one is a statement that cannot be reconciled against anything.
     */
    public static final long MAX_MINOR_UNITS = 900_000_000_000_000L;

    /** What {@link Currency#getDefaultFractionDigits()} returns for a code that defines no minor unit. */
    private static final int NO_MINOR_UNITS_DEFINED = -1;

    /**
     * Digits, optionally one decimal point, digits.
     *
     * <p>No sign, no exponent, no separators, no currency symbol. Checked before {@link BigDecimal},
     * which would accept {@code 1e3} — a formatting mistake from a client that this service should
     * refuse rather than understand.
     */
    private static final Pattern DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    public Money {
        Objects.requireNonNull(currency, "currency is required; money without a currency is a number");
        if (minorUnits > MAX_MINOR_UNITS || minorUnits < -MAX_MINOR_UNITS) {
            throw new IllegalArgumentException(
                    "amount is past the sanity bound of " + MAX_MINOR_UNITS + " minor units: " + minorUnits
                            + ". This is an overflow or a minor-unit/pounds confusion, and a statement that "
                            + "carries one cannot be reconciled against anything.");
        }
    }

    /**
     * Parses a decimal string in a currency.
     *
     * @param decimal the amount as sent on the wire, such as {@code 1200.00}
     * @param currency the currency the amount is in
     * @return the amount in minor units
     * @throws IllegalArgumentException if the text is not a plain positive decimal, or carries more
     *     decimal places than the currency has minor units
     */
    public static Money parse(String decimal, Currency currency) {
        Objects.requireNonNull(decimal, "amount is required");
        if (!DECIMAL.matcher(decimal).matches()) {
            throw new IllegalArgumentException(
                    "amount must be plain digits with at most one decimal point, got '" + decimal + "'");
        }
        int digits = fractionDigits(currency);
        BigDecimal value = new BigDecimal(decimal);
        if (value.stripTrailingZeros().scale() > digits) {
            throw new IllegalArgumentException("currency " + currency.getCurrencyCode() + " has " + digits
                    + " decimal place(s), so '" + decimal + "' is a more precise amount than the currency can "
                    + "represent. Rounding it here would make the statement disagree with the ledger by less "
                    + "than a penny, which is exactly the kind of difference a reconciliation then cannot "
                    + "explain.");
        }
        return new Money(
                value.movePointRight(digits)
                        .setScale(0, RoundingMode.UNNECESSARY)
                        .longValueExact(),
                currency);
    }

    /**
     * The number of minor-unit digits a currency has.
     *
     * @param currency the currency to inspect
     * @return the digit count, or 0 for a currency that defines no minor unit
     */
    private static int fractionDigits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits == NO_MINOR_UNITS_DEFINED ? 0 : digits;
    }

    /**
     * Renders as the decimal string the platform uses everywhere else.
     *
     * <p>The same string the ledger stored, so a statement line and the journal entry behind it can be
     * compared as text by somebody reading both, not only as a long by a program.
     *
     * @return the amount as, for example, {@code 1200.00}
     */
    public String toDecimal() {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currency))
                .setScale(fractionDigits(currency))
                .toPlainString();
    }

    /**
     * Adds another amount.
     *
     * @param other the amount to add, which must be in the same currency
     * @return the sum
     * @throws IllegalArgumentException if the currencies differ
     */
    public Money add(Money other) {
        requireSameCurrency(other, "add");
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * Subtracts another amount.
     *
     * @param other the amount to subtract, which must be in the same currency
     * @return the difference
     * @throws IllegalArgumentException if the currencies differ
     */
    public Money subtract(Money other) {
        requireSameCurrency(other, "subtract");
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * Negates the amount, for a refund.
     *
     * @return the same amount with its sign reversed
     */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    /**
     * Whether this amount is below zero.
     *
     * <p>Used by the statement builder, not by the ledger: a capture is never negative and a reversal
     * always is, so the sign is how a line says which kind it is without being asked.
     *
     * @return true when the amount is negative
     */
    public boolean isNegative() {
        return minorUnits < 0;
    }

    /**
     * Whether this amount is exactly zero.
     *
     * @return true when the amount is zero
     */
    public boolean isZero() {
        return minorUnits == 0;
    }

    /**
     * Refuses arithmetic across currencies.
     *
     * @param other the amount being combined
     * @param operation the operation's name, for the message
     */
    private void requireSameCurrency(Money other, String operation) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("refusing to " + operation + " " + toDecimal() + " "
                    + currency.getCurrencyCode() + " to " + other.toDecimal() + " "
                    + other.currency.getCurrencyCode() + "; a statement total in one currency is a statement "
                    + "about one currency, and adding two together produces a number that means nothing");
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other, "compare");
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toDecimal() + " " + currency.getCurrencyCode();
    }
}
