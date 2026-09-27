package com.fintech.platform.fraud.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.fraud.error.FraudErrorCodes;
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
 * <p>{@code FraudSecurityTest} proves that every endpoint calls one of these before touching data. This
 * proves the sets are what they are meant to be, which the endpoint test cannot: an endpoint test that
 * only checks "a role-less caller is refused" would pass just as happily if {@code requireRead} and
 * {@code requireAction} were the same method with the same set.
 */
class FraudAuthorizationTest {

    private final FraudAuthorization authorization = new FraudAuthorization();

    private static InternalIdentity caller(String... roles) {
        return new InternalIdentity(
                "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f",
                "analyst-1",
                List.of(roles),
                "correlation-1",
                Instant.parse("2024-06-15T12:00:00Z"));
    }

    @Nested
    @DisplayName("reading decisions, alerts and the dashboard")
    class Reading {

        @ParameterizedTest(name = "{0} may read")
        @ValueSource(strings = {"FRAUD_ANALYST", "COMPLIANCE_OFFICER", "PLATFORM_ADMIN", "AUDITOR"})
        @DisplayName("is allowed for every supervisory role, because all of them must be able to see it")
        void supervisoryRolesMayRead(String role) {
            assertThatCode(() -> authorization.requireRead(caller(role))).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may not read")
        @ValueSource(strings = {"CUSTOMER", "SUPPORT_AGENT", "CARD_AGENT", "TRANSACTION_AGENT"})
        @DisplayName("is refused for operational and customer roles")
        void otherRolesMayNotRead(String role) {
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
    @DisplayName("claiming, closing, re-scoring and adjusting")
    class Acting {

        @ParameterizedTest(name = "{0} may act")
        @ValueSource(strings = {"FRAUD_ANALYST", "PLATFORM_ADMIN"})
        @DisplayName("is allowed only for the roles that work the queue")
        void analystsMayAct(String role) {
            assertThatCode(() -> authorization.requireAction(caller(role))).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may read but not act")
        @ValueSource(strings = {"AUDITOR", "COMPLIANCE_OFFICER"})
        @DisplayName("is refused for the supervisory roles, who must be able to see without being able to change")
        void supervisorsMayNotAct(String role) {
            // The separation of duties, and the reason it is worth a test: an auditor who could close an
            // alert or overrule a score would be editing the evidence they are there to examine. This is
            // the same reasoning that keeps SUPPORT_AGENT out of card cancellation in card-service.
            assertThatCode(() -> authorization.requireRead(caller(role))).doesNotThrowAnyException();
            assertRefused(() -> authorization.requireAction(caller(role)));
        }

        @Test
        @DisplayName("is refused for a customer, on every route")
        void customerMayNotAct() {
            // A customer who can read a score learns the threshold; a customer who can set one learns it
            // completely. There is no path from a customer token to either, by design.
            assertRefused(() -> authorization.requireRead(caller("CUSTOMER")));
            assertRefused(() -> authorization.requireAction(caller("CUSTOMER")));
        }

        @Test
        @DisplayName("is refused for a missing identity")
        void missingIdentityMayNotAct() {
            assertRefused(() -> authorization.requireAction(null));
        }
    }

    private static void assertRefused(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(FraudErrorCodes.FRAUD_FORBIDDEN.code());
    }
}
