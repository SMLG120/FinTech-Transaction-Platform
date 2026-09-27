package com.fintech.platform.notification.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money in one currency, as an integer count of minor units.
 *
 * <p>The same representation and the same wire format as the other services' {@code Money}, and a
 * deliberate further implementation of it rather than a shared class. The format — a decimal string
 * in, a count of minor units out, no floating point anywhere — is fixed everywhere, and each service
 * parses it with its own exact code, because a service that shares its money arithmetic with another
 * service's jar cannot change its own behaviour without a coordinated release.
 *
 * <p>What this service does with an amount is print it in a message a customer will read, so that is
 * all this type offers. There is no arithmetic here, and no ledger.
 */
public record Money(long minorUnits, Currency currency) {

    /** A sanity bound, matching the other services'. */
    public static final long MAX_MINOR_UNITS = 900_000_000_000_000L;

    /** What {@link Currency#getDefaultFractionDigits()} returns for a code that defines no minor unit. */
    private static final int NO_MINOR_UNITS_DEFINED = -1;

    /**
     * Digits, optionally one decimal point, digits.
     *
     * <p>No sign, no exponent, no separators, no currency symbol. Checked before {@code BigDecimal},
     * which would accept {@code 1e3} — a formatting mistake this service should refuse rather than
     * understand, because a misunderstood amount becomes a message about the wrong figure.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^\\d+(\\.\\d+)?$");

    public Money {
        if (minorUnits > MAX_MINOR_UNITS || minorUnits < -MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("amount is out of range: " + minorUnits);
        }
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /**
     * Parses the {@code amount} field of a transaction event.
     *
     * <p>Excess precision is refused, exactly as the producing service refuses it. Rounding here
     * would tell the customer a figure the payment never had.
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

    private static int fractionDigits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits == NO_MINOR_UNITS_DEFINED) {
            throw new IllegalArgumentException("currency " + currency.getCurrencyCode() + " defines no minor unit");
        }
        return digits;
    }

    /** The amount as it appeared on the wire, for a message a human will read. */
    public String toDecimalString() {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currency)).toPlainString();
    }
}
