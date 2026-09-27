package com.fintech.platform.settlement.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two role sets, on their own.
 *
 * <p>{@code SettlementSecurityTest} proves every endpoint consults one of these before touching data. This
 * proves the sets are what they are meant to be, which the endpoint test cannot: a test that only checked
 * "a role-less caller is refused" would pass just as happily if {@code requireRead} and {@code requireAction}
 * were the same method with the same members.
 */
class SettlementAuthorizationTest {

    private final SettlementAuthorization authorization = new SettlementAuthorization();

    private static InternalIdentity caller(String... roles) {
        return new InternalIdentity(
                "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f",
                "operator-1",
                List.of(roles),
                "correlation-1",
                Instant.parse("2024-06-15T12:00:00Z"));
    }

    private static void assertRefused(Runnable call) {
        // Asserting the code, not just the refusal. A 403 for the wrong reason — an unauthenticated
        // caller reaching a missing route, say — would pass a status-only assertion while telling the
        // caller nothing about whether their role was the problem.
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo("SETTLEMENT_FORBIDDEN");
    }

    @Nested
    @DisplayName("reading cycles and findings")
    class Reading {

        @ParameterizedTest(name = "{0} may read")
        @ValueSource(strings = {"SETTLEMENT_OPERATOR", "COMPLIANCE_OFFICER", "AUDITOR", "PLATFORM_ADMIN"})
        @DisplayName("is allowed for every role that has business being in a settlement period")
        void supervisoryRolesMayRead(String role) {
            // An auditor who cannot open the statement cannot audit it, and a compliance officer who cannot
            // read a period's figures cannot reconcile anything. Read access is wide on purpose.
            assertThatCode(() -> authorization.requireRead(caller(role))).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may not read")
        @ValueSource(strings = {"CUSTOMER", "SUPPORT_AGENT", "CARD_AGENT", "TRANSACTION_AGENT", "FRAUD_ANALYST"})
        @DisplayName("is refused for customer and operational roles")
        void otherRolesMayNotRead(String role) {
            // A customer reading a settlement statement learns the platform's fee and timing figures, which
            // belong to the merchant relationship rather than to them.
            assertRefused(() -> authorization.requireRead(caller(role)));
        }

        @Test
        @DisplayName("is refused for a caller with no roles, rather than defaulting to allowed")
        void rolelessCallerMayNotRead() {
            assertRefused(() -> authorization.requireRead(caller()));
        }

        @Test
        @DisplayName("is refused for a missing identity, because null is not a caller")
        void missingIdentityMayNotRead() {
            assertRefused(() -> authorization.requireRead(null));
        }
    }

    @Nested
    @DisplayName("closing, declaring, reconciling and working findings")
    class Acting {

        @ParameterizedTest(name = "{0} may act")
        @ValueSource(strings = {"SETTLEMENT_OPERATOR", "PLATFORM_ADMIN"})
        @DisplayName("is allowed only for the roles that work the queue")
        void operatorsMayAct(String role) {
            assertThatCode(() -> authorization.requireAction(caller(role))).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may read but not act")
        @ValueSource(strings = {"AUDITOR", "COMPLIANCE_OFFICER"})
        @DisplayName("is refused for the supervisory roles, who must see without being able to change")
        void supervisorsMayNotAct(String role) {
            // The separation of duties, and the reason it earns a test. An auditor who could declare an
            // actual is supplying the very figure they are there to check against the statement, and an
            // auditor who could resolve a break is editing the evidence of a discrepancy. Both are the
            // same mistake in a different place, and both are the reason this is a separate set rather
            // than a convenience.
            assertThatCode(() -> authorization.requireRead(caller(role))).doesNotThrowAnyException();
            assertRefused(() -> authorization.requireAction(caller(role)));
        }

        @Test
        @DisplayName("is refused for a customer, on both halves")
        void customerMayNotAct() {
            // Declaring an actual would let a customer state what they were paid, and the platform would
            // then reconcile their period against it. There is no path from a customer token to a
            // settlement action, and this is the assertion that keeps it that way.
            assertRefused(() -> authorization.requireAction(caller("CUSTOMER")));
        }
    }
}
