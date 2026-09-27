package com.fintech.platform.customer.service;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may do what to a customer profile.
 *
 * <p>Authorisation lives here, in the service layer, rather than in the gateway or in controller
 * method bodies. The gateway decides whether a caller may send a request to this service at all; it
 * cannot know whose profile the request names, because the customer id is in the body. Putting the
 * rule in a gateway route means every new endpoint has to re-derive it, and the one that forgets is
 * the breach.
 *
 * <p>Deliberately three predicates rather than one. Read access is broad, because auditors and
 * support staff genuinely need to see a profile, while actions that cannot be undone are narrow. An
 * endpoint that only needs "may read" and reaches for the strictest predicate is merely inconvenient;
 * the reverse mistake, reusing the broad predicate on a destructive operation, hands an irreversible
 * deletion of somebody's personal data to a support agent because both calls used the same name.
 */
@Component
public class CustomerAuthorization {

    /** May read any profile. */
    private static final Set<String> READERS =
            Set.of("PLATFORM_ADMIN", "SUPPORT_AGENT", "COMPLIANCE_OFFICER", "AUDITOR");

    /** May act on anyone's behalf, irreversibly. */
    private static final Set<String> ADMINS = Set.of("PLATFORM_ADMIN");

    /** May conclude an identity check on someone's behalf. */
    private static final Set<String> COMPLIANCE = Set.of("PLATFORM_ADMIN", "COMPLIANCE_OFFICER");

    /**
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller is neither the owner
     *     nor a role that may read any profile
     */
    public void requireReadAccess(InternalIdentity caller, String subject) {
        if (isOwner(caller, subject) || hasAnyRole(caller, READERS)) {
            return;
        }
        throw CustomerErrorCodes.NOT_THE_OWNER.exception();
    }

    /**
     * For operations that cannot be undone, chiefly erasure.
     *
     * <p>Deliberately excludes {@code SUPPORT_AGENT}. A support agent can see a profile to help with a
     * question; the ability to delete a customer's personal data is a different grant, and a support
     * desk is a large, high-turnover, high-phishing-target population.
     */
    public void requireSelfOrAdmin(InternalIdentity caller, String subject) {
        if (isOwner(caller, subject) || hasAnyRole(caller, ADMINS)) {
            return;
        }
        throw CustomerErrorCodes.NOT_THE_OWNER.exception();
    }

    /** @throws ApiException 403 if the caller may not conclude identity checks */
    public void requireComplianceRole(InternalIdentity caller) {
        if (!hasAnyRole(caller, COMPLIANCE)) {
            throw CustomerErrorCodes.NOT_THE_OWNER.exception();
        }
    }

    /**
     * @return whether the caller owns the profile; false for a tombstone, which has no owner
     */
    public boolean isOwner(InternalIdentity caller, String subject) {
        // A null subject means the profile is a tombstone, and a tombstone has no owner to match. It
        // cannot be read as self-service by anyone, which is the safe direction for that edge.
        return subject != null && subject.equals(caller.subject());
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller.roles().stream().anyMatch(allowed::contains);
    }
}
