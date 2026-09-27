package com.fintech.platform.audit.web;

import com.fintech.platform.audit.error.AuditErrors;
import com.fintech.platform.common.identity.InternalIdentity;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may read the trail.
 *
 * <p>Same placement and reasoning as the other services' authorization beans: the rule lives in the
 * service layer rather than in the gateway or in an annotation, because the annotations elsewhere in
 * this platform are inert — see {@code docs/security.md} — so a rule written only as {@code
 * @PreAuthorize} is a rule that is not being enforced.
 *
 * <p><b>One set, and the absence of a second is the design.</b> The trail is append-only, so there
 * is no action to authorize: no close, no acknowledge, no retry. Readers are {@code AUDITOR},
 * {@code COMPLIANCE_OFFICER} and {@code PLATFORM_ADMIN} — the roles whose job is supervision. There
 * is deliberately no customer, support, analyst or operator access: a trail that the supervised can
 * read is a control, and a trail the supervised can shape is a press release. The same set stands in
 * the gateway's {@code /api/audit/**} rule, and the two lists saying the same thing is the property
 * {@code AuditSecurityTest} and {@code GatewayAuthorisationTest} pin from opposite sides.
 */
@Component
public class AuditAuthorization {

    /** May read the trail. Nobody may write to it through this API. */
    private static final Set<String> READERS = Set.of("AUDITOR", "COMPLIANCE_OFFICER", "PLATFORM_ADMIN");

    /** @throws com.fintech.platform.common.error.ApiException 403 if the caller may not read */
    public void requireRead(InternalIdentity caller) {
        if (!hasAnyRole(caller, READERS)) {
            throw AuditErrors.FORBIDDEN.exception();
        }
    }

    private static boolean hasAnyRole(InternalIdentity caller, Set<String> allowed) {
        return caller != null && caller.roles().stream().anyMatch(allowed::contains);
    }
}
