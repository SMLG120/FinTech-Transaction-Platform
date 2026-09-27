package com.fintech.platform.fraud.web;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.fraud.error.FraudErrorCodes;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may read the fraud engine's output, and who may change it.
 *
 * <p>Same placement and same reasoning as {@code CardAuthorization} in card-service: the rule lives in the
 * service layer, not in the gateway and not in an annotation. The gateway cannot apply it, because whether
 * a caller may see a given decision depends on a row this service has to look up. And the annotations
 * elsewhere in this platform are inert — see the note in {@code docs/security.md} — so a rule expressed
 * only as {@code @PreAuthorize} is a rule that is not being enforced.
 *
 * <p><b>Two sets, and the asymmetry between them is the design.</b>
 *
 * <ul>
 *   <li><strong>Readers</strong> are {@code FRAUD_ANALYST}, {@code COMPLIANCE_OFFICER},
 *       {@code PLATFORM_ADMIN} and {@code AUDITOR}. A compliance officer and an auditor both need to see
 *       what the engine concluded, and both are entitled to it: the data is pseudonymous, and a party that
 *       cannot see the fraud assessment of a payment cannot supervise it.
 *   <li><strong>Actors</strong> are {@code FRAUD_ANALYST} and {@code PLATFORM_ADMIN} only. Claiming an
 *       alert, closing one, or overuling a score changes what the platform believes about a payment, and
 *       an auditor who could do that would stop being an auditor — the same reasoning that keeps
 *       {@code SUPPORT_AGENT} out of card cancellation in card-service.
 * </ul>
 *
 * <p><b>There is no customer access, by any route.</b> Not "a customer may see their own decision" — a
 * customer who can be told "this payment was declined for fraud" learns the threshold, and a customer who
 * can query the score learns it to the point. A customer learns that a payment was declined, from
 * transaction-service, which is where that fact belongs. This service has no path from a customer's token
 * to a score, and that is a stronger property than a rule that could be misconfigured.
 */
@Component
public class FraudAuthorization {

    /** May read decisions, alerts and the dashboard. */
    private static final Set<String> READERS =
            Set.of("FRAUD_ANALYST", "COMPLIANCE_OFFICER", "PLATFORM_ADMIN", "AUDITOR");

    /** May change an alert or a score. */
    private static final Set<String> ACTORS = Set.of("FRAUD_ANALYST", "PLATFORM_ADMIN");

    /** @throws com.fintech.platform.common.error.ApiException 403 if the caller may not read */
    public void requireRead(InternalIdentity caller) {
        if (!hasAnyRole(caller, READERS)) {
            throw FraudErrorCodes.FRAUD_FORBIDDEN.exception();
        }
    }

    /**
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller may not change an alert or
     *     a score
     */
    public void requireAction(InternalIdentity caller) {
        if (!hasAnyRole(caller, ACTORS)) {
            throw FraudErrorCodes.FRAUD_FORBIDDEN.exception();
        }
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller != null && caller.roles().stream().anyMatch(allowed::contains);
    }
}
