package com.fintech.platform.settlement.domain;

import java.time.LocalDate;
import java.util.Currency;

/**
 * The name of a settlement cycle, derived rather than generated.
 *
 * <p>Two things follow from deriving it, and both are the reason. A cycle is idempotent by
 * construction: asking twice for the same business date and currency produces the same reference, so a
 * retried close cannot invent a second statement for the same money. And the reference is readable —
 * it appears on a statement, in a break report and in a support conversation, and an operator who has
 * to look a cycle up by UUID is an operator who cannot find the cycle at 3am.
 *
 * <p>Deriving it also means the database's unique index on {@code (business_date, currency_code)} is a
 * backstop rather than the only guard. A unique index alone would catch the race, but only as a
 * constraint violation from the database, which an operator closing a cycle sees as a 500. Deriving the
 * key means the service can look for the cycle first and say "this day is already settled" in words.
 */
public record CycleReference(String value, LocalDate businessDate, Currency currency) {

    private static final String PREFIX = "SETTLE";

    public CycleReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cycle reference is required");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("business date is required; a cycle covering a day must be "
                    + "nameable without a timezone, because the business date an acquirer uses is not the "
                    + "UTC date");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required; a statement is a statement about one "
                    + "currency and a reference that does not name it is ambiguous");
        }
    }

    /**
     * Derives the reference for a business date and currency.
     *
     * @param businessDate the day being settled
     * @param currency the currency being settled
     * @return the reference
     */
    public static CycleReference of(LocalDate businessDate, Currency currency) {
        // ISO-8601 date, so the reference sorts chronologically as text. A statement index that has to be
        // parsed to be ordered is an index somebody will eventually read in the wrong order.
        String value = PREFIX + "-" + businessDate + "-" + currency.getCurrencyCode();
        return new CycleReference(value, businessDate, currency);
    }

    @Override
    public String toString() {
        return value;
    }
}
