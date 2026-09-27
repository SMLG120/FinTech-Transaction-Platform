package com.fintech.platform.customer.pii;

import java.time.LocalDate;

/**
 * Reductions of personal data for places that do not need the value itself.
 *
 * <p>Written to be safe on anything, including input it does not recognise. A masker that passes an
 * unparseable value through unchanged is worse than no masker at all, because the code calling it looks
 * protected; so a shape that cannot be parsed comes back fully masked rather than echoed.
 *
 * <p>Star counts reveal length, which is a small leak and an accepted one. Preserving length keeps the
 * output the same shape as the input, which matters more in practice: a masked value that is visibly
 * shorter gets treated as a different kind of thing by whatever consumes it.
 */
public final class PiiMasker {

    private PiiMasker() {}

    /**
     * Keeps the first letter of each word and stars the rest.
     *
     * <p>Enough to tell two similarly-named customers apart in a support conversation, which is the job
     * this does. Note that it is not enough to do that reliably for a common name, and the alternative
     * of keeping more of the name to compensate would make it a name-disclosure tool.
     */
    public static String name(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String[] words = plaintext.trim().split("\\s+");
        StringBuilder masked = new StringBuilder(plaintext.length());
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                masked.append(' ');
            }
            String word = words[i];
            if (word.isEmpty()) {
                continue;
            }
            masked.append(word.charAt(0)).append(stars(word.length() - 1));
        }
        return masked.toString();
    }

    /**
     * Keeps the first character of the local part and the whole domain.
     *
     * <p>The domain survives because it identifies the mail provider rather than the person, and a
     * support agent genuinely needs it to recognise which mailbox they are looking at. A local part
     * with no {@code @} is not an address this can reason about, so it is fully masked.
     */
    public static String email(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        int at = plaintext.indexOf('@');
        if (at <= 0 || at == plaintext.length() - 1) {
            return maskAll(plaintext.trim());
        }
        String local = plaintext.substring(0, at);
        String domain = plaintext.substring(at);
        return local.charAt(0) + stars(local.length() - 1) + domain;
    }

    /**
     * Keeps the last two digits and stars the rest.
     *
     * <p>Two digits is what a person can read aloud to confirm "is that the number you mean" without
     * being a means of identifying the number to somebody listening. A number of two digits or fewer
     * has no such safe tail, so it is fully masked.
     */
    public static String phone(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        // Formatting characters are dropped so the count is of digits, which is what the reader is
        // counting. "+447700900123" and "447700900123" are the same number and must mask identically.
        String digits = PiiNormalizer.phone(plaintext);
        if (digits == null) {
            return maskAll(plaintext.trim());
        }
        if (digits.length() <= 2) {
            return maskAll(digits);
        }
        return stars(digits.length() - 2) + digits.substring(digits.length() - 2);
    }

    /**
     * @return the year of birth alone
     * @implNote The year is enough for a support agent to recognise a customer and not enough to answer
     *     a knowledge-based authentication question, which is the line this is trying to sit on. It is
     *     a {@code String} rather than a {@code LocalDate} so a caller cannot mistake a reduced date for
     *     a real one and store it as the customer's date of birth.
     */
    public static String birthYear(LocalDate dateOfBirth) {
        return dateOfBirth == null ? null : Integer.toString(dateOfBirth.getYear());
    }

    private static String stars(int count) {
        return count <= 0 ? "" : "*".repeat(count);
    }

    private static String maskAll(String value) {
        return value.isEmpty() ? "" : "*".repeat(value.length());
    }
}
