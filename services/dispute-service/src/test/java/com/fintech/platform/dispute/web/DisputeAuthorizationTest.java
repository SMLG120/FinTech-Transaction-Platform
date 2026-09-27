package com.fintech.platform.dispute.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.dispute.domain.DisputeReason;
import com.fintech.platform.dispute.error.DisputeErrors;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The three capabilities, on their own.
 *
 * <p>{@code DisputeSecurityTest} proves every endpoint consults one of these before touching data.
 * This proves the sets are what they are meant to be, which the endpoint test cannot: a test that
 * only checked "a role-less caller is refused" would pass just as happily if open, plead and
 * decide admitted the same members.
 */
class DisputeAuthorizationTest {

    private final DisputeAuthorization authorization = new DisputeAuthorization();

    private static InternalIdentity caller(String subject, String... roles) {
        return new InternalIdentity(
                subject, "user-1", List.of(roles), "correlation-1", Instant.parse("2024-06-15T12:00:00Z"));
    }

    private static DisputeEntity caseOf(String opener) {
        return DisputeEntity.open(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                DisputeReason.FRAUD,
                "I did not pay this",
                opener,
                Instant.parse("2024-06-15T12:00:00Z"));
    }

    @Nested
    @DisplayName("opening a case")
    class Opening {

        @ParameterizedTest(name = "{0} may open")
        @ValueSource(strings = {"CUSTOMER", "PLATFORM_ADMIN"})
        @DisplayName("is allowed for the customer and the administrator")
        void openersMayOpen(String role) {
            assertThatCode(() -> authorization.requireOpen(caller("subject-1", role)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may not open")
        @ValueSource(strings = {"SUPPORT_AGENT", "AUDITOR", "COMPLIANCE_OFFICER", "FRAUD_ANALYST"})
        @DisplayName("is refused for agents, supervisors and other operators")
        void othersMayNotOpen(String role) {
            // A support agent cannot open a case on a payment they cannot see; the customer opens
            // and the agent decides. An agent-opened case would break the ownership every other
            // service keeps.
            assertThatThrownBy(() -> authorization.requireOpen(caller("subject-1", role)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(DisputeErrors.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("deciding a case")
    class Deciding {

        @ParameterizedTest(name = "{0} may decide")
        @ValueSource(strings = {"SUPPORT_AGENT", "PLATFORM_ADMIN"})
        @DisplayName("is allowed for the roles that work the queue")
        void decidersMayDecide(String role) {
            assertThatCode(() -> authorization.requireDecide(caller("subject-1", role)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may not decide")
        @ValueSource(strings = {"CUSTOMER", "AUDITOR", "COMPLIANCE_OFFICER"})
        @DisplayName("is refused for the customer and the supervisors")
        void othersMayNotDecide(String role) {
            // A customer who could resolve their own dispute holds a refund button, and a refund
            // button does not need a case.
            assertThatThrownBy(() -> authorization.requireDecide(caller("subject-1", role)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(DisputeErrors.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("seeing a case")
    class Party {

        @Test
        @DisplayName("staff see every case")
        void staffSeeEveryCase() {
            DisputeEntity dispute = caseOf("opener-subject");

            assertThatCode(() -> authorization.requireParty(caller("agent-subject", "SUPPORT_AGENT"), dispute))
                    .doesNotThrowAnyException();
            assertThatCode(() -> authorization.requireParty(caller("admin-subject", "PLATFORM_ADMIN"), dispute))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a customer sees their own case and no other")
        void customerSeesOnlyTheirOwn() {
            DisputeEntity dispute = caseOf("opener-subject");

            assertThatCode(() -> authorization.requireParty(caller("opener-subject", "CUSTOMER"), dispute))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> authorization.requireParty(caller("other-subject", "CUSTOMER"), dispute))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(DisputeErrors.FORBIDDEN);
        }

        @Test
        @DisplayName("a missing identity sees nothing, because null is not a party")
        void missingIdentitySeesNothing() {
            assertThat(authorization.isStaff(null)).isFalse();
            assertThatThrownBy(() -> authorization.requireParty(null, caseOf("opener-subject")))
                    .isInstanceOf(ApiException.class);
        }
    }
}
