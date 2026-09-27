package com.fintech.platform.card.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * A primary account number, held in memory only and never persisted anywhere.
 *
 * <p>This class exists for exactly as long as it takes to mint a number, derive its last four digits
 * for display, hand the digits to {@link com.fintech.platform.card.tokenisation.CardTokenizer} and
 * show the holder once. Nothing here writes, and no field of {@link Card} is typed as a {@code Pan},
 * so there is no column for a PAN to leak into. See ADR-0006 for the schema-level argument and
 * {@code docs/security.md} for the control this implements.
 *
 * <p>Two properties are enforced here rather than left to discipline downstream, because both failure
 * modes are silent and both are severe:
 *
 * <ul>
 *   <li>{@link #toString()} is masked. A {@code Pan} that appears in a log line, an exception message
 *       or a {@code String.format} call reveals nothing, so no logging configuration or refactor can
 *       turn this into a disclosure. Overriding {@code toString} is the only place the danger can be
 *       closed for good; a {@code @ToString.Exclude} annotation merely asks every future reader to
 *       remember.
 *   <li>Nothing is retained after issue. The platform deliberately has no "look up a cardholder's
 *       number" operation, because an issuer that can replay a number can leak every number it ever
 *       issued.
 * </ul>
 *
 * <p>Numbers are minted in the {@code 9} range. Per ISO/IEC 7812 ID-1 a leading {@code 9} is reserved
 * for national use and is not assigned to any major card scheme, so a number generated here cannot
 * collide with a real issued card. That matters even though the platform never talks to a network: a
 * demonstration that minted from the {@code 4}/{@code 5}/{@code 3} ranges could produce a digit string
 * that is a genuine account somewhere, and the value of being unable to is that the failure mode of
 * misdirecting this build at a real acquirer is not "a card number appears in a fixture".
 */
public final class Pan {

    /** Digits in every number this platform mints. Sized for the longest real scheme as well. */
    public static final int LENGTH = 16;

    /** ISO/IEC 7812 national-use range. See the class comment for why this is not 4, 5 or 3. */
    private static final String N_PREFIX = "9";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String digits;

    private Pan(String digits) {
        this.digits = digits;
    }

    /**
     * Mints a Luhn-valid number in the national-use range.
     *
     * <p>{@link SecureRandom} rather than {@code Random} because this is a secret that must be
     * unguessable, and a seeded PRNG would let anyone who has seen two numbers derive the sequence and
     * therefore the next one. The prefix is fixed and the body is random, so the only entropy is in the
     * 11 body digits, which is 10^11 possibilities and ample for a simulation.
     *
     * <p>The check digit is computed after the body is drawn, so every candidate is valid by
     * construction and the mint path never has to retry.
     */
    public static Pan mint() {
        StringBuilder body = new StringBuilder(LENGTH - 1);
        body.append(N_PREFIX);
        while (body.length() < LENGTH - 1) {
            body.append(RANDOM.nextInt(10));
        }
        return new Pan(body.toString() + checkDigitFor(body.toString()));
    }

    /** The digits, unformatted. Only for the tokeniser and for the one-time issue response. */
    public String digits() {
        return digits;
    }

    /** The final four digits, which are all that may be stored or shown thereafter. */
    public String last4() {
        return digits.substring(digits.length() - 4);
    }

    /**
     * Grouped in fours, the way a cardholder reads a number printed on plastic.
     *
     * <p>Still a secret: the spaces are cosmetic. Only the response that carries this is allowed to
     * leave the process, and the value is not retained.
     */
    public String formatted() {
        StringBuilder grouped = new StringBuilder(LENGTH + 3);
        for (int i = 0; i < digits.length(); i += 4) {
            if (i > 0) {
                grouped.append(' ');
            }
            grouped.append(digits, i, Math.min(i + 4, digits.length()));
        }
        return grouped.toString();
    }

    /**
     * Masked for every string rendering.
     *
     * <p>Deliberately not delegating to {@link #formatted()}, so that a log statement cannot be
     * "just temporarily" widened back to the full number by editing one method.
     */
    @Override
    public String toString() {
        return "Pan[**** **** **** " + last4() + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pan pan && pan.digits.equals(digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits);
    }

    /**
     * The Luhn check digit for a partial number.
     *
     * <p>Package-private and static so the algorithm can be tested against published vectors without a
     * {@link SecureRandom} in the way. The variant used here doubles every second digit from the right
     * and subtracts 9 from any result above 9, which is what the Luhn checksum is.
     */
    static int checkDigitFor(String partial) {
        int sum = 0;
        boolean doubling = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int digit = partial.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return (10 - (sum % 10)) % 10;
    }

    /**
     * Whether a digit string satisfies Luhn.
     *
     * <p>Exists as a public predicate rather than a validating factory because the platform never
     * accepts a PAN from anywhere: the only numbers it will ever see are the ones it minted. This is
     * the assertion that they are well formed, and it is deliberately shaped so a future "bring your
     * own card" endpoint has a check waiting for it rather than reaching for a regex.
     */
    public static boolean isLuhnValid(String candidate) {
        if (candidate == null
                || candidate.length() != LENGTH
                || !candidate.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            int digit = candidate.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
