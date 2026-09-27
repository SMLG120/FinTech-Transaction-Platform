package com.fintech.platform.dispute.service;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.dispute.error.DisputeErrors;
import java.time.Duration;
import java.util.UUID;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks transaction-service what a payment is, and whether the caller owns it.
 *
 * <p>Synchronous, and that is a deliberate exception to the platform's usual shape — the same one
 * card-service takes for the same reason. A dispute opened on a payment that is not settled, or not
 * the caller's, is a case that must never exist: it would either demand a refund of money that never
 * moved or hand one customer a case on another's payment. There is no later event that could make
 * either right, so the request waits.
 *
 * <p>The ownership question is answered by transaction-service rather than here, because the owner
 * digest lives there and this service must never hold the key that computes it. A 200 means the
 * payment exists and the caller owns it; anything else is a refusal in this service's own
 * vocabulary.
 *
 * <p>Three details keep the synchronous dependency from becoming a way to take the service down —
 * the same three as card-service's eligibility check:
 *
 * <ul>
 *   <li><strong>Timeouts.</strong> A hung transaction-service converts into a hung dispute endpoint
 *       without them. The budget is short: a caller who cannot get an answer in two seconds does not
 *       get a case from this call.
 *   <li><strong>Fail closed.</strong> Any failure, including an unexpected status, raises {@code
 *       TRANSACTION_UNAVAILABLE} and opens nothing.
 *   <li><strong>The caller's identity is forwarded, not re-asserted.</strong> This service signs the
 *       same identity the gateway signed, so transaction-service applies its own ownership rule to a
 *       real caller's request. A hop that minted a fresh identity would turn a compromised
 *       dispute-service into a reader of every payment on the platform.
 * </ul>
 *
 * <p>The response is read as status and amount strings and compared, never deserialised into
 * transaction-service's domain types — the same wire-value discipline as the card eligibility
 * check. A status this service has never heard of fails the comparison rather than a parse, which
 * refuses the dispute instead of 500ing on it.
 */
public class TransactionLookup {

    /** The only payment state that can be disputed. A hold is not money moved yet. */
    private static final String SETTLED = "SETTLED";

    private final RestClient http;

    private final InternalIdentityCodec codec;

    private final Duration timeout;

    public TransactionLookup(RestClient http, InternalIdentityCodec codec, Duration timeout) {
        this.http = http;
        this.codec = codec;
        this.timeout = timeout;
    }

    /** What a dispute needs to know about a payment: its settled state and its figure. */
    public record SettledPayment(UUID transactionId, String amount, String currency) {}

    /**
     * Returns the payment when it exists, is settled, and belongs to the caller.
     *
     * <p>A payment that is not the caller's answers 404 rather than 403, and that is
     * transaction-service's choice carried through rather than second-guessed: its ownership check
     * deliberately does not reveal whether the payment exists, so reaching for another customer's
     * money looks exactly like a typo. A 403 here would confirm the payment is real to someone who
     * has no right to know it.
     *
     * @throws com.fintech.platform.common.error.ApiException 404 when the payment does not exist
     *     or is not the caller's, 422 when it is not settled, 503 when transaction-service cannot
     *     be reached
     */
    public SettledPayment requireSettledOwnedBy(UUID transactionId, InternalIdentity caller) {
        TransactionView payment = fetch(transactionId, caller);
        if (!SETTLED.equals(payment.status())) {
            // Only captures are disputable. A hold is money reserved, not money moved — disputing
            // it would demand a refund of a payment that never completed, and the 422 says the
            // payment's state is the problem rather than the request.
            throw DisputeErrors.PAYMENT_NOT_SETTLED.exception(
                    "Only a settled payment can be disputed; this one is " + payment.status());
        }
        if (payment.amount() == null || payment.amount().isBlank() || payment.currency() == null) {
            throw DisputeErrors.TRANSACTION_UNAVAILABLE.exception(
                    "transaction-service returned a settled payment with no figure", timeoutDetails());
        }
        return new SettledPayment(transactionId, payment.amount(), payment.currency());
    }

    private TransactionView fetch(UUID transactionId, InternalIdentity caller) {
        String url = "/api/v1/transactions/" + transactionId;
        try {
            TransactionView payment = http.get()
                    .uri(url)
                    .headers(headers -> codec.headersFor(caller).forEach(headers::set))
                    .retrieve()
                    .body(TransactionView.class);
            if (payment == null || payment.status() == null) {
                throw DisputeErrors.TRANSACTION_UNAVAILABLE.exception(
                        "transaction-service returned no usable payment for " + transactionId, timeoutDetails());
            }
            return payment;
        } catch (HttpClientErrorException.NotFound e) {
            // No such payment — or not the caller's, which transaction-service deliberately answers
            // the same way. Either is a 404 here: a typo in a request and a reach for somebody
            // else's money both end with no case, and only the caller's own audit trail could tell
            // them apart, which is where that distinction belongs.
            throw DisputeErrors.PAYMENT_NOT_FOUND.exception("No payment with id " + transactionId);
        } catch (RestClientException e) {
            throw DisputeErrors.TRANSACTION_UNAVAILABLE.exception(
                    "transaction-service could not be reached to confirm the payment", timeoutDetails());
        }
    }

    private java.util.Map<String, Object> timeoutDetails() {
        return java.util.Map.of("timeout", timeout.toMillis() + "ms");
    }

    /**
     * The fields this service reads, as strings.
     *
     * <p>{@code status} is declared as a String so an unexpected value fails the comparison above
     * rather than an unknown-enum-value deserialisation error, which would surface as a 500 from
     * this service and look like a dispute-service bug rather than an upstream one.
     */
    record TransactionView(String id, String amount, String currency, String status) {}
}
