package com.fintech.platform.card.service;

import com.fintech.platform.common.identity.InternalIdentity;
import java.util.UUID;

/**
 * Whether a named customer is allowed to hold a card.
 *
 * <p>An interface with one method because it exists to be a seam, not to be mocked in every test. The
 * real implementation asks customer-service, which owns identity state; a test implementation is a
 * lambda. That distinction matters here more than usual, because the alternative — card-service
 * deciding eligibility from something it already holds — is the mistake this port exists to prevent.
 *
 * <p>card-service has no copy of a customer's identity-check status, and there is no sound way for it
 * to derive one. It holds a token and a keyed digest of a subject id, which is everything a card
 * needs and nothing a compliance decision needs. The identity state lives in customer-service's
 * database behind ADR-0002's per-service roles, so the question has to be asked of the service that
 * owns the answer.
 *
 * <p>Implementations must fail closed: an infrastructure failure has to raise
 * {@link com.fintech.platform.card.error.CardErrorCodes#ELIGIBILITY_UNAVAILABLE} rather than return
 * {@code false} or, worse, {@code true}. Returning {@code false} would report a verified customer as
 * ineligible and send a cardholder to support for no reason; returning {@code true} would issue a
 * card to whoever asked, which is the compliance failure the check exists to prevent.
 */
@FunctionalInterface
public interface CardIssuanceEligibility {

    /**
     * @param customerId the customer the card would be issued to
     * @param caller the identity asking, forwarded so customer-service can apply its own
     *     authorisation rather than trusting a claim made by this service on the caller's behalf
     * @return whether the customer is currently approved and may hold a card
     * @throws com.fintech.platform.common.error.ApiException
     *     {@link com.fintech.platform.card.error.CardErrorCodes#ELIGIBILITY_UNAVAILABLE} if the
     *     question could not be answered at all
     */
    boolean isApproved(UUID customerId, InternalIdentity caller);
}
