package com.fintech.platform.transaction.web;

import com.fintech.platform.transaction.domain.Counterparty;
import com.fintech.platform.transaction.domain.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Currency;
import java.util.Locale;

/**
 * A payment request as it arrives over HTTP.
 *
 * <p><b>The amount is a string, and that is not a JSON detail.</b> {@code 40.00} in JSON is a number, and
 * a JSON number is parsed into a double by most clients before this service ever sees it, at which point
 * {@code 40.00} may have become {@code 39.999999999999996} and ten pence has been lost with no error
 * anywhere. A string is carried exactly. A caller who sends a number gets a 400 rather than a payment
 * that is wrong by a fraction of a penny in a way nobody will ever notice.
 *
 * <p><b>The currency is required and explicit.</b> Inferring it from a customer's default would make
 * which account is debited depend on state this service does not control, and a payment that means
 * "forty pounds" in one request and "forty dollars" in another is a bug that only shows up in
 * reconciliation.
 *
 * <p>No card number field, and none derivable. {@code cardToken} is the only way to name a card, and it
 * is a 64-character token that this service cannot convert back into a number. See ADR-0006.
 */
public record CreateTransactionRequest(
        @NotBlank(message = "amount is required") @Size(max = 32, message = "amount is at most 32 characters")
        String amount,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter code")
        String currency,

        @NotBlank(message = "cardToken is required") @Size(max = 64, message = "cardToken is at most 64 characters")
        String cardToken,

        @NotBlank(message = "payeeName is required")
        @Size(max = Counterparty.MAX_NAME_LENGTH, message = "payeeName is too long")
        String payeeName,

        @Size(max = Counterparty.MAX_REFERENCE_LENGTH, message = "payeeReference is too long")
        String payeeReference,

        @Pattern(
                regexp = "WEB|MOBILE|POS|ATM|MERCHANT_API",
                message = "channel must be one of WEB, MOBILE, POS, ATM, MERCHANT_API")
        String channel,

        /**
         * An opaque identifier the client keeps stable for one device.
         *
         * <p>Free text, capped, and never interpreted. It is hashed before it leaves this service — see
         * {@link com.fintech.platform.transaction.domain.FraudContext} — so nothing downstream can read
         * it, and a client that invents a new value for every payment loses the new-device and
         * shared-device rules rather than gaining anything.
         */
        @Size(max = 128, message = "deviceFingerprint is too long")
        String deviceFingerprint) {

    /**
     * Parses the amount into the domain type.
     *
     * <p>Every precision and range rule lives in {@link Money#parse}, so the wire format and the domain
     * cannot disagree about what a valid amount is. This method only supplies the currency.
     */
    public Money toMoney() {
        return Money.parse(amount, toCurrency());
    }

    public Currency toCurrency() {
        return Currency.getInstance(currency.toUpperCase(Locale.ROOT));
    }

    public Counterparty toCounterparty() {
        return new Counterparty(payeeName, payeeReference);
    }

    /**
     * A canonical string identifying this request, for the idempotency fingerprint.
     *
     * <p>Built from the fields in a fixed order with a separator, not from a serialised form of the
     * object. Two clients sending the same payment in a different field order, or with different
     * insignificant whitespace, produce the same string here and are correctly recognised as the same
     * request. If it were built by re-serialising the record, those two would hash differently and the
     * second would be refused with a 409 for a difference neither client can see.
     *
     * <p>{@code |} as the separator because it cannot occur in any of the fields: they are amounts,
     * three-letter currency codes, hex tokens and free-text names, and a name containing a pipe would
     * have to be escaping something.
     *
     * <p><b>The channel and device fingerprint are in it</b>, and that is a decision rather than an
     * oversight. They are not decoration: they are part of what was authorised, and a retry that arrives
     * with the same key and a different device is not the request the first attempt answered. Excluding
     * them would mean a client that retried from a different device — which is exactly what a fraudster
     * does after a network error — got the first attempt's response back, and the stored decision would
     * silently describe a device that was never used.
     */
    public String canonicalFingerprint(String method, String path) {
        return String.join(
                "|",
                method.toUpperCase(Locale.ROOT),
                path,
                amount.trim(),
                currency.toUpperCase(Locale.ROOT),
                cardToken,
                payeeName.trim(),
                payeeReference == null ? "" : payeeReference.trim(),
                channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT),
                deviceFingerprint == null ? "" : deviceFingerprint.trim());
    }
}
