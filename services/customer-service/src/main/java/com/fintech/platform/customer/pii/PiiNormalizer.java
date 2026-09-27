package com.fintech.platform.customer.pii;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Canonical forms for the values that get looked up rather than displayed.
 *
 * <p>Every method here exists because of a duplicate-identity failure. A uniqueness check on an email
 * column is only as good as the definition of equality underneath it: without folding case and
 * surrounding whitespace, the same person can register the same mailbox twice by typing it slightly
 * differently, and the constraint that was supposed to prevent that fires on nobody.
 *
 * <p>All three return null for a blank input. Null is the answer for "there is no value here" as
 * opposed to an empty string, and the callers store the result in a blind-index column where a blank
 * and a null have to behave the same way: an empty index would match every other empty index.
 */
public final class PiiNormalizer {

    private PiiNormalizer() {}

    /**
     * @return the canonical mailbox, or null
     */
    public static String email(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        // Lowercased with Locale.ROOT rather than the default locale, because the Turkish locale
        // lowercases "I" to a dotless "ı" and would index an address differently on a Turkish-locale
        // host than on every other one.
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the digits, or null when there are none
     * @implNote Deliberately drops a leading {@code +} and does not convert between national and
     *     international formats. Dropping the {@code +} is the point: it is the one difference a
     *     customer can type differently on a second visit to dodge a duplicate check, and no two
     *     spellings of one number should produce two blind indexes. Converting a national number to an
     *     international one needs the country, and guessing the trunk prefix wrong would merge two
     *     different people into one customer, so a national-format number is returned as its digits
     *     and is not comparable to an international-format one.
     */
    public static String phone(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    /**
     * @return the canonical name: lower case, accent-folded, single-spaced
     * @implNote Accents are folded rather than preserved because two keyboards produce the same name in
     *     composed and decomposed forms, and those are the same person. Preserving them would index
     *     "Zoe" and "Zoë" separately. A name is a display value as well as a lookup one, so callers
     *     keep the original for display and use this only for matching.
     */
    public static String name(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            // Dropping combining marks is what turns a precomposed "ë" and a decomposed "e" + diaeresis
            // into one string. Anything in the "mark" category is exactly that.
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                folded.append(c);
            }
        }
        return collapseWhitespace(folded.toString().toLowerCase(Locale.ROOT));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String collapseWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean previousWasSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                // Skip, and remember: a run of five spaces becomes one space rather than five.
                previousWasSpace = true;
                continue;
            }
            if (previousWasSpace && !result.isEmpty()) {
                result.append(' ');
            }
            previousWasSpace = false;
            result.append(c);
        }
        return result.toString();
    }
}
