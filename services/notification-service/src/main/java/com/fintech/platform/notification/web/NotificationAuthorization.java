package com.fintech.platform.notification.web;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.notification.error.NotificationErrors;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may read the delivery log, and who may retry a failed send.
 *
 * <p>Same placement and reasoning as {@code FraudAuthorization} and {@code SettlementAuthorization}:
 * the rule lives in the service layer rather than in the gateway or in an annotation, because the
 * annotations elsewhere in this platform are inert — see {@code docs/security.md} — so a rule written
 * only as {@code @PreAuthorize} is a rule that is not being enforced.
 *
 * <p><b>One set, and the absence of a second is the design.</b> Reading a notification and retrying
 * one are the same job: a support agent answering "did the customer get told, and if not, tell them
 * again". Splitting them would invent a role that may see a failed delivery but may not fix it, which
 * is a queue that fills and nobody empties. The refusal that matters here is not between two staff
 * roles but between staff and everyone else, below.
 *
 * <p><b>There is no customer access, by any route.</b> Not "a customer may see their own
 * notifications" — this service correlates on an owner digest and holds no mapping from a token to
 * that digest, so "their own" is a lookup it cannot perform. A rule promising per-customer scoping
 * would be enforced by nothing, and a customer who can list messages by transaction id learns the
 * platform's fraud and settlement wording. The customer learns what happened to their payment from
 * transaction-service, which is where that fact belongs.
 */
@Component
public class NotificationAuthorization {

    /** May read the delivery log and retry a failed send. */
    private static final Set<String> SUPPORT = Set.of("SUPPORT_AGENT", "PLATFORM_ADMIN");

    /** @throws com.fintech.platform.common.error.ApiException 403 if the caller may not read */
    public void requireRead(InternalIdentity caller) {
        if (!hasAnyRole(caller, SUPPORT)) {
            throw NotificationErrors.FORBIDDEN.exception();
        }
    }

    /**
     * @throws com.fintech.platform.common.error.ApiException 403 if the caller may not retry a
     *     failed send
     */
    public void requireRetry(InternalIdentity caller) {
        if (!hasAnyRole(caller, SUPPORT)) {
            throw NotificationErrors.FORBIDDEN.exception();
        }
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller != null && caller.roles().stream().anyMatch(allowed::contains);
    }
}
