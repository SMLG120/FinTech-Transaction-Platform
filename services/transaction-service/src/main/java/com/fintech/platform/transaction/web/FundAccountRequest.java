package com.fintech.platform.transaction.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Currency;
import java.util.Locale;

/**
 * A request to add funds to the caller's account.
 *
 * <p>Query parameters rather than a body because funding is a stand-in for a rail rather than a real
 * payment, and it keeps the shape obvious: one amount, one currency, no payee.
 *
 * <p>The amount is a string for the same reason it is on a payment. {@code 100.00} as a JSON number
 * arrives as a double, and a double is where money stops being exact.
 *
 * @param amount the value to add
 * @param currency ISO 4217 code
 */
public record FundAccountRequest(
        @NotBlank(message = "amount is required") @Size(max = 32, message = "amount is at most 32 characters")
        String amount,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter code")
        String currency) {

    /**
     * The currency as a domain type.
     *
     * <p>Assumes {@code currency} is present, because {@code @NotBlank} above has already rejected a
     * request that omits it. That ordering is the whole point of the annotations: without them a
     * missing parameter arrives as null, and the first thing to touch it is {@code toUpperCase}, which
     * is a NullPointerException inside a service method — a 500, a stack trace in the log, and a caller
     * told the platform is broken for having forgotten a query parameter.
     */
    public Currency toCurrency() {
        return Currency.getInstance(currency.toUpperCase(Locale.ROOT));
    }

    /**
     * A canonical string identifying this request.
     *
     * <p>Amount and currency only. Funding has no other inputs, so a request that differs in any
     * remaining respect is the same request — and treating it as a different one would refuse a
     * legitimate retry, which is the one thing the idempotency key exists to allow.
     */
    public String canonicalFingerprint(String path) {
        return String.join(
                "|",
                "POST",
                path,
                amount == null ? "" : amount.trim(),
                currency == null ? "" : currency.toUpperCase(Locale.ROOT));
    }
}
