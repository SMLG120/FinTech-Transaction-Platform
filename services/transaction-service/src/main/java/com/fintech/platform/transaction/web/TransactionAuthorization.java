package com.fintech.platform.transaction.web;

import com.fintech.platform.common.error.CommonErrorCodes;
import com.fintech.platform.common.identity.InternalIdentity;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Service-side role checks for transaction and account operations. */
@Component
public class TransactionAuthorization {

    private static final Set<String> READERS = Set.of("CUSTOMER", "SUPPORT_AGENT", "PLATFORM_ADMIN");
    // Customers only. PLATFORM_ADMIN operates the platform and, per the realm's role definition, is
    // "deliberately distinct from any role that moves money": an operator account that can also pay
    // turns one stolen admin session into both control of the system and access to funds.
    private static final Set<String> MONEY_MOVERS = Set.of("CUSTOMER");

    public void requireRead(InternalIdentity caller) {
        requireAnyRole(caller, READERS);
    }

    public void requireMoneyMovement(InternalIdentity caller) {
        requireAnyRole(caller, MONEY_MOVERS);
    }

    private static void requireAnyRole(InternalIdentity caller, Set<String> allowed) {
        if (caller == null || caller.roles().stream().noneMatch(allowed::contains)) {
            throw CommonErrorCodes.FORBIDDEN.exception();
        }
    }
}
