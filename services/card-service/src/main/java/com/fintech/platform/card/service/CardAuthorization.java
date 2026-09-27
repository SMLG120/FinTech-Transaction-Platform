package com.fintech.platform.card.service;

import com.fintech.platform.common.identity.InternalIdentity;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may do what to a card.
 *
 * <p>Same placement and same reasoning as {@code CustomerAuthorization} in customer-service: the rule
 * lives in the service layer, not in the gateway and not in a controller. The gateway cannot apply
 * these, because the card id is in the path and the owner is a row this service has to look up.
 *
 * <p>The predicates are narrower than customer-service's, and deliberately so. A profile is a record
 * about a person; a card is an instrument. The asymmetry is the point of this class:
 *
 * <ul>
 *   <li><strong>Issuing has no staff override.</strong> Only the cardholder may issue their own card.
 *       Support could be given the ability tomorrow, and today it deliberately is not, because a card
 *       is something a person carries and uses, and an agent creating one in a customer's name is
 *       harder to justify than an agent reading a profile.
 *   <li><strong>Freezing and reporting a loss are available to support</strong>, because those are the
 *       two operations a customer rings a support desk to request during a fraud call, and refusing
 *       them would push staff towards handling the card outside the system.
 *   <li><strong>Unfreezing is not.</strong> It is the one lifecycle operation that puts a card back into
 *       circulation, and letting support perform it means a compromised support account can revive a
 *       card. A cardholder reactivating their own frozen card is a different risk, and it is allowed.
 *   <li><strong>Cancelling is available to support and admin</strong> but not to the broad reader set:
 *       a card cannot be un-cancelled, so it belongs with the destructive operations.
 * </ul>
 */
@Component
public class CardAuthorization {

    /** May read any card, as customer-service's reader set. */
    private static final Set<String> READERS =
            Set.of("PLATFORM_ADMIN", "SUPPORT_AGENT", "COMPLIANCE_OFFICER", "AUDITOR");

    /**
     * May suspend a card, or report one lost, for any cardholder.
     *
     * <p>Both only ever move a card away from use, so the blast radius of a misapplied grant is a
     * blocked card rather than a stolen one. That asymmetry is what makes it reasonable to give to a
     * role as broad as support.
     */
    private static final Set<String> RESTRICTORS = Set.of("PLATFORM_ADMIN", "SUPPORT_AGENT");

    /** May close a card for good, for any cardholder. */
    private static final Set<String> ADMINS = Set.of("PLATFORM_ADMIN");

    /**
     * @param ownerSubjectDigest the digest stored on the card, or {@code null} when the card does not
     *     exist or the caller may not see it; both are refused identically
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller is neither the
     *     cardholder nor a role that may read any card
     */
    public void requireReadAccess(InternalIdentity caller, String ownerSubjectDigest, String digestForCaller) {
        if (isOwner(ownerSubjectDigest, digestForCaller) || hasAnyRole(caller, READERS)) {
            return;
        }
        throw com.fintech.platform.card.error.CardErrorCodes.NOT_THE_CARDHOLDER.exception();
    }

    /**
     * For operations that only ever stop a card working.
     *
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller is neither the
     *     cardholder nor a role permitted to restrict cards
     */
    public void requireSelfOrRestrictor(InternalIdentity caller, String ownerSubjectDigest, String digestForCaller) {
        if (isOwner(ownerSubjectDigest, digestForCaller) || hasAnyRole(caller, RESTRICTORS)) {
            return;
        }
        throw com.fintech.platform.card.error.CardErrorCodes.NOT_THE_CARDHOLDER.exception();
    }

    /**
     * For closing a card, which cannot be undone.
     *
     * <p>Excludes {@code SUPPORT_AGENT} for the reason customer-service excludes it from erasure: a
     * support desk is a large, high-turnover population and cancellation is irreversible.
     */
    public void requireSelfOrAdmin(InternalIdentity caller, String ownerSubjectDigest, String digestForCaller) {
        if (isOwner(ownerSubjectDigest, digestForCaller) || hasAnyRole(caller, ADMINS)) {
            return;
        }
        throw com.fintech.platform.card.error.CardErrorCodes.NOT_THE_CARDHOLDER.exception();
    }

    /**
     * For reactivation, which is the one operation that returns a card to circulation.
     *
     * <p>Owner only, with no role override at all. See the class comment.
     */
    public void requireSelf(InternalIdentity caller, String ownerSubjectDigest, String digestForCaller) {
        if (isOwner(ownerSubjectDigest, digestForCaller)) {
            return;
        }
        throw com.fintech.platform.card.error.CardErrorCodes.NOT_THE_CARDHOLDER.exception();
    }

    /**
     * @return whether the caller owns the card
     *
     * <p>Compares digests rather than subjects, so a constant-time comparison is not needed and a
     * timing oracle could not distinguish "no such card" from "not yours" anyway: both lookups are
     * keyed by the caller's own digest and an absent card is a null digest, which never equals one.
     */
    public boolean isOwner(String ownerSubjectDigest, String digestForCaller) {
        return ownerSubjectDigest != null && ownerSubjectDigest.equals(digestForCaller);
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller.roles().stream().anyMatch(allowed::contains);
    }
}
