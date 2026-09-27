package com.fintech.platform.fraud.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money in one currency, as an integer count of minor units.
 *
 * <p>The same representation and the same wire format as transaction-service's {@code Money}, and a
 * deliberate second implementation of it rather than a shared class. ADR-0007 fixes the <em>format</em> —
 * a decimal string in, a count of minor units out, no floating point anywhere — and each service parses
 * it with its own exact code, because a service that shares its money arithmetic with another service's
 * jar is a service that cannot change its own rounding behaviour without a coordinated release. Two
 * hundred lines of duplicated parsing is a cheaper price than that coupling.
 *
 * <p>What this service does with an amount is compare it against a threshold and print it in a risk
 * explanation, so that is all this type offers. There is no arithmetic here, and no ledger: fraud scoring
 * must never be the component that has an opinion about the value of a payment.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    /**
     * A sanity bound, matching transaction-service's. An amount past this is an overflow or a unit
     * confusion, and a risk threshold compared against a nonsense amount is a rule that silently never
     * fires.
     */
    public static final long MAX_MINOR_UNITS = 900_000_000_000_000L;

    /** What {@link Currency#getDefaultFractionDigits()} returns for a code that defines no minor unit. */
    private static final int NO_MINOR_UNITS_DEFINED = -1;

    /**
     * Digits, optionally one decimal point, digits.
     *
     * <p>No sign, no exponent, no separators, no currency symbol. Checked before {@code BigDecimal},
     * which would accept {@code 1e3} — a formatting mistake from a client that this service should
     * refuse rather than understand.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^\\d+(\\.\\d+)?$");

    public Money {
        if (minorUnits > MAX_MINOR_UNITS || minorUnits < -MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("amount is out of range: " + minorUnits);
        }
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money minor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * Parses the {@code amount} field of a transaction event.
     *
     * <p>Excess precision is refused, exactly as the producing service refuses it. If this side rounded
     * {@code "10.501"} to {@code 10.50} the two services would disagree about what the payment was, and
     * the rule thresholds would be evaluated against a number the customer never agreed to.
     *
     * @throws IllegalArgumentException if the text is not a plain positive decimal, or carries more
     *     precision than the currency has minor units
     */
    public static Money parse(String decimal, Currency currency) {
        Objects.requireNonNull(decimal, "decimal must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        String text = decimal.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("amount must not be blank");
        }
        if (!PLAIN_DECIMAL.matcher(text).matches()) {
            throw new IllegalArgumentException("amount is not a plain decimal: " + decimal);
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount is not a decimal: " + decimal);
        }
        int digits = fractionDigits(currency);
        BigDecimal scaled = value.setScale(digits, RoundingMode.UNNECESSARY);
        return new Money(scaled.movePointRight(digits).longValueExact(), currency);
    }

    /**
     * How many minor units this currency has.
     *
     * <p>Not defaulted to 2. A currency with no defined fraction digits is a configuration mistake, and
     * assuming two of them would put a score threshold in the wrong place for a currency nobody has
     * configured deliberately.
     */
    private static int fractionDigits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits == NO_MINOR_UNITS_DEFINED) {
            throw new IllegalArgumentException("currency " + currency.getCurrencyCode() + " defines no minor unit");
        }
        return digits;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    /** The amount as it appeared on the wire, for a risk explanation a human will read. */
    public String toDecimalString() {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currency)).toPlainString();
    }

    /**
     * Compares by magnitude.
     *
     * <p>Refuses to compare across currencies rather than converting. This platform has no exchange-rate
     * source, so a conversion here would be a made-up rate that quietly decided whether a payment looked
     * large. A rule configured in GBP simply does not fire on a EUR payment, and the decision records
     * that the amount was not assessed against it.
     */
    @Override
    public int compareTo(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot compare " + currency.getCurrencyCode() + " with " + other.currency.getCurrencyCode());
        }
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency.getCurrencyCode();
    }
}
