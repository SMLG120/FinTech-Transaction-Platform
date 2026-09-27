package com.fintech.platform.dispute.web;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.dispute.error.DisputeErrors;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may open a case, plead in it, and decide it.
 *
 * <p>Same placement and reasoning as the other services' authorization beans: the rule lives in the
 * service layer rather than in the gateway or in an annotation, because the annotations elsewhere in
 * this platform are inert — see {@code docs/security.md} — so a rule written only as {@code
 * @PreAuthorize} is a rule that is not being enforced.
 *
 * <p><b>Three capabilities, and the split between them is the workflow.</b>
 *
 * <ul>
 *   <li><strong>Open</strong> is {@code CUSTOMER} and {@code PLATFORM_ADMIN}: the customer opens
 *       their own case — ownership is verified against transaction-service under their own identity
 *       — and an administrator may open where the customer cannot act for themselves. A support
 *       agent cannot open a case on a payment they cannot see; the customer opens, the agent
 *       decides.
 *   <li><strong>Plead</strong> (read, evidence) is the case's parties: the opener and the staff who
 *       decide. A customer reads only their own cases; staff read the queue.
 *   <li><strong>Decide</strong> is {@code SUPPORT_AGENT} and {@code PLATFORM_ADMIN} only. A
 *       customer who could resolve their own dispute holds a refund button, and a refund button
 *       does not need a case.
 * </ul>
 */
@Component
public class DisputeAuthorization {

    /** May open a case. */
    private static final Set<String> OPENERS = Set.of("CUSTOMER", "PLATFORM_ADMIN");

    /** May decide a case. */
    private static final Set<String> DECIDERS = Set.of("SUPPORT_AGENT", "PLATFORM_ADMIN");

    /** May read the queue and plead in any case. */
    private static final Set<String> STAFF = Set.of("SUPPORT_AGENT", "PLATFORM_ADMIN");

    /** @throws com.fintech.platform.common.error.ApiException 403 if the caller may not open */
    public void requireOpen(InternalIdentity caller) {
        if (!hasAnyRole(caller, OPENERS)) {
            throw DisputeErrors.FORBIDDEN.exception();
        }
    }

    /** @throws com.fintech.platform.common.error.ApiException 403 if the caller may not decide */
    public void requireDecide(InternalIdentity caller) {
        if (!hasAnyRole(caller, DECIDERS)) {
            throw DisputeErrors.FORBIDDEN.exception();
        }
    }

    /**
     * Whether the caller works the queue rather than owning cases: staff see every case, a customer
     * only their own.
     */
    public boolean isStaff(InternalIdentity caller) {
        return hasAnyRole(caller, STAFF);
    }

    /**
     * Whether the caller may see this case at all: staff may see every case, a customer only their
     * own.
     *
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller is neither staff nor
     *     the opener
     */
    public void requireParty(InternalIdentity caller, DisputeEntity dispute) {
        if (hasAnyRole(caller, STAFF)) {
            return;
        }
        if (caller != null && caller.subject().equals(dispute.getOpenedBySubject())) {
            return;
        }
        throw DisputeErrors.FORBIDDEN.exception();
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller != null && caller.roles().stream().anyMatch(allowed::contains);
    }
}
