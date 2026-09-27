package com.fintech.platform.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.customer.domain.CustomerIdentity.ClaimedName;
import com.fintech.platform.customer.domain.CustomerIdentity.DateOfBirth;
import com.fintech.platform.customer.domain.CustomerIdentity.Email;
import com.fintech.platform.customer.domain.CustomerIdentity.IdentityDocument;
import com.fintech.platform.customer.domain.CustomerIdentity.Nationality;
import com.fintech.platform.customer.domain.CustomerIdentity.PostalAddress;
import com.fintech.platform.customer.kyc.Check;
import com.fintech.platform.customer.kyc.KycProvider;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.pii.PiiCipher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the aggregate against the real database and the real schema.
 *
 * <p>Worth the container because the parts most likely to be wrong are the parts a mocked repository
 * cannot see. Hibernate's {@code ddl-auto: validate} will refuse to start if a column name, type or
 * nullability here disagrees with {@code V1__create_customers_and_kyc.sql}, and the two CHECK
 * constraints that make erasure all-or-nothing only exist in the database. Both were wrong at least
 * once while this was being written, and neither would have been caught without a real Postgres.
 *
 * <p>The PII key is set as a dynamic property rather than through a checked-in test
 * {@code application.yml}, so this file cannot become a second source of configuration that shadows
 * the real one.
 */
@Testcontainers
@SpringBootTest
@Transactional
class CustomerPersistenceTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_customers_test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.customer.pii.master-key", () -> KEY);
        // No broker in this test: the aggregate does not publish anything, and a test that needed one
        // would be testing the publisher rather than the persistence.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private PiiCipher cipher;

    @Test
    @DisplayName("round-trips a profile with every column written and read back")
    void roundTripsThroughTheDatabase() {
        UUID id = UUID.randomUUID();
        Customer saved = Customer.register(id, "subject-round-trip", identity(), "+44 7700 900123", cipher, NOW);
        entityManager.persist(saved);
        entityManager.flush();
        entityManager.clear();

        Customer reloaded = entityManager.find(Customer.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.subject()).isEqualTo("subject-round-trip");
        assertThat(reloaded.fullName(cipher)).isEqualTo("alice chen");
        assertThat(reloaded.email(cipher)).isEqualTo("alice@example.com");
        assertThat(reloaded.dateOfBirth(cipher)).isEqualTo(LocalDate.of(1990, 5, 17));
        assertThat(reloaded.phone(cipher)).isEqualTo("447700900123");
        assertThat(reloaded.address(cipher).city()).isEqualTo("London");
        assertThat(reloaded.kycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        assertThat(reloaded.isErased()).isFalse();
    }

    @Test
    @DisplayName("writes ciphertext to the column, not plaintext")
    void writesCiphertextToTheColumn() {
        UUID id = UUID.randomUUID();
        Customer saved = Customer.register(id, "subject-ciphertext-check", identity(), null, cipher, NOW);
        entityManager.persist(saved);
        entityManager.flush();
        entityManager.clear();

        Object stored = entityManager
                .createNativeQuery("SELECT email_encrypted FROM customers WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();

        assertThat(stored).asString().doesNotContain("alice").doesNotContain("example.com");
    }

    @Test
    @DisplayName("persists an erasure that satisfies the database's all-or-nothing constraint")
    void persistsErasure() {
        UUID id = UUID.randomUUID();
        Customer saved = Customer.register(id, "subject-erasure", identity(), null, cipher, NOW);
        entityManager.persist(saved);
        entityManager.flush();

        saved.erase(cipher, NOW);
        entityManager.flush();
        entityManager.clear();

        Customer reloaded = entityManager.find(Customer.class, id);

        assertThat(reloaded.isErased()).isTrue();
        assertThat(reloaded.subject()).isNull();
        assertThat(reloaded.subjectDigest())
                .as("retained for a repeat-erasure check")
                .isNotBlank();
        assertThat(reloaded.fullName(cipher)).isNull();
    }

    @Test
    @DisplayName("lets two customers erase without a unique violation on the nulled subject")
    void erasesTwoCustomers() {
        // The subject column is nullable precisely so it can be nulled on erasure, which a plain
        // UNIQUE(subject) would reject for the second customer. This is the case the partial index
        // in V1 exists for, so it is worth proving against the real constraint.
        for (String subject : new String[] {"subject-erase-a", "subject-erase-b"}) {
            Customer saved = Customer.register(UUID.randomUUID(), subject, identity(), null, cipher, NOW);
            entityManager.persist(saved);
            saved.erase(cipher, NOW);
        }
        entityManager.flush();

        Long erased = (Long) entityManager
                .createNativeQuery("SELECT count(*) FROM customers WHERE erased_at IS NOT NULL")
                .getSingleResult();
        Long withSubject = (Long) entityManager
                .createNativeQuery("SELECT count(subject) FROM customers WHERE erased_at IS NOT NULL")
                .getSingleResult();

        assertThat(erased).isEqualTo(2L);
        assertThat(withSubject).as("no subject survives an erasure").isZero();
    }

    @Test
    @DisplayName("rejects a duplicate live subject, which is the database backstop for a double registration")
    void rejectsDuplicateSubject() {
        entityManager.persist(Customer.register(UUID.randomUUID(), "subject-dupe", identity(), null, cipher, NOW));
        entityManager.flush();

        entityManager.persist(Customer.register(UUID.randomUUID(), "subject-dupe", identity(), null, cipher, NOW));

        // A second profile for one subject is exactly what a double-submitted registration form
        // produces, and it must not end up as two customers. Asserted as a data-integrity violation
        // rather than a specific exception type so the test does not pin Hibernate's internals.
        org.assertj.core.api.Assertions.assertThatThrownBy(entityManager::flush)
                .isInstanceOf(jakarta.persistence.PersistenceException.class)
                .hasMessageContaining("customers_subject_unique");
    }

    @Test
    @DisplayName("persists an identity-check transition")
    void persistsKycTransition() {
        UUID id = UUID.randomUUID();
        Customer saved = Customer.register(id, "subject-kyc", identity(), null, cipher, NOW);
        entityManager.persist(saved);
        saved.moveKycTo(KycStatus.PENDING_REVIEW, NOW);
        saved.moveKycTo(KycStatus.APPROVED, NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Customer.class, id).kycStatus()).isEqualTo(KycStatus.APPROVED);
    }

    @Test
    @DisplayName("generates an id before insert, so callers never persist a null key")
    void requiresAnId() {
        // Left to the application rather than a database default, because a UUID assigned by Postgres
        // cannot be known before the insert, and an event published on registration has to carry one.
        assertThat(Customer.register(UUID.randomUUID(), "subject-id", identity(), null, cipher, NOW)
                        .id())
                .isNotNull();
    }

    @Nested
    @DisplayName("identity checks")
    class IdentityChecks {

        @Test
        @DisplayName("round-trips a decided check with its per-check result rows")
        void roundTripsAnIdentityCheck() {
            UUID customerId = persistCustomer("subject-check-round-trip");
            KycCheck check = KycCheck.open(UUID.randomUUID(), customerId, NOW);
            check.encryptEvidence("subject-check-round-trip", "SYNTH-0001", "Alice Chen", cipher);
            check.decide(approval("SYNTH-REF-1"), NOW.plusSeconds(4));
            entityManager.persist(check);
            entityManager.flush();
            entityManager.clear();

            KycCheck reloaded = entityManager.find(KycCheck.class, check.id());

            assertThat(reloaded.outcome()).isEqualTo(KycStatus.APPROVED);
            assertThat(reloaded.providerReference()).isEqualTo("SYNTH-REF-1");
            assertThat(reloaded.decidedAt()).isEqualTo(NOW.plusSeconds(4));
            assertThat(reloaded.documentReference("subject-check-round-trip", cipher))
                    .isEqualTo("SYNTH-0001");
            // Touching the lazy collection inside the transaction; reading it after one is the
            // open-in-view failure this test class avoids by referencing the customer by id.
            assertThat(reloaded.results()).hasSize(3);
        }

        @Test
        @DisplayName("writes document evidence as ciphertext and clears it on erasure")
        void erasesDocumentEvidenceButKeepsTheDecision() {
            UUID customerId = persistCustomer("subject-check-erasure");
            KycCheck check = KycCheck.open(UUID.randomUUID(), customerId, NOW);
            check.encryptEvidence("subject-check-erasure", "SYNTH-0001", "Alice Chen", cipher);
            check.decide(rejection("SYNTH-REF-2"), NOW.plusSeconds(4));
            entityManager.persist(check);
            entityManager.flush();

            Object before = entityManager
                    .createNativeQuery("SELECT document_reference_encrypted FROM kyc_checks WHERE id = :id")
                    .setParameter("id", check.id())
                    .getSingleResult();
            assertThat(before).asString().doesNotContain("SYNTH-0001");

            check.eraseEvidence();
            entityManager.flush();
            entityManager.clear();

            KycCheck reloaded = entityManager.find(KycCheck.class, check.id());
            assertThat(reloaded.documentReference("subject-check-erasure", cipher))
                    .isNull();
            assertThat(reloaded.outcome()).isEqualTo(KycStatus.REJECTED);
            assertThat(reloaded.failureReasons()).containsExactly("applicant is under 18");
        }

        @Test
        @DisplayName("stores an in-flight check with a null provider handle")
        void storesAnInFlightCheck() {
            UUID customerId = persistCustomer("subject-check-inflight");
            KycCheck check = KycCheck.open(UUID.randomUUID(), customerId, NOW);
            entityManager.persist(check);
            entityManager.flush();
            entityManager.clear();

            KycCheck reloaded = entityManager.find(KycCheck.class, check.id());
            assertThat(reloaded.providerReference()).isNull();
            assertThat(reloaded.isDecided()).isFalse();
        }

        @Test
        @DisplayName("rejects a decided check that has no provider handle")
        void refusesADecidedCheckWithoutAProviderHandle() {
            UUID customerId = persistCustomer("subject-check-no-ref");
            entityManager.persist(KycCheck.open(UUID.randomUUID(), customerId, NOW));
            entityManager.flush();

            // Written past the aggregate on purpose: the aggregate cannot produce this state, and that
            // is the point. The column is nullable so an in-flight row can be inserted before the
            // provider is called, which means the database is the only thing left enforcing this.
            assertThatThrownBy(() -> entityManager
                            .createNativeQuery("""
                                    INSERT INTO kyc_checks
                                        (id, customer_id, outcome, submitted_at, decided_at)
                                    VALUES (:id, :customerId, 'APPROVED', :submittedAt, :decidedAt)
                                    """)
                            .setParameter("id", UUID.randomUUID())
                            .setParameter("customerId", customerId)
                            .setParameter("submittedAt", NOW)
                            .setParameter("decidedAt", NOW.plusSeconds(4))
                            .executeUpdate())
                    .hasMessageContaining("kyc_checks_reference_present_when_decided");
        }

        @Test
        @DisplayName("rejects a failed check result that carries no reason")
        void refusesAFailedCheckWithNoReason() {
            UUID customerId = persistCustomer("subject-check-no-reason");
            KycCheck check = KycCheck.open(UUID.randomUUID(), customerId, NOW);
            entityManager.persist(check);
            entityManager.flush();

            assertThatThrownBy(() -> entityManager
                            .createNativeQuery("""
                                    INSERT INTO kyc_check_results
                                        (id, kyc_check_id, check_name, passed, reason)
                                    VALUES (:id, :checkId, 'DOCUMENT_CURRENT', false, NULL)
                                    """)
                            .setParameter("id", UUID.randomUUID())
                            .setParameter("checkId", check.id())
                            .executeUpdate())
                    .hasMessageContaining("kyc_check_results_failure_has_reason");
        }

        @Test
        @DisplayName("allows at most one in-flight check per customer")
        void refusesASecondInFlightCheck() {
            UUID customerId = persistCustomer("subject-check-concurrent");
            entityManager.persist(KycCheck.open(UUID.randomUUID(), customerId, NOW));
            entityManager.flush();
            entityManager.persist(KycCheck.open(UUID.randomUUID(), customerId, NOW.plusSeconds(1)));

            // The partial unique index, not application code, is what actually stops two concurrent
            // submissions from both looking successful.
            assertThatThrownBy(() -> entityManager.flush()).hasMessageContaining("kyc_checks_current_per_customer");
        }

        @Test
        @DisplayName("refuses two of one customer's checks claiming the same provider reference")
        void refusesADuplicateProviderReferenceForOneCustomer() {
            UUID customerId = persistCustomer("subject-check-duplicate-ref");
            KycCheck first = KycCheck.open(UUID.randomUUID(), customerId, NOW);
            first.decide(approval("SYNTH-REF-DUPLICATE"), NOW.plusSeconds(2));
            entityManager.persist(first);
            entityManager.flush();
            KycCheck second = KycCheck.open(UUID.randomUUID(), customerId, NOW.plusSeconds(3));
            second.decide(approval("SYNTH-REF-DUPLICATE"), NOW.plusSeconds(4));
            entityManager.persist(second);

            // Resolving one of a customer's checks by the provider's handle has to be unambiguous. If
            // it were not, a manual decision applied to the reference would land on whichever row the
            // query happened to return, and a compliance decision would be made by accident.
            assertThatThrownBy(() -> entityManager.flush()).hasMessageContaining("kyc_checks_provider_reference_uidx");
        }

        @Test
        @DisplayName("allows two customers to present the same document reference")
        void allowsASharedProviderReferenceAcrossCustomers() {
            UUID first = persistCustomer("subject-check-shared-ref-1");
            UUID second = persistCustomer("subject-check-shared-ref-2");
            KycCheck one = KycCheck.open(UUID.randomUUID(), first, NOW);
            one.decide(approval("SYNTH-0001"), NOW.plusSeconds(2));
            entityManager.persist(one);
            KycCheck other = KycCheck.open(UUID.randomUUID(), second, NOW.plusSeconds(1));
            other.decide(approval("SYNTH-0001"), NOW.plusSeconds(3));
            entityManager.persist(other);
            entityManager.flush();

            // A platform-wide unique constraint here would be a denial-of-service rather than a
            // safety rule: whoever submitted first would permanently block the second customer from
            // ever completing an identity check, by quoting a document number they already hold.
            assertThat(entityManager
                            .createQuery("select count(c) from KycCheck c", Long.class)
                            .getSingleResult())
                    .isEqualTo(2L);
        }

        private UUID persistCustomer(String subject) {
            UUID id = UUID.randomUUID();
            entityManager.persist(Customer.register(id, subject, identity(), null, cipher, NOW));
            entityManager.flush();
            return id;
        }

        private static KycProvider.Assessment approval(String reference) {
            return new KycProvider.Assessment(
                    KycProvider.Decision.APPROVE,
                    java.util.List.of(
                            new KycProvider.CheckResult(Check.NAME_MATCHES_DOCUMENT, true, null),
                            new KycProvider.CheckResult(Check.AGE_ELIGIBLE, true, null),
                            new KycProvider.CheckResult(Check.DOCUMENT_CURRENT, true, null)),
                    reference,
                    java.util.List.of());
        }

        private static KycProvider.Assessment rejection(String reference) {
            return new KycProvider.Assessment(
                    KycProvider.Decision.REJECT,
                    java.util.List.of(new KycProvider.CheckResult(Check.AGE_ELIGIBLE, false, "applicant is under 18")),
                    reference,
                    java.util.List.of("applicant is under 18"));
        }
    }

    private static CustomerIdentity identity() {
        return new CustomerIdentity(
                new ClaimedName("Alice Chen"),
                new DateOfBirth(LocalDate.of(1990, 5, 17), Nationality.BRITISH),
                new Email("alice@example.com"),
                new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                new IdentityDocument("SYNTH-0001", new ClaimedName("Alice Chen"), LocalDate.of(2031, 1, 1), "GB"));
    }
}
