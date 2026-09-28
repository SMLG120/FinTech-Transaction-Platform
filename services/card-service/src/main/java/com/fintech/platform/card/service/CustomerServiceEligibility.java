package com.fintech.platform.card.service;

import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.resilience.OutboundGuard;
import java.time.Duration;
import java.util.UUID;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks customer-service whether a customer is approved.
 *
 * <p>Synchronous, and that is a deliberate exception to the platform's usual shape. ADR-0004 makes
 * fraud scoring asynchronous so a slow check degrades a payment rather than blocking it, and the same
 * reasoning would apply here if the answer could arrive late. It cannot. The caller is standing in
 * front of a "your card is ready" screen, and a card issued before the identity check finished is a card
 * issued to somebody the platform has not verified. There is no later event that could make it right,
 * so the request waits.
 *
 * <p>Three details are what keep a synchronous dependency from becoming a way to take the service down.
 *
 * <ul>
 * <li><strong>Timeouts — and a breaker behind them.</strong> Without them a hung customer-service converts into a hung card
 *       endpoint, and the thread pool exhausts on requests that can never be answered. The budget is
 *       short deliberately: a caller who cannot get an answer in two seconds does not get a card from
 *       this call. The {@link OutboundGuard} on top retries once on a transport failure or 5xx and
 *       then opens the circuit, so a downed dependency fails callers in microseconds rather than
 *       holding a thread each for the full timeout. Definitive 4xx answers neither retry nor trip it.
 *   <li><strong>Fail closed.</strong> Any failure, including an unexpected status, raises
 *       {@link CardErrorCodes#ELIGIBILITY_UNAVAILABLE} and issues nothing.
 *   <li><strong>The caller's identity is forwarded, not re-asserted.</strong> This service signs the
 *       same identity the gateway signed, so customer-service applies its own authorisation to a real
 *       caller's request. A service-to-service hop that minted a fresh identity with elevated roles
 *       would turn a compromised card-service into a reader of every customer profile in the platform.
 * </ul>
 *
 * <p>The response is read as a plain string and compared against {@code "APPROVED"} rather than
 * deserialised into customer-service's {@code KycStatus}. Two services that each own a database and a
 * domain model should not share a Java type for it: a shared enum would make a rename on one side a
 * compile-time break on the other, and would pull one service's classpath into the other's. The status
 * name is a published wire value and is a legitimate contract between them.
 */
public class CustomerServiceEligibility implements CardIssuanceEligibility {

    /**
     * The only identity-check status that permits a card.
     *
     * <p>A wire value compared as a string, so a status this service has never heard of is a refusal
     * rather than a parse failure. Fail-safe by default: an unrecognised status must never be the
     * thing that issues a card.
     */
    private static final String APPROVED = "APPROVED";

    private final RestClient http;
    private final InternalIdentityCodec codec;
    private final Duration timeout;
    private final OutboundGuard guard;

    public CustomerServiceEligibility(
            RestClient http, InternalIdentityCodec codec, Duration timeout, OutboundGuard guard) {
        this.http = http;
        this.codec = codec;
        this.timeout = timeout;
        this.guard = guard;
    }

    @Override
    public boolean isApproved(UUID customerId, InternalIdentity caller) {
        String url = "/api/v1/customers/" + customerId;
        try {
            EligibilityResponse response = guard.execute(
                    () -> http.get()
                            .uri(url)
                            .headers(headers -> codec.headersFor(caller).forEach(headers::set))
                            .retrieve()
                            .body(EligibilityResponse.class),
                    () -> CardErrorCodes.ELIGIBILITY_UNAVAILABLE.exception(
                            "customer-service is not answering; failing closed without issuing", timeoutDetails()));

            if (response == null || response.kycStatus() == null) {
                // A 2xx with no usable body is a contract violation, not an approval. Treating the
                // empty field as "not approved" would be indistinguishable from a real refusal, and
                // the operator would be chasing the wrong outage.
                throw CardErrorCodes.ELIGIBILITY_UNAVAILABLE.exception(
                        "customer-service returned no identity-check status for the cardholder", timeoutDetails());
            }
            return APPROVED.equals(response.kycStatus());
        } catch (HttpClientErrorException.NotFound e) {
            // A customer id nobody has. Distinct from "not approved", and the caller is owed the
            // difference: a typo in a request is not the same event as a customer failing a check.
            // Re-mapped into this service's own vocabulary rather than propagated, so that card-service
            // does not have to know what customer-service calls things.
            throw CardErrorCodes.HOLDER_NOT_FOUND.exception();
        } catch (HttpClientErrorException.Forbidden e) {
            // The caller's own identity was rejected by customer-service, which means the customer id
            // is not theirs. Surfacing this as "eligibility unavailable" would turn an attempted
            // access to somebody else's profile into a 503, telling the caller to retry and hiding
            // the fact that the answer is no.
            throw CardErrorCodes.NOT_THE_CARDHOLDER.exception();
        } catch (RestClientException e) {
            throw CardErrorCodes.ELIGIBILITY_UNAVAILABLE.exception(
                    "customer-service could not be reached to confirm cardholder eligibility", timeoutDetails());
        }
    }

    private java.util.Map<String, Object> timeoutDetails() {
        return java.util.Map.of("timeout", timeout.toMillis() + "ms");
    }

    /**
     * The one field this service reads.
     *
     * <p>{@code kycStatus} is declared as a String so an unexpected value fails the comparison above
     * rather than an unknown-enum-value deserialisation error, which would surface as a 500 from this
     * service and look like a card-service bug rather than an upstream one.
     */
    record EligibilityResponse(String kycStatus) {}
}
