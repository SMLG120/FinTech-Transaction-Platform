package com.fintech.platform.customer.domain;

import com.fintech.platform.customer.kyc.KycStateMachine;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.pii.PiiCipher;
import com.fintech.platform.customer.pii.PiiDecryptionException;
import com.fintech.platform.customer.pii.PiiNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer profile, the root of this service's aggregate.
 *
 * <p>Encryption happens here rather than in a JPA {@code AttributeConverter}, and that is forced
 * rather than chosen. Each customer's key is derived from their subject, and an attribute converter
 * is handed one field value at a time with no access to the rest of the row, so it cannot know which
 * subject it is encrypting for. A converter-based design would either derive every value under one
 * shared key, which is the property this whole design exists to avoid, or it would have to reach
 * around itself to read the subject.
 *
 * <p>For the same reason this class holds a {@link PiiCipher} and has no public setters. Plaintext
 * exists in memory only inside a method call; it is never a field, so it cannot be logged by a
 * toString, serialised by a Jackson mixin, or captured in a heap dump of an idle object.
 *
 * <p>Not thread-safe and not meant to be: an entity is loaded, mutated and saved within one
 * transaction, and {@link #version} turns a lost update into a conflict rather than a silent overwrite.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;

    /**
     * The Keycloak subject, and the salt for this customer's key derivation.
     *
     * <p>Null once erased. That null is the entire erasure mechanism: the ciphertext in this row
     * cannot be decrypted without the subject, so removing the subject removes the ability to read the
     * row even for someone holding the master key.
     */
    @Column(name = "subject")
    private String subject;

    /** A keyed digest of the subject, retained through erasure so a repeat request is recognisable. */
    @Column(name = "subject_digest", nullable = false, length = 64)
    private String subjectDigest;

    @Column(name = "full_name_encrypted")
    private String fullNameEncrypted;

    @Column(name = "full_name_bidx", length = 64)
    private String fullNameBlindIndex;

    @Column(name = "date_of_birth_encrypted")
    private String dateOfBirthEncrypted;

    @Column(name = "email_encrypted")
    private String emailEncrypted;

    @Column(name = "email_bidx", length = 64)
    private String emailBlindIndex;

    @Column(name = "phone_encrypted")
    private String phoneEncrypted;

    @Column(name = "phone_bidx", length = 64)
    private String phoneBlindIndex;

    @Column(name = "address_encrypted")
    private String addressEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 32)
    private KycStatus kycStatus;

    @Column(name = "erased_at")
    private Instant erasedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** JPA only. */
    protected Customer() {}

    /**
     * Creates a profile for a newly authenticated subject.
     *
     * @param subject the Keycloak subject; also the key-derivation salt
     * @param identity the claimed identity to encrypt and store
     * @param phone optional, since not every customer has one
     */
    public static Customer register(
            UUID id, String subject, CustomerIdentity identity, String phone, PiiCipher cipher, Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }

        Customer customer = new Customer();
        customer.id = id;
        customer.subject = subject;
        customer.kycStatus = KycStatus.NOT_STARTED;
        customer.createdAt = now;
        customer.updatedAt = now;
        customer.subjectDigest = cipher.subjectDigest(subject);
        customer.encryptIdentity(identity, subject, cipher);
        customer.encryptPhone(phone, subject, cipher);
        return customer;
    }

    /**
     * Replaces the stored identity, re-encrypting every value.
     *
     * <p>All fields are rewritten rather than only the ones the caller changed, because a partial
     * update would have to know which fields were supplied, and a caller that omits a field to mean
     * "unchanged" is a convention one endpoint will get wrong.
     */
    public void updateIdentity(CustomerIdentity identity, String phone, PiiCipher cipher, Instant now) {
        requireLive();
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(cipher, "cipher must not be null");
        encryptIdentity(identity, subject, cipher);
        encryptPhone(phone, subject, cipher);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Moves the identity check to a new state, refusing any move the state machine disallows.
     *
     * @throws com.fintech.platform.common.error.ApiException if the transition is not legal
     */
    public void moveKycTo(KycStatus target, Instant now) {
        requireLive();
        this.kycStatus = KycStateMachine.require(this.kycStatus, target);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Erases the profile, keeping only what an audit trail needs.
     *
     * <p>Idempotent in effect but not in outcome: erasing an already-erased profile throws, because a
     * caller asking twice is a bug or a probe and both deserve to be visible. It is deliberately not
     * a silent no-op, which would let a broken caller believe it had erased a live profile.
     *
     * <p>The ciphertext is nulled rather than overwritten with a random value. Nulling is what the
     * database check constraint requires, and it is sufficient: without the subject the remaining
     * bytes are already unreadable, so a ciphertext left in place would be a liability without being a
     * risk.
     */
    public void erase(PiiCipher cipher, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (isErased()) {
            throw com.fintech.platform.customer.error.CustomerErrorCodes.CUSTOMER_ALREADY_ERASED.exception();
        }
        this.erasedAt = now;
        this.subject = null;
        this.fullNameEncrypted = null;
        this.fullNameBlindIndex = null;
        this.dateOfBirthEncrypted = null;
        this.emailEncrypted = null;
        this.emailBlindIndex = null;
        this.phoneEncrypted = null;
        this.phoneBlindIndex = null;
        this.addressEncrypted = null;
        this.kycStatus = KycStatus.NOT_STARTED;
        this.updatedAt = now;
    }

    // ---------------------------------------------------------------------------------------
    // Reads. Each returns null for an erased profile rather than throwing, because "this profile
    // has no name" is the correct answer for a tombstone and an exception here would turn a routine
    // read of an erased customer into a 500.
    // ---------------------------------------------------------------------------------------

    /** @return the decrypted name, or null once erased */
    public String fullName(PiiCipher cipher) {
        return decryptOrNull(cipher, fullNameEncrypted);
    }

    /** @return the decrypted date of birth, or null once erased */
    public LocalDate dateOfBirth(PiiCipher cipher) {
        String decrypted = decryptOrNull(cipher, dateOfBirthEncrypted);
        return decrypted == null ? null : LocalDate.parse(decrypted);
    }

    /** @return the decrypted email, or null once erased */
    public String email(PiiCipher cipher) {
        return decryptOrNull(cipher, emailEncrypted);
    }

    /** @return the decrypted phone, or null if never supplied or since erased */
    public String phone(PiiCipher cipher) {
        return decryptOrNull(cipher, phoneEncrypted);
    }

    /** @return the decrypted address, or null once erased */
    public CustomerIdentity.PostalAddress address(PiiCipher cipher) {
        String decrypted = decryptOrNull(cipher, addressEncrypted);
        return decrypted == null ? null : decodeAddress(decrypted);
    }

    /**
     * Whether this profile already holds this email.
     *
     * <p>Compares blind indexes, not ciphertexts. Encryption is randomised, so the same address
     * encrypts to different bytes every time and a ciphertext comparison would report every repeat
     * registration as new.
     */
    public boolean hasEmail(String candidate, PiiCipher cipher) {
        return emailBlindIndex != null && emailBlindIndex.equals(cipher.blindIndex(PiiNormalizer.email(candidate)));
    }

    /** Whether this profile already holds this phone number, compared on its normalised form. */
    public boolean hasPhone(String candidate, PiiCipher cipher) {
        return phoneBlindIndex != null && phoneBlindIndex.equals(cipher.blindIndex(PiiNormalizer.phone(candidate)));
    }

    public boolean isErased() {
        return erasedAt != null;
    }

    public UUID id() {
        return id;
    }

    public String subject() {
        return subject;
    }

    public String subjectDigest() {
        return subjectDigest;
    }

    public KycStatus kycStatus() {
        return kycStatus;
    }

    public Instant erasedAt() {
        return erasedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /**
     * Never includes ciphertext or plaintext.
     *
     * <p>A generated toString on an entity is how PII ends up in a log line, and a ciphertext in a log
     * line is still a durable copy of a customer's data outside the database's access controls. This
     * says what is useful for debugging and nothing more.
     */
    @Override
    public String toString() {
        return "Customer[id=" + id + ", kycStatus=" + kycStatus + ", erased=" + isErased() + "]";
    }

    // ---------------------------------------------------------------------------------------

    private void encryptIdentity(CustomerIdentity identity, String saltSubject, PiiCipher cipher) {
        this.fullNameEncrypted = cipher.encrypt(saltSubject, identity.name().value());
        this.fullNameBlindIndex = cipher.blindIndex(identity.name().value());
        this.dateOfBirthEncrypted =
                cipher.encrypt(saltSubject, identity.dateOfBirth().value().toString());
        this.emailEncrypted = cipher.encrypt(saltSubject, identity.email().value());
        this.emailBlindIndex = cipher.blindIndex(identity.email().value());
        this.addressEncrypted = cipher.encrypt(saltSubject, encodeAddress(identity.address()));
    }

    /**
     * Encrypts an optional phone number.
     *
     * <p>Both columns are cleared together first, so a customer removing their phone number does not
     * leave a stale blind index behind that would still match a lookup for the old number.
     */
    private void encryptPhone(String phone, String saltSubject, PiiCipher cipher) {
        String normalised = phone == null || phone.isBlank() ? null : PiiNormalizer.phone(phone);
        this.phoneEncrypted = normalised == null ? null : cipher.encrypt(saltSubject, normalised);
        this.phoneBlindIndex = normalised == null ? null : cipher.blindIndex(normalised);
    }

    private static String encodeAddress(CustomerIdentity.PostalAddress address) {
        return String.join(
                "\n",
                address.line1(),
                address.line2() == null ? "" : address.line2(),
                address.city(),
                address.postalCode(),
                address.country());
    }

    private static CustomerIdentity.PostalAddress decodeAddress(String encoded) {
        String[] parts = encoded.split("\n", -1);
        if (parts.length != 5) {
            throw new IllegalStateException("stored address is malformed");
        }
        return new CustomerIdentity.PostalAddress(
                parts[0], parts[1].isEmpty() ? null : parts[1], parts[2], parts[3], parts[4]);
    }

    private String decryptOrNull(PiiCipher cipher, String encrypted) {
        if (isErased() || encrypted == null) {
            return null;
        }
        try {
            return cipher.decrypt(subject, encrypted);
        } catch (PiiDecryptionException e) {
            // A stored value this build cannot read is a deployment or data problem, never something
            // to pass on to the caller as an empty field: that would look like the customer simply had
            // no email on file.
            throw new IllegalStateException("stored PII for customer " + id + " could not be decrypted", e);
        }
    }

    private void requireLive() {
        if (isErased()) {
            throw com.fintech.platform.customer.error.CustomerErrorCodes.CUSTOMER_NOT_ERASED.exception();
        }
    }
}
