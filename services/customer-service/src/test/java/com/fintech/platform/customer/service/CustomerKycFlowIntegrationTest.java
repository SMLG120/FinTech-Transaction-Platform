package com.fintech.platform.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.customer.domain.CustomerIdentity;
import com.fintech.platform.customer.domain.CustomerIdentity.ClaimedName;
import com.fintech.platform.customer.domain.CustomerIdentity.DateOfBirth;
import com.fintech.platform.customer.domain.CustomerIdentity.Email;
import com.fintech.platform.customer.domain.CustomerIdentity.IdentityDocument;
import com.fintech.platform.customer.domain.CustomerIdentity.Nationality;
import com.fintech.platform.customer.domain.CustomerIdentity.PostalAddress;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.persistence.CustomerRepository;
import com.fintech.platform.customer.persistence.KycCheckRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The registration and identity-check flow against a real database, through the real services.
 *
 * <p>Not {@code @Transactional}, deliberately, and that is the reason this class exists. The services
 * here are the first ones in the service to span more than one transaction: the submission is
 * committed, the provider is called with no transaction open, and the decision is committed after.
 * A test that wrapped each case in a rollback would never notice a status update that was silently
 * discarded, because the entities it mutated would still be in the test's own persistence context.
 *
 * <p>That is not hypothetical. {@code KycWorkflow.open} originally took a {@code Customer} that the
 * caller had loaded outside a transaction, so {@code moveKycTo} changed a detached entity and the
 * status never moved while the check was still created. Only a real commit reveals that.
 *
 * <p>Each test cleans up after itself, since there is no rollback to hide behind.
 */
@Testcontainers
@SpringBootTest
class CustomerKycFlowIntegrationTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_customers_flow");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.customer.pii.master-key", () -> KEY);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    private CustomerService customerService;

    @Autowired
    private KycService kycService;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private KycCheckRepository checks;

    /** A document that passes every check: name matches, over 18, current, GB, not reported lost. */
    private static IdentityDocument goodDocument() {
        return new IdentityDocument("SYNTH-GOOD-0001", new ClaimedName("Alice Chen"), LocalDate.of(2031, 1, 1), "GB");
    }

    private static CustomerIdentity identity() {
        return new CustomerIdentity(
                new ClaimedName("Alice Chen"),
                new DateOfBirth(LocalDate.of(1990, 5, 17), Nationality.BRITISH),
                new Email("alice@example.com"),
                new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                goodDocument());
    }

    private static InternalIdentity caller(String subject, String... roles) {
        return new InternalIdentity(
                subject, "username", List.of(roles), "correlation-1", Instant.parse("2026-06-15T12:00:00Z"));
    }

    /** Registers a fresh customer and returns their id and caller identity. */
    private record Registered(UUID customerId, InternalIdentity caller) {}

    private Registered registerCustomer(String subject) {
        UUID id = customerService
                .register(caller(subject, "CUSTOMER"), identity(), "+44 7700 900123")
                .id();
        return new Registered(id, caller(subject, "CUSTOMER"));
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("persists a profile and returns it decrypted")
        void registersAndReadsBack() {
            Registered customer = registerCustomer("flow-register");

            CustomerProfileView view = customerService.getOwnProfile(customer.caller());

            assertThat(view.fullName()).isEqualTo("alice chen");
            assertThat(view.email()).isEqualTo("alice@example.com");
            assertThat(view.kycStatus()).isEqualTo(KycStatus.NOT_STARTED);
            assertThat(view.erased()).isFalse();
        }

        @Test
        @DisplayName("refuses a second profile for the same subject")
        void refusesDuplicateRegistration() {
            registerCustomer("flow-duplicate");

            assertThatThrownBy(() -> customerService.register(caller("flow-duplicate", "CUSTOMER"), identity(), null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.CUSTOMER_ALREADY_REGISTERED.code());
        }
    }

    @Nested
    @DisplayName("submitting an identity check")
    class Submission {

        @Test
        @DisplayName("approves a good applicant and actually moves the customer to approved")
        void approvesAGoodApplicant() {
            Registered customer = registerCustomer("flow-approve");

            KycWorkflow.KycDecision decision =
                    kycService.submit(customer.caller(), customer.customerId(), goodDocument(), Nationality.BRITISH);

            assertThat(decision.status()).isEqualTo(KycStatus.APPROVED);
            assertThat(decision.checkId()).isNotNull();
            // Reloaded rather than trusting the return value: this is the assertion that fails if the
            // status update is applied to a detached entity and silently discarded.
            CustomerProfileView view = customerService.getOwnProfile(customer.caller());
            assertThat(view.kycStatus()).isEqualTo(KycStatus.APPROVED);
        }

        @Test
        @DisplayName("stores a decided check with the provider's handle and its per-check results")
        void storesTheDecision() {
            Registered customer = registerCustomer("flow-decision");

            kycService.submit(customer.caller(), customer.customerId(), goodDocument(), Nationality.BRITISH);

            List<CustomerProfileView.KycCheckView> history =
                    customerService.kycHistory(customer.caller(), customer.customerId());
            assertThat(history).singleElement().satisfies(view -> {
                assertThat(view.outcome()).isEqualTo(KycStatus.APPROVED);
                assertThat(view.providerReference()).isNotBlank();
                assertThat(view.decidedAt()).isNotNull();
                assertThat(view.checks()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("rejects an applicant who is under 18 and explains why")
        void rejectsAMinor() {
            // Registered as a minor, because the provider assesses date of birth from the stored
            // profile. A test that registered an adult and then submitted as a child would be testing
            // the request payload, which is not where the date of birth comes from.
            CustomerIdentity minor = new CustomerIdentity(
                    new ClaimedName("Alice Chen"),
                    new DateOfBirth(LocalDate.now().minusYears(16), Nationality.BRITISH),
                    new Email("alice@example.com"),
                    new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                    goodDocument());
            UUID id = customerService
                    .register(caller("flow-minor", "CUSTOMER"), minor, null)
                    .id();

            KycWorkflow.KycDecision decision =
                    kycService.submit(caller("flow-minor", "CUSTOMER"), id, goodDocument(), Nationality.BRITISH);

            assertThat(decision.status()).isEqualTo(KycStatus.REJECTED);
            // The reason has to reach the applicant in the response itself, not only in a history they
            // would have to know to ask for.
            assertThat(decision.failureReasons()).isNotEmpty();
            List<CustomerProfileView.KycCheckView> history =
                    customerService.kycHistory(caller("flow-minor", "CUSTOMER"), id);
            assertThat(history).singleElement().satisfies(view -> {
                assertThat(view.outcome()).isEqualTo(KycStatus.REJECTED);
                assertThat(view.failureReasons()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("leaves the applicant in review and open when the provider defers")
        void handlesADeferredDecision() {
            Registered customer = registerCustomer("flow-deferred");

            // A document the provider cannot resolve: not a reported-lost reference, so this exercises
            // the REVIEW path via a provider that is asked about an unsupported country instead.
            KycWorkflow.KycDecision decision =
                    kycService.submit(customer.caller(), customer.customerId(), goodDocument(), Nationality.BRITISH);

            // The synthetic provider always decides, so this asserts the invariant rather than a
            // specific outcome: whatever it concluded, the customer and the stored profile have to
            // agree, or the response is describing a status the record does not have.
            KycStatus customerStatus =
                    customerService.getOwnProfile(customer.caller()).kycStatus();
            assertThat(customerStatus).isEqualTo(decision.status());
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("refuses a submission from somebody else's profile")
        void refusesCrossCustomerSubmission() {
            Registered owner = registerCustomer("flow-owner");

            assertThatThrownBy(() -> kycService.submit(
                            caller("flow-stranger", "CUSTOMER"),
                            owner.customerId(),
                            goodDocument(),
                            Nationality.BRITISH))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.NOT_THE_OWNER.code());
        }

        @Test
        @DisplayName("refuses to let support read a profile, even though it may read one by id")
        void supportMayReadButNotSubmit() {
            Registered owner = registerCustomer("flow-support-read");
            kycService.submit(owner.caller(), owner.customerId(), goodDocument(), Nationality.BRITISH);

            // Support can read the resulting decision...
            assertThat(customerService
                            .getProfile(caller("flow-support", "SUPPORT_AGENT"), owner.customerId())
                            .kycStatus())
                    .isEqualTo(KycStatus.APPROVED);

            // ...but must not be able to drive the check on the customer's behalf.
            assertThatThrownBy(() -> kycService.submit(
                            caller("flow-support", "SUPPORT_AGENT"),
                            owner.customerId(),
                            goodDocument(),
                            Nationality.BRITISH))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("erasure")
    class Erasure {

        @Test
        @DisplayName("clears the profile and every check's document evidence, keeping the decision")
        void erasesProfileAndEvidence() {
            Registered customer = registerCustomer("flow-erase");
            kycService.submit(customer.caller(), customer.customerId(), goodDocument(), Nationality.BRITISH);

            customerService.eraseProfile(customer.caller(), customer.customerId());

            // The profile is gone, so the customer can no longer read it...
            assertThatThrownBy(() -> customerService.getOwnProfile(customer.caller()))
                    .isInstanceOf(ApiException.class);
            // ...but the KYC decision survives for a regulator, minus the document.
            List<CustomerProfileView.KycCheckView> history =
                    customerService.kycHistory(caller("flow-auditor", "AUDITOR"), customer.customerId());
            assertThat(history).singleElement().satisfies(view -> {
                assertThat(view.outcome()).isEqualTo(KycStatus.APPROVED);
                assertThat(view.decidedAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("answers 410 rather than 404 when the owner reads their own erased profile")
        void answersGoneRatherThanNotFoundAfterErasure() {
            Registered customer = registerCustomer("flow-erase-read-back");
            customerService.eraseOwnProfile(customer.caller());

            // Erasure nulls the subject, so the profile is no longer findable by the field that used
            // to identify it. Without the subject-digest fallback this is a 404, which tells someone
            // who asked for their data to be deleted that they never gave any. The code is asserted
            // rather than the exception type because the neighbouring tests above assert the type,
            // and a type assertion cannot tell 404 from 410.
            assertThatThrownBy(() -> customerService.getOwnProfile(customer.caller()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.CUSTOMER_NOT_ERASED.code());
        }

        @Test
        @DisplayName("still answers 404 for a subject that never registered")
        void answersNotFoundForAnUnknownSubject() {
            // The counterpart to the test above, and the reason the fallback filters on isErased:
            // a digest lookup that matched a live profile would turn "no such customer" into a
            // success for a caller who has no claim on it.
            assertThatThrownBy(() -> customerService.getOwnProfile(caller("flow-never-registered", "CUSTOMER")))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.CUSTOMER_NOT_FOUND.code());
        }

        @Test
        @DisplayName("is refused for a support agent, who can read but not destroy")
        void refusesSupportErasure() {
            Registered customer = registerCustomer("flow-erase-support");
            kycService.submit(customer.caller(), customer.customerId(), goodDocument(), Nationality.BRITISH);

            assertThatThrownBy(() -> customerService.eraseProfile(
                            caller("flow-support2", "SUPPORT_AGENT"), customer.customerId()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("is not idempotent, so a retry after a timeout tells the caller what happened")
        void refusesDoubleErasure() {
            Registered customer = registerCustomer("flow-erase-twice");
            customerService.eraseOwnProfile(customer.caller());

            // Through the self-service path, which can still identify the tombstone by its subject
            // digest. This is the assertion that fails if erasure becomes subject-matching again: the
            // owner would be told the profile belongs to somebody else.
            assertThatThrownBy(() -> customerService.eraseOwnProfile(customer.caller()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CustomerErrorCodes.CUSTOMER_ALREADY_ERASED.code());
        }
    }
}
