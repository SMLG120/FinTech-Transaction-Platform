package com.fintech.platform.transaction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in a single currency, held as an integer count of minor units.
 *
 * <p>Never a {@code double} and never a {@code BigDecimal} in the arithmetic. A {@code double} cannot
 * represent {@code 0.10} exactly, so a ledger built on one accumulates an error that no care at the
 * call sites prevents; a {@code BigDecimal} is exact but pushes a scale decision onto every caller,
 * and callers that choose scale independently eventually disagree. The count of minor units has
 * neither problem, and {@code 1050} is unambiguous in a way {@code 10.50} is not.
 *
 * <p><b>Sign is meaningful and this is not an oversight.</b> A payment amount is positive, but a
 * journal line is signed relative to the account it touches, so this type has to carry negative
 * values. Validation that an amount is positive belongs to the operation being performed, not to the
 * value object: {@link #requirePositive()} exists for the callers that need it.
 *
 * <p><b>Currency is part of the value</b>, not an attribute looked up from an account, because a
 * payment crosses currencies and inferring one from the account is how an amount gets converted by
 * accident. Arithmetic between different currencies is refused rather than converted. This platform
 * has no foreign-exchange rate source, and inventing a rate at a call site would be worse than
 * refusing the operation.
 *
 * <p>Immutable, and every operation returns a new instance.
 *
 * <p>The reasoning behind the representation is in ADR-0007,
 * {@code docs/decisions/0007-double-entry-ledger-and-idempotency.md}.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    /**
     * The widest amount representable, used as a sanity bound.
     *
     * <p>Not {@code Long.MAX_VALUE}. A payment of nine quintillion minor units is not a large payment,
     * it is a bug — an overflowed subtraction or a unit confusion — and allowing it to reach the
     * ledger turns a detectable arithmetic error into an unrepresentable balance. Nine hundred
     * trillion minor units is nine trillion pounds, which is more than this platform will ever move.
     */
    public static final long MAX_MINOR_UNITS = 900_000_000_000_000L;

    /**
     * What {@link Currency#getDefaultFractionDigits()} returns for a currency that has no minor unit.
     *
     * <p>Named here because the platform needs to distinguish "zero decimal places" (JPY, a real
     * currency with no minor unit) from "no fraction digits defined at all" (a private-use code),
     * and {@code -1} in a condition reads as a magic number rather than as that distinction.
     */
    private static final int NO_MINOR_UNITS_DEFINED = -1;

    /**
     * The only accepted shape for an amount on the wire: digits, optionally one decimal point, digits.
     *
     * <p>No sign, because a negative payment amount is a different error with a different meaning and
     * is rejected separately. No exponent, no thousands separator, no currency symbol, no leading or
     * trailing decimal point.
     */
    private static final java.util.regex.Pattern PLAIN_DECIMAL = java.util.regex.Pattern.compile("^\\d+(\\.\\d+)?$");

    /**
     * Validates the components on the way in, so an out-of-range amount or a null currency is
     * impossible to construct rather than merely unlikely.
     */
    public Money {
        if (minorUnits > MAX_MINOR_UNITS || minorUnits < -MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("amount is out of range: " + minorUnits);
        }
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /** An amount of zero in the given currency. */
    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /** An amount from a count of minor units, e.g. {@code minor(1050, GBP)}. */
    public static Money minor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * Parses a decimal string such as {@code "10.50"} into minor units.
     *
     * <p>The wire format is a string rather than a JSON number precisely so that this method is the
     * only place a decimal is ever interpreted. A JSON number is turned into a double by most clients
     * before the service sees it, which reintroduces the error the representation exists to prevent
     * and lets client and server disagree about the value of the same payment.
     *
     * <p><b>Excess precision is rejected, not rounded.</b> {@code "10.501"} in GBP is refused. A
     * ledger that rounds is deciding, silently, what the customer owes, and the decision is invisible
     * because the response still looks well-formed. Refusing means the caller finds out.
     *
     * @throws IllegalArgumentException if the text is not a positive decimal, or carries more
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
            // Checked before BigDecimal, not after, because BigDecimal is more permissive than a money
            // format should be: it accepts "1e3" and "1E3", which are exactly 1000 but read as a
            // formatting mistake from a client. A payments API should reject a shape its author did not
            // intend rather than quietly understand it, so the accepted syntax is digits and at most
            // one decimal point, and nothing else.
            throw new IllegalArgumentException("amount is not a plain decimal: " + decimal);
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount is not a decimal: " + decimal);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + decimal);
        }
        int scale = currency.getDefaultFractionDigits();
        if (scale == NO_MINOR_UNITS_DEFINED) {
            // A currency with no defined minor unit cannot be represented by this type, and guessing
            // a scale for it is how an amount ends up off by a factor of a hundred.
            throw new IllegalArgumentException("currency has no defined minor unit: " + currency.getCurrencyCode());
        }
        if (value.scale() > scale) {
            throw new IllegalArgumentException("amount " + decimal + " has more precision than "
                    + currency.getCurrencyCode() + " has minor units");
        }
        // Exact: the scale is already verified to be within the currency's, so this multiply cannot
        // lose a digit. setScale is here to normalise "10.5" and "10.50" to the same value.
        return new Money(
                value.setScale(scale, RoundingMode.UNNECESSARY)
                        .movePointRight(scale)
                        .longValueExact(),
                currency);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    /** @throws IllegalArgumentException if this amount is zero or negative */
    public Money requirePositive() {
        if (!isPositive()) {
            throw new IllegalArgumentException("amount must be positive: " + this);
        }
        return this;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negated() : this;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("cannot combine " + currency.getCurrencyCode() + " with "
                    + other.currency.getCurrencyCode() + "; this platform does not convert currency");
        }
    }

    /**
     * @throws IllegalArgumentException if the currencies differ, which makes an ordering between them
     *     meaningless
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /**
     * Renders this amount as a decimal string in the currency's own scale.
     *
     * <p>Not {@code NumberFormat}, which applies locale-dependent grouping and symbol placement and
     * would make the API's output depend on the server's default locale. A payment API's amount is a
     * value, not a presentation.
     */
    public String toDecimalString() {
        int scale = currency.getDefaultFractionDigits();
        if (scale == NO_MINOR_UNITS_DEFINED) {
            // Cannot happen: the constructor does not validate currency, but nothing can build one of
            // these without naming a Currency, and parse() refuses the codes with no scale. Guarded
            // anyway because rendering "10.5" for a currency that means "1000" is worse than throwing.
            throw new IllegalStateException("currency has no defined minor unit: " + currency.getCurrencyCode());
        }
        if (scale == 0) {
            return Long.toString(minorUnits);
        }
        boolean negative = minorUnits < 0;
        // Long.MIN_VALUE is unreachable: the constructor bounds the magnitude.
        long absolute = Math.abs(minorUnits);
        String digits = Long.toString(absolute);
        StringBuilder out = new StringBuilder(digits.length() + scale + 1);
        if (negative) {
            out.append('-');
        }
        if (digits.length() <= scale) {
            out.append("0.");
            out.append("0".repeat(scale - digits.length()));
            out.append(digits);
        } else {
            out.append(digits, 0, digits.length() - scale);
            out.append('.');
            out.append(digits, digits.length() - scale, digits.length());
        }
        return out.toString();
    }

    private static long pow10(int scale) {
        long result = 1;
        for (int i = 0; i < scale; i++) {
            result *= 10;
        }
        return result;
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency.getCurrencyCode();
    }
}
