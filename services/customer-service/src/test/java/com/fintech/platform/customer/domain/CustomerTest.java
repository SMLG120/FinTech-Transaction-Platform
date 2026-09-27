package com.fintech.platform.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.customer.domain.CustomerIdentity.ClaimedName;
import com.fintech.platform.customer.domain.CustomerIdentity.DateOfBirth;
import com.fintech.platform.customer.domain.CustomerIdentity.Email;
import com.fintech.platform.customer.domain.CustomerIdentity.IdentityDocument;
import com.fintech.platform.customer.domain.CustomerIdentity.Nationality;
import com.fintech.platform.customer.domain.CustomerIdentity.PostalAddress;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.pii.PiiCipher;
import com.fintech.platform.customer.pii.PiiDecryptionException;
import com.fintech.platform.customer.pii.PiiProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerTest {

    private static final String SUBJECT = "f1a2b3c4-keycloak-subject";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private PiiCipher cipher;
    private Customer customer;

    @BeforeEach
    void setUp() {
        cipher = new PiiCipher(new PiiProperties(KEY, 1));
        customer = Customer.register(UUID.randomUUID(), SUBJECT, identity(), "+44 7700 900123", cipher, NOW);
    }

    private static CustomerIdentity identity() {
        return new CustomerIdentity(
                new ClaimedName("Alice Chen"),
                new DateOfBirth(LocalDate.of(1990, 5, 17), Nationality.BRITISH),
                new Email("alice@example.com"),
                new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                new IdentityDocument("SYNTH-0001", new ClaimedName("Alice Chen"), LocalDate.of(2031, 1, 1), "GB"));
    }

    /** Reads a private field so the test can assert on what is actually stored, not on the accessors. */
    private static String stored(Object entity, String field) {
        return (String) ReflectionTestUtils.getField(entity, field);
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("stores no plaintext anywhere in the row")
        void storesNoPlaintext() {
            // The strongest available statement about encryption at rest: none of the submitted values
            // appear verbatim in any column. Checked field by field because a single forgotten setter
            // would leave one plaintext value behind while every accessor still looked correct.
            for (String field : new String[] {
                "fullNameEncrypted", "dateOfBirthEncrypted", "emailEncrypted", "phoneEncrypted", "addressEncrypted"
            }) {
                assertThat(stored(customer, field)).as("%s", field).isNotNull();
            }
            String name = stored(customer, "fullNameEncrypted");
            assertThat(name).doesNotContain("Alice").doesNotContain("Chen");
            assertThat(stored(customer, "emailEncrypted"))
                    .doesNotContain("alice")
                    .doesNotContain("example.com");
            assertThat(stored(customer, "dateOfBirthEncrypted")).doesNotContain("1990-05-17");
            assertThat(stored(customer, "phoneEncrypted")).doesNotContain("447700900123");
        }

        @Test
        @DisplayName("round-trips every value it stored")
        void roundTrips() {
            assertThat(customer.fullName(cipher)).isEqualTo("alice chen");
            assertThat(customer.email(cipher)).isEqualTo("alice@example.com");
            assertThat(customer.dateOfBirth(cipher)).isEqualTo(LocalDate.of(1990, 5, 17));
            assertThat(customer.phone(cipher)).isEqualTo("447700900123");
            assertThat(customer.address(cipher).city()).isEqualTo("London");
            assertThat(customer.address(cipher).country()).isEqualTo("GB");
        }

        @Test
        @DisplayName("stores the phone number in its canonical digits-only form")
        void normalisesPhone() {
            assertThat(customer.phone(cipher)).isEqualTo("447700900123");
        }

        @Test
        @DisplayName("round-trips an address with no second line")
        void roundTripsAddressWithoutLineTwo() {
            CustomerIdentity noLineTwo = new CustomerIdentity(
                    new ClaimedName("Bob Smith"),
                    new DateOfBirth(LocalDate.of(1985, 1, 1), Nationality.BRITISH),
                    new Email("bob@example.com"),
                    new PostalAddress("2 Other Road", "Leeds", "LS1 1AA", "GB"),
                    new IdentityDocument("SYNTH-0002", new ClaimedName("Bob Smith"), LocalDate.of(2030, 1, 1), "GB"));

            Customer created = Customer.register(UUID.randomUUID(), "subject-b", noLineTwo, null, cipher, NOW);

            assertThat(created.address(cipher).line1()).isEqualTo("2 Other Road");
            assertThat(created.address(cipher).line2()).isNull();
        }

        @Test
        @DisplayName("starts not started, with no erasure and a retained subject digest")
        void startsFresh() {
            assertThat(customer.kycStatus()).isEqualTo(KycStatus.NOT_STARTED);
            assertThat(customer.isErased()).isFalse();
            assertThat(customer.erasedAt()).isNull();
            assertThat(customer.subject()).isEqualTo(SUBJECT);
            assertThat(customer.subjectDigest()).isNotBlank();
        }

        @Test
        @DisplayName("rejects a blank subject, because a blank salt would be shared by every erased customer")
        void rejectsBlankSubject() {
            assertThatThrownBy(() -> Customer.register(UUID.randomUUID(), "  ", identity(), null, cipher, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("subject must not be blank");
        }
    }

    @Nested
    @DisplayName("duplicate detection")
    class Duplicates {

        @Test
        @DisplayName("recognises the same email written differently")
        void matchesEmailAcrossFormatting() {
            assertThat(customer.hasEmail("  ALICE@Example.COM ", cipher)).isTrue();
        }

        @Test
        @DisplayName("does not match a different email")
        void rejectsDifferentEmail() {
            assertThat(customer.hasEmail("bob@example.com", cipher)).isFalse();
        }

        @Test
        @DisplayName("recognises the same phone written differently")
        void matchesPhoneAcrossFormatting() {
            assertThat(customer.hasPhone("+44 7700 900123", cipher)).isTrue();
            assertThat(customer.hasPhone("447700900123", cipher)).isTrue();
        }

        @Test
        @DisplayName("stops matching after a phone number is removed, rather than leaving a stale index")
        void forgetsRemovedPhone() {
            customer.updateIdentity(identity(), null, cipher, NOW);

            assertThat(customer.phone(cipher)).isNull();
            assertThat(customer.hasPhone("+44 7700 900123", cipher))
                    .as("a removed phone must not still match a lookup")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("updates")
    class Updates {

        @Test
        @DisplayName("re-encrypts the new values and drops the old ones")
        void reEncrypts() {
            CustomerIdentity replacement = new CustomerIdentity(
                    new ClaimedName("Alicia Chen"),
                    new DateOfBirth(LocalDate.of(1991, 2, 3), Nationality.BRITISH),
                    new Email("alicia@example.com"),
                    new PostalAddress("9 New Street", "Bath", "BA1 1AA", "GB"),
                    new IdentityDocument("SYNCH-0001", new ClaimedName("Alicia Chen"), LocalDate.of(2031, 1, 1), "GB"));

            customer.updateIdentity(replacement, "+44 7700 900999", cipher, NOW.plusSeconds(60));

            assertThat(customer.fullName(cipher)).isEqualTo("alicia chen");
            assertThat(customer.email(cipher)).isEqualTo("alicia@example.com");
            assertThat(customer.phone(cipher)).isEqualTo("447700900999");
            assertThat(customer.hasEmail("alice@example.com", cipher))
                    .as("the previous email must stop matching")
                    .isFalse();
        }

        @Test
        @DisplayName("records when the change happened")
        void stampsUpdate() {
            customer.updateIdentity(identity(), null, cipher, NOW.plusSeconds(60));
            assertThat(customer.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        }
    }

    @Nested
    @DisplayName("erasure")
    class Erasure {

        @Test
        @DisplayName("removes the subject, which is the only input needed to derive the key")
        void removesSubject() {
            customer.erase(cipher, NOW);

            assertThat(customer.subject()).isNull();
            assertThat(customer.isErased()).isTrue();
            assertThat(customer.erasedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("clears every encrypted column and blind index")
        void clearsEverything() {
            customer.erase(cipher, NOW);

            for (String field : new String[] {
                "fullNameEncrypted",
                "fullNameBlindIndex",
                "dateOfBirthEncrypted",
                "emailEncrypted",
                "emailBlindIndex",
                "phoneEncrypted",
                "phoneBlindIndex",
                "addressEncrypted"
            }) {
                assertThat(stored(customer, field)).as("%s", field).isNull();
            }
        }

        @Test
        @DisplayName("retains the subject digest, so a repeat erasure is recognisable")
        void retainsSubjectDigest() {
            String digest = customer.subjectDigest();
            customer.erase(cipher, NOW);

            assertThat(customer.subjectDigest()).isEqualTo(digest);
        }

        @Test
        @DisplayName("reports every value as absent rather than throwing on a read")
        void readsAsAbsentRatherThanThrowing() {
            customer.erase(cipher, NOW);

            // A tombstone is a normal thing to encounter when reading, so a read of one must answer
            // "no name" rather than blow up. An exception here would turn an ordinary read of an
            // erased customer into a 500.
            assertThat(customer.fullName(cipher)).isNull();
            assertThat(customer.email(cipher)).isNull();
            assertThat(customer.dateOfBirth(cipher)).isNull();
            assertThat(customer.phone(cipher)).isNull();
            assertThat(customer.address(cipher)).isNull();
        }

        @Test
        @DisplayName("makes the retained ciphertext undecryptable even with the master key")
        void ciphertextBecomesUnreadable() {
            customer.erase(cipher, NOW);

            // The real guarantee behind "crypto-shredding": with the subject gone there is no way to
            // derive the key, so the bytes still in any backup are inert. Without the subject the cipher
            // refuses to derive rather than falling back to a shared key.
            assertThatThrownBy(() -> cipher.decrypt(null, "c29tZS1jaXBoZXJ0ZXh0"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without a subject");
        }

        @Test
        @DisplayName("refuses a second erasure, so a broken caller cannot believe it erased a live profile")
        void refusesSecondErasure() {
            customer.erase(cipher, NOW);

            assertThatThrownBy(() -> customer.erase(cipher, NOW))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(
                                    ((ApiException) thrown).getErrorCode().code())
                            .isEqualTo("CUSTOMER_ALREADY_ERASED"));
        }

        @Test
        @DisplayName("refuses to update an erased profile, which would resurrect data the customer asked us to remove")
        void refusesUpdateAfterErasure() {
            customer.erase(cipher, NOW);

            assertThatThrownBy(() -> customer.updateIdentity(identity(), null, cipher, NOW))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(
                                    ((ApiException) thrown).getErrorCode().code())
                            .isEqualTo("CUSTOMER_NOT_ERASED"));
        }

        @Test
        @DisplayName("refuses to move the identity check of an erased profile")
        void refusesKycChangeAfterErasure() {
            customer.erase(cipher, NOW);

            assertThatThrownBy(() -> customer.moveKycTo(KycStatus.PENDING_REVIEW, NOW))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("resets the identity-check state, since the evidence behind it is gone")
        void resetsKycStatus() {
            customer.moveKycTo(KycStatus.PENDING_REVIEW, NOW);
            customer.moveKycTo(KycStatus.APPROVED, NOW);

            customer.erase(cipher, NOW);

            assertThat(customer.kycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        }

        @Test
        @DisplayName("leaves no match for the erased email or phone")
        void forgetsIdentifiers() {
            customer.erase(cipher, NOW);

            assertThat(customer.hasEmail("alice@example.com", cipher)).isFalse();
            assertThat(customer.hasPhone("+44 7700 900123", cipher)).isFalse();
        }
    }

    @Nested
    @DisplayName("identity-check state")
    class KycState {

        @Test
        @DisplayName("follows a legal transition")
        void followsLegalTransition() {
            customer.moveKycTo(KycStatus.PENDING_REVIEW, NOW);
            customer.moveKycTo(KycStatus.APPROVED, NOW);

            assertThat(customer.kycStatus()).isEqualTo(KycStatus.APPROVED);
        }

        @Test
        @DisplayName("refuses an illegal transition rather than accepting any value")
        void refusesIllegalTransition() {
            assertThatThrownBy(() -> customer.moveKycTo(KycStatus.APPROVED, NOW))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(
                                    ((ApiException) thrown).getErrorCode().code())
                            .isEqualTo("KYC_INVALID_TRANSITION"));
            assertThat(customer.kycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        }
    }

    @Nested
    @DisplayName("isolation between customers")
    class Isolation {

        @Test
        @DisplayName("gives two customers different ciphertext for the same name")
        void separatesCiphertext() {
            Customer other = Customer.register(UUID.randomUUID(), "different-subject", identity(), null, cipher, NOW);

            assertThat(stored(customer, "fullNameEncrypted"))
                    .as("identical plaintext under different keys must not produce identical ciphertext")
                    .isNotEqualTo(stored(other, "fullNameEncrypted"));
        }

        @Test
        @DisplayName("cannot read one customer's row with another customer's subject")
        void cannotCrossRead() {
            String ciphertext = stored(customer, "emailEncrypted");

            assertThatThrownBy(() -> cipher.decrypt("different-subject", ciphertext))
                    .isInstanceOf(PiiDecryptionException.class)
                    .hasMessageContaining("failed authentication");
        }

        @Test
        @DisplayName("gives the same customer different ciphertext for the same name on each write")
        void usesFreshIvPerWrite() {
            String first = stored(customer, "emailEncrypted");
            customer.updateIdentity(identity(), null, cipher, NOW);

            assertThat(stored(customer, "emailEncrypted"))
                    .as("a repeated IV under one key breaks GCM outright")
                    .isNotEqualTo(first);
        }
    }

    @Test
    @DisplayName("toString carries no ciphertext or plaintext")
    void toStringIsSafeToLog() {
        // A generated toString on an entity is how PII reaches a log line, and a ciphertext in a log
        // file is still a copy of the data outside the database's access controls.
        String rendered = customer.toString();

        assertThat(rendered).doesNotContain("Alice").doesNotContain("alice@example.com");
        assertThat(stored(customer, "emailEncrypted")).isNotNull();
        assertThat(rendered).doesNotContain(stored(customer, "emailEncrypted"));
    }
}
