package com.fintech.platform.customer.domain;

import com.fintech.platform.customer.kyc.KycProvider;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.pii.PiiCipher;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One identity-check submission and what came of it.
 *
 * <p>A check is never edited after it is decided. A rejected customer who resubmits gets a new row,
 * because the question a regulator or a dispute handler asks is "what was decided on this date, and on
 * what evidence", and overwriting the previous answer destroys it. The one thing that does change is
 * the decision itself, from in-flight to concluded, which is why that is a method rather than a setter.
 *
 * <p>The customer is referenced by id rather than through a {@code @ManyToOne}. With {@code
 * open-in-view} disabled, a lazy association read outside a transaction fails at the point of use,
 * which in a controller means a 500 that only reproduces sometimes. A plain UUID column is one less
 * way for that to happen and loses nothing here, since a check is always read alongside its customer.
 */
@Entity
@Table(name = "kyc_checks")
public class KycCheck {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /**
     * The provider's handle for the assessment, or null while in flight.
     *
     * <p>Set from the assessment rather than invented at submission time, because a made-up handle
     * looks like a real one and resolves to nothing. The database enforces that a decided row has one
     * (see {@code kyc_checks_reference_present_when_decided}).
     */
    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private KycStatus outcome;

    /** Why the decision was a rejection. Null unless {@link #outcome} is {@code REJECTED}. */
    @Column(name = "failure_reasons", columnDefinition = "TEXT")
    private String failureReasons;

    /**
     * The submitted document's reference, encrypted.
     *
     * <p>Kept so a dispute can be investigated later. Encrypted under the same per-customer key as the
     * rest of the profile, which means it stops being readable when the customer is erased. That is the
     * correct outcome: the document was part of what they asked us to remove.
     */
    @Column(name = "document_reference_encrypted")
    private String documentReferenceEncrypted;

    /** The name printed on the document, encrypted, for the same reason. */
    @Column(name = "printed_name_encrypted")
    private String printedNameEncrypted;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "kycCheck", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("checkName ASC")
    private List<KycCheckResult> results = new ArrayList<>();

    /** JPA only. */
    protected KycCheck() {}

    /**
     * Opens a check: recorded as submitted, with no decision and no provider handle yet.
     *
     * <p>Deliberately a step before {@link #decide}. Persisting the submission before calling the
     * provider means a provider that is down leaves a visible in-flight check rather than a customer
     * who appears never to have tried, and it means the submission is already durable when the
     * assessment comes back.
     */
    public static KycCheck open(UUID id, UUID customerId, Instant submittedAt) {
        KycCheck check = new KycCheck();
        check.id = Objects.requireNonNull(id, "id must not be null");
        check.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        check.outcome = KycStatus.PENDING_REVIEW;
        check.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        return check;
    }

    /**
     * Records the provider's assessment.
     *
     * <p>Each result becomes its own row so the decision stays interrogable in SQL later, which a JSON
     * column on this table would prevent.
     *
     * <p>A {@link KycProvider.Decision#REVIEW} outcome updates the status but deliberately leaves
     * {@code decidedAt} null, so the check stays the customer's current in-flight one. Marking it
     * decided would leave the customer in {@code UNDER_REVIEW} with nothing to complete, and would
     * also release the partial unique index that is meant to stop a second submission arriving while
     * the first is still open. A deferred check is not a concluded one.
     *
     * @throws IllegalStateException if this check has already been concluded
     */
    public void decide(KycProvider.Assessment assessment, Instant decidedAt) {
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        if (this.decidedAt != null) {
            throw new IllegalStateException("this identity check has already been decided");
        }
        this.providerReference = requireText(assessment.reference(), "assessment reference");
        this.outcome = switch (assessment.decision()) {
            case APPROVE -> KycStatus.APPROVED;
            case REJECT -> KycStatus.REJECTED;
            case REVIEW -> KycStatus.UNDER_REVIEW;
        };
        this.failureReasons =
                assessment.failureReasons().isEmpty() ? null : String.join("; ", assessment.failureReasons());
        this.decidedAt = assessment.decision() == KycProvider.Decision.REVIEW ? null : decidedAt;
        this.results.clear();
        for (KycProvider.CheckResult result : assessment.checks()) {
            this.results.add(KycCheckResult.of(UUID.randomUUID(), this, result));
        }
    }

    /**
     * Encrypts the document evidence against the customer's key.
     *
     * <p>Called when the check is submitted, while the plaintext is still in hand and the subject is
     * known.
     */
    public void encryptEvidence(String subject, String documentReference, String printedName, PiiCipher cipher) {
        Objects.requireNonNull(subject, "subject must not be null");
        this.documentReferenceEncrypted = documentReference == null ? null : cipher.encrypt(subject, documentReference);
        this.printedNameEncrypted = printedName == null ? null : cipher.encrypt(subject, printedName);
    }

    /**
     * Clears the encrypted document evidence.
     *
     * <p>Called when the customer is erased. The row stays and stays decided, because a regulator
     * still needs to know a check happened and what it concluded; only the document evidence goes.
     */
    public void eraseEvidence() {
        this.documentReferenceEncrypted = null;
        this.printedNameEncrypted = null;
    }

    /** @return the document reference, or null once erased */
    public String documentReference(String subject, PiiCipher cipher) {
        return decryptOrNull(subject, documentReferenceEncrypted, cipher);
    }

    /** @return the printed name, or null once erased */
    public String printedName(String subject, PiiCipher cipher) {
        return decryptOrNull(subject, printedNameEncrypted, cipher);
    }

    public boolean isDecided() {
        return decidedAt != null;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public String providerReference() {
        return providerReference;
    }

    public KycStatus outcome() {
        return outcome;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    /** @return why it was rejected; empty when it was not */
    public List<String> failureReasons() {
        if (failureReasons == null || failureReasons.isBlank()) {
            return List.of();
        }
        return List.of(failureReasons.split("; "));
    }

    public List<KycCheckResult> results() {
        return List.copyOf(results);
    }

    /** Never includes ciphertext or a failure reason. See {@link Customer#toString()}. */
    @Override
    public String toString() {
        return "KycCheck[id=" + id + ", outcome=" + outcome + ", decided=" + isDecided() + "]";
    }

    private String decryptOrNull(String subject, String encrypted, PiiCipher cipher) {
        // After an erasure there is no subject to derive a key from, so the answer is "no longer held"
        // rather than an error: the evidence is gone by design, and an auditor asking for it is
        // legitimate. Returning null keeps the erasure from turning every later read into a 500.
        if (subject == null || encrypted == null) {
            return null;
        }
        return cipher.decrypt(subject, encrypted);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /** JPA only. */
    void addResult(KycCheckResult result) {
        this.results.add(result);
    }
}
