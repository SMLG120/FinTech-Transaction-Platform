package com.fintech.platform.audit.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.audit.error.AuditErrors;
import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The supervisory role set, on its own.
 *
 * <p>{@code AuditSecurityTest} proves every endpoint consults this before touching data. This proves
 * the set is what it is meant to be, which the endpoint test cannot: a test that only checked "a
 * role-less caller is refused" would pass just as happily if the set admitted everybody.
 */
class AuditAuthorizationTest {

    private final AuditAuthorization authorization = new AuditAuthorization();

    private static InternalIdentity caller(String... roles) {
        return new InternalIdentity(
                "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f",
                "auditor-1",
                List.of(roles),
                "correlation-1",
                Instant.parse("2024-06-15T12:00:00Z"));
    }

    @ParameterizedTest(name = "{0} may read")
    @ValueSource(strings = {"AUDITOR", "COMPLIANCE_OFFICER", "PLATFORM_ADMIN"})
    @DisplayName("is allowed for the roles whose job is supervision")
    void supervisoryRolesMayRead(String role) {
        // An auditor who cannot open the trail cannot audit anything, and a compliance officer who
        // cannot read it cannot supervise. Read access is exactly these three, on purpose.
        assertThatCode(() -> authorization.requireRead(caller(role))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} may not read")
    @ValueSource(strings = {"CUSTOMER", "SUPPORT_AGENT", "FRAUD_ANALYST", "SETTLEMENT_OPERATOR"})
    @DisplayName("is refused for customers, support, and the supervised operators")
    void otherRolesMayNotRead(String role) {
        // The sharpest refusal is the analyst and the operator: the trail records their actions,
        // and a trail the supervised can read is a control while a trail they can shape would be a
        // press release. Reading is not shaping, but the boundary is drawn one step earlier so it
        // never has to be argued per endpoint.
        assertThatThrownBy(() -> authorization.requireRead(caller(role)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AuditErrors.FORBIDDEN);
    }

    @Test
    @DisplayName("is refused for a caller with no roles, rather than defaulting to allowed")
    void rolelessCallerMayNotRead() {
        assertThatThrownBy(() -> authorization.requireRead(caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AuditErrors.FORBIDDEN);
    }

    @Test
    @DisplayName("is refused for a missing identity, because null is not a caller")
    void missingIdentityMayNotRead() {
        assertThatThrownBy(() -> authorization.requireRead(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AuditErrors.FORBIDDEN);
    }
}
