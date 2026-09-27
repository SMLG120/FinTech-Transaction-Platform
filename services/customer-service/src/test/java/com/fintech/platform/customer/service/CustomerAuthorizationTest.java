package com.fintech.platform.customer.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerAuthorizationTest {

    private static final String OWNER_SUBJECT = "subject-of-the-customer";
    private static final String OTHER_SUBJECT = "subject-of-somebody-else";

    private final CustomerAuthorization authorization = new CustomerAuthorization();

    private static InternalIdentity caller(String subject, String... roles) {
        return new InternalIdentity(
                subject, "username", List.of(roles), "correlation-1", Instant.parse("2026-06-15T12:00:00Z"));
    }

    private static InternalIdentity owner() {
        return caller(OWNER_SUBJECT, "CUSTOMER");
    }

    @Nested
    @DisplayName("reading a profile")
    class Reading {

        @Test
        @DisplayName("is allowed for the owner")
        void ownerMayRead() {
            assertThatCode(() -> authorization.requireReadAccess(owner(), OWNER_SUBJECT))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} may read any profile")
        @ValueSource(strings = {"PLATFORM_ADMIN", "SUPPORT_AGENT", "COMPLIANCE_OFFICER", "AUDITOR"})
        @DisplayName("is allowed for staff roles, because auditing and support need it")
        void staffMayReadAnyProfile(String role) {
            assertThatCode(() -> authorization.requireReadAccess(caller(OTHER_SUBJECT, role), OWNER_SUBJECT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is refused for another customer")
        void anotherCustomerMayNotRead() {
            assertThatThrownBy(() -> authorization.requireReadAccess(caller(OTHER_SUBJECT, "CUSTOMER"), OWNER_SUBJECT))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.NOT_THE_OWNER.code());
        }

        @Test
        @DisplayName("is refused for a customer with no roles at all, rather than defaulting to allowed")
        void rolelessCallerMayNotRead() {
            assertThatThrownBy(() -> authorization.requireReadAccess(caller(OTHER_SUBJECT), OWNER_SUBJECT))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("erasing a profile")
    class Erasing {

        @Test
        @DisplayName("is allowed for the owner")
        void ownerMayErase() {
            assertThatCode(() -> authorization.requireSelfOrAdmin(owner(), OWNER_SUBJECT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is allowed for a platform administrator")
        void adminMayEraseAnyProfile() {
            assertThatCode(() ->
                            authorization.requireSelfOrAdmin(caller(OTHER_SUBJECT, "PLATFORM_ADMIN"), OWNER_SUBJECT))
                    .doesNotThrowAnyException();
        }

        /**
         * The test that carries the reason this class has three predicates rather than one.
         *
         * <p>Support can read a profile to answer a question. Deleting a customer's personal data is a
         * different grant, and if both used the same predicate, giving support read access would silently
         * have given it irreversible deletion too.
         */
        @Test
        @DisplayName("is refused for support and audit roles, which may read but not destroy")
        void readOnlyRolesMayNotErase() {
            for (String role : List.of("SUPPORT_AGENT", "AUDITOR", "COMPLIANCE_OFFICER")) {
                assertThatThrownBy(() -> authorization.requireSelfOrAdmin(caller(OTHER_SUBJECT, role), OWNER_SUBJECT))
                        .as("%s must not be able to erase another customer's data", role)
                        .isInstanceOf(ApiException.class);
            }
        }

        @Test
        @DisplayName("is refused for another customer")
        void anotherCustomerMayNotErase() {
            assertThatThrownBy(() -> authorization.requireSelfOrAdmin(caller(OTHER_SUBJECT, "CUSTOMER"), OWNER_SUBJECT))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("concluding an identity check")
    class DecidingChecks {

        @Test
        @DisplayName("is allowed for compliance and platform administrators")
        void complianceMayDecide() {
            for (String role : List.of("COMPLIANCE_OFFICER", "PLATFORM_ADMIN")) {
                assertThatCode(() -> authorization.requireComplianceRole(caller(OWNER_SUBJECT, role)))
                        .as("%s must be able to conclude a check", role)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("is refused for a customer, including on their own check")
        void customerMayNotDecide() {
            assertThatThrownBy(() -> authorization.requireComplianceRole(owner()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.NOT_THE_OWNER.code());
        }

        @Test
        @DisplayName("is refused for support, who may read a decision but not make one")
        void supportMayNotDecide() {
            assertThatThrownBy(() -> authorization.requireComplianceRole(caller(OWNER_SUBJECT, "SUPPORT_AGENT")))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("erased profiles")
    class Tombstones {

        /**
         * An erasure nulls the subject, so a tombstone has no owner for {@code isOwner} to match. A
         * tombstone must not become readable by self-service, which is the only direction that is safe.
         */
        @Test
        @DisplayName("are not readable by self-service, because they have no owner to match")
        void tombstoneIsNotSelfServiceReadable() {
            assertThatThrownBy(() -> authorization.requireReadAccess(owner(), null))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("are still readable by staff, who need to confirm an erasure happened")
        void tombstoneIsStillVisibleToStaff() {
            assertThatCode(() -> authorization.requireReadAccess(caller(OTHER_SUBJECT, "COMPLIANCE_OFFICER"), null))
                    .doesNotThrowAnyException();
        }
    }
}
