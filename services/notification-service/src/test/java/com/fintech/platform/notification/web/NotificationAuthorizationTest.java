package com.fintech.platform.notification.web;

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
 * The support role set, on its own.
 *
 * <p>{@code NotificationSecurityTest} proves every endpoint consults one of these before touching
 * data. This proves the set is what it is meant to be, which the endpoint test cannot: a test that
 * only checked "a role-less caller is refused" would pass just as happily if {@code requireRead}
 * and {@code requireRetry} admitted different members.
 */
class NotificationAuthorizationTest {

    private final NotificationAuthorization authorization = new NotificationAuthorization();

    private static InternalIdentity caller(String... roles) {
        return new InternalIdentity(
                "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f",
                "support-1",
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
                .isEqualTo("NOTIFICATION_FORBIDDEN");
    }

    @Nested
    @DisplayName("reading the delivery log")
    class Reading {

        @ParameterizedTest(name = "{0} may read")
        @ValueSource(strings = {"SUPPORT_AGENT", "PLATFORM_ADMIN"})
        @DisplayName("is allowed for the roles that answer 'was the customer told'")
        void supportRolesMayRead(String role) {
            assertThatCode(() -> authorization.requireRead(caller(role))).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may not read")
        @ValueSource(strings = {"CUSTOMER", "AUDITOR", "COMPLIANCE_OFFICER", "FRAUD_ANALYST", "SETTLEMENT_OPERATOR"})
        @DisplayName("is refused for customers, supervisors and other operators")
        void otherRolesMayNotRead(String role) {
            // A customer reading the log learns the platform's fraud and settlement wording; an
            // auditor or analyst reading it gains a join key into another service's pseudonyms.
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
    @DisplayName("retrying a failed send")
    class Retrying {

        @ParameterizedTest(name = "{0} may retry")
        @ValueSource(strings = {"SUPPORT_AGENT", "PLATFORM_ADMIN"})
        @DisplayName("is allowed for the same roles that read, because a queue nobody may empty fills")
        void supportRolesMayRetry(String role) {
            assertThatCode(() -> authorization.requireRetry(caller(role))).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may not retry")
        @ValueSource(strings = {"CUSTOMER", "AUDITOR", "COMPLIANCE_OFFICER", "FRAUD_ANALYST", "SETTLEMENT_OPERATOR"})
        @DisplayName("is refused for everyone else, including the roles that supervise other queues")
        void otherRolesMayNotRetry(String role) {
            assertRefused(() -> authorization.requireRetry(caller(role)));
        }

        @Test
        @DisplayName("is refused for a customer, on both halves")
        void customerMayNotRetry() {
            assertRefused(() -> authorization.requireRetry(caller("CUSTOMER")));
        }
    }
}
