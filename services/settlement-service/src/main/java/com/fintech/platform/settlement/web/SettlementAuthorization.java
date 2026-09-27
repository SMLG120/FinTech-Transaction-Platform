package com.fintech.platform.settlement.web;

import com.fintech.platform.common.error.ErrorCode;
import com.fintech.platform.common.identity.InternalIdentity;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may read a settlement statement, and who may change one.
 *
 * <p>Same placement and reasoning as {@code FraudAuthorization}: the rule lives in the service layer
 * rather than in the gateway or in an annotation, because the gateway cannot know which cycle a caller
 * should see, and the annotations elsewhere in this platform are inert — see {@code docs/security.md} — so
 * a rule written only as {@code @PreAuthorize} is a rule that is not being enforced.
 *
 * <p><b>Two sets, and the asymmetry is the design.</b>
 *
 * <ul>
 *   <li><strong>Readers</strong> are {@code SETTLEMENT_OPERATOR}, {@code COMPLIANCE_OFFICER},
 *       {@code AUDITOR} and {@code PLATFORM_ADMIN}. Reading a statement is how an auditor establishes
 *       that money reached a merchant, and a party that cannot see a period's totals cannot supervise it.
 *   <li><strong>Actors</strong> are {@code SETTLEMENT_OPERATOR} and {@code PLATFORM_ADMIN}. Closing a
 *       cycle, declaring an actual and acknowledging a break change what the platform believes about money
 *       that has already moved, and an auditor who could do that would stop being an auditor — the same
 *       reasoning that keeps {@code SUPPORT_AGENT} out of card cancellation.
 * </ul>
 *
 * <p><b>There is no customer or support access, by any route.</b> Not "a merchant may see their own
 * statement" — this platform has no merchant identity, and a rule that would grow one the first time a
 * merchant portal was wanted is a rule that would be written without the same care. There is no path from
 * a customer's token to a cycle, which is a stronger property than a rule that could be misconfigured.
 */
@Component
public class SettlementAuthorization {

    /** May read cycles, lines and breaks. */
    private static final Set<String> READERS =
            Set.of("SETTLEMENT_OPERATOR", "COMPLIANCE_OFFICER", "PLATFORM_ADMIN", "AUDITOR");

    /** May close a cycle, declare an actual, or work a break. */
    private static final Set<String> ACTORS = Set.of("SETTLEMENT_OPERATOR", "PLATFORM_ADMIN");

    /** The 403 code, distinct from the 404 so a permissions mistake is not mistaken for a missing cycle. */
    private static final ErrorCode FORBIDDEN = ErrorCode.of(
            "SETTLEMENT_FORBIDDEN",
            org.springframework.http.HttpStatus.FORBIDDEN,
            "This endpoint is for settlement operators and compliance staff");

    /**
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller may not read
     */
    public void requireRead(InternalIdentity caller) {
        if (!hasAnyRole(caller, READERS)) {
            throw FORBIDDEN.exception();
        }
    }

    /**
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller may not change a cycle or
     *     work a break
     */
    public void requireAction(InternalIdentity caller) {
        if (!hasAnyRole(caller, ACTORS)) {
            throw FORBIDDEN.exception();
        }
    }

    /**
     * The actor to record against a break.
     *
     * <p>The subject rather than the username. The subject is the immutable, opaque identifier the gateway
     * verified, and {@code username} is documented on {@link InternalIdentity} as a human-readable label
     * for logs that must never be an authorisation input. An acknowledgement is an audit record about a
     * specific person, and an audit record wants the identifier that cannot be reassigned to somebody else
     * by a rename.
     *
     * @param caller the verified identity
     * @return the actor to store
     */
    public String actorOf(InternalIdentity caller) {
        return caller.subject();
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller != null && caller.roles().stream().anyMatch(allowed::contains);
    }
}
