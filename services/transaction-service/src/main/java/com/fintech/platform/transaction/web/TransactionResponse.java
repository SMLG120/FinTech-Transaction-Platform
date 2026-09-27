package com.fintech.platform.transaction.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.domain.TransactionStatus;
import java.time.Instant;

/**
 * A payment as it goes back over HTTP.
 *
 * <p><b>The amount is a string on the way out for the same reason it is one on the way in.</b> A JSON
 * number here would be parsed into a double by the client, and a receipt that does not read back as the
 * amount that was charged is worse than one that needs formatting.
 *
 * <p><b>{@code lastFour} is included, {@code cardToken} is not.</b> The last four digits are what every
 * receipt already shows and are not useful for authorising anything. The token <em>is</em> what a network
 * would present to authorise a payment, so echoing it in a response body means it ends up in browser
 * history, a screenshot and a support ticket. A caller that already sent the token has it; a caller that
 * did not has no need of it.
 *
 * <p>{@code declineReason} is present only when there is one, so its absence is meaningful rather than
 * being an empty string that has to be interpreted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        String id,
        String amount,
        String currency,
        TransactionStatus status,
        String cardLastFour,
        String payeeName,
        String payeeReference,
        String declineReason,
        Instant createdAt,
        Instant authorizedAt,
        Instant settledAt,
        Instant reversedAt) {

    /**
     * The last four digits of the card token.
     *
     * <p>Derived from the token rather than stored, because the token is HMAC-SHA256 output and its tail
     * is not the card's last four digits — it is four hex characters of a hash. So this is a stable
     * four-character suffix that identifies which token was used and nothing else, and the field is named
     * for what it is rather than for what a card reader would call it. Showing it lets a customer
     * distinguish two of their own cards; it cannot be used to start an authorisation.
     */
    public static String tokenSuffix(String cardToken) {
        if (cardToken == null || cardToken.length() < 4) {
            return null;
        }
        return cardToken.substring(cardToken.length() - 4);
    }

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id().toString(),
                transaction.amount().toDecimalString(),
                transaction.currency().getCurrencyCode(),
                transaction.status(),
                tokenSuffix(transaction.cardToken()),
                transaction.payee().name(),
                transaction.payee().reference(),
                transaction.declineReason(),
                transaction.createdAt(),
                transaction.authorizedAt(),
                transaction.settledAt(),
                transaction.reversedAt());
    }
}
