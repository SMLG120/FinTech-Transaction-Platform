package com.fintech.platform.customer.service;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.customer.domain.Customer;
import com.fintech.platform.customer.domain.CustomerIdentity;
import com.fintech.platform.customer.domain.KycCheck;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import com.fintech.platform.customer.persistence.CustomerRepository;
import com.fintech.platform.customer.persistence.KycCheckRepository;
import com.fintech.platform.customer.pii.PiiCipher;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer registration, profile reads and updates, and erasure.
 *
 * <p>Every method that names a customer takes the caller's {@link InternalIdentity} as its first
 * argument and authorises inside this class. That is the whole point of the signature: a method that
 * took only a customer id would be an unauthenticated read from the point of view of anyone who calls
 * it later, and the only thing marking it as safe would be a comment.
 *
 * <p>Every read and write goes through {@link PiiCipher}, so no method here handles plaintext beyond
 * the value it is immediately returning.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final KycCheckRepository checks;
    private final CustomerAuthorization authorization;
    private final PiiCipher cipher;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customers,
            KycCheckRepository checks,
            CustomerAuthorization authorization,
            PiiCipher cipher,
            Clock clock) {
        this.customers = customers;
        this.checks = checks;
        this.authorization = authorization;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * Creates a profile for the calling subject.
     *
     * <p>The subject is taken from the verified identity, never from the request body. A registration
     * form that carried its own subject field would let anyone create a profile belonging to somebody
     * else, which is the whole reason this service exists.
     *
     * @throws com.fintech.platform.common.error.ApiException 409 if the subject already has a profile
     */
    @Transactional
    public CustomerProfileView register(InternalIdentity caller, CustomerIdentity identity, String phone) {
        String subject = caller.subject();
        if (customers.findBySubject(subject).isPresent()) {
            throw CustomerErrorCodes.CUSTOMER_ALREADY_REGISTERED.exception();
        }
        Customer customer = Customer.register(UUID.randomUUID(), subject, identity, phone, cipher, clock.instant());
        customers.save(customer);
        // Unmasked: the caller is the owner by construction, having just registered as themselves.
        return toView(customer, false);
    }

    /**
     * @throws ApiException 404 if no profile exists for the subject, 410 if it has been erased
     */
    @Transactional(readOnly = true)
    public CustomerProfileView getOwnProfile(InternalIdentity caller) {
        return toView(requireLiveForSubject(caller.subject()), false);
    }

    /**
     * Reads any profile, for a support, compliance or audit caller.
     *
     * @throws ApiException 403 if the caller may not read other profiles
     */
    @Transactional(readOnly = true)
    public CustomerProfileView getProfile(InternalIdentity caller, UUID customerId) {
        Customer customer =
                customers.findById(customerId).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        authorization.requireReadAccess(caller, customer.subject());
        // Masked for anyone but the owner. A support agent asking "does this account exist" needs
        // enough to recognise the customer, not their date of birth and address, and the ability to
        // read those is a separate grant this endpoint does not confer.
        return toView(customer, !authorization.isOwner(caller, customer.subject()));
    }

    /**
     * Replaces the stored identity, re-encrypting every field.
     *
     * <p>A full replacement rather than a patch. A partial update needs a convention for "field absent
     * means unchanged", and that convention is always fine until one endpoint sends a null by accident
     * and silently blanks a customer's date of birth.
     */
    @Transactional
    public CustomerProfileView updateProfile(
            InternalIdentity caller, UUID customerId, CustomerIdentity identity, String phone) {
        Customer customer =
                customers.findById(customerId).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        authorization.requireSelfOrAdmin(caller, customer.subject());
        if (customer.isErased()) {
            throw CustomerErrorCodes.CUSTOMER_NOT_ERASED.exception();
        }
        customer.updateIdentity(identity, phone, cipher, clock.instant());
        return toView(customer, false);
    }

    /**
     * Erases the caller's profile and all of its identity-check evidence.
     *
     * <p>The cascade is the part worth reading twice. Nulling the profile while leaving a decrypted
     * document reference on a KYC check would satisfy a naive reading of "the customer deleted their
     * data" while leaving the most sensitive thing they submitted fully intact, so every check's
     * evidence is cleared in the same transaction. The checks themselves survive: a regulator still
     * needs to know a check happened and what it concluded.
     *
     * <p>Not idempotent. A second call throws {@code CUSTOMER_ALREADY_ERASED}, so a caller retrying
     * after a timeout finds out whether the first attempt landed.
     */
    /**
     * Erases the caller's own profile, found by subject rather than by id.
     *
     * <p>This is the path a customer exercises a deletion right through, and it resolves by subject
     * digest rather than by matching a subject. After an erasure the subject column is null, so a
     * retry from the same customer cannot authorise itself any more: comparing subjects returns false
     * and the owner is told "this profile belongs to another customer", which is both wrong and
     * baffling. The digest is the one field that survives erasure, so it identifies the tombstone to
     * the person it belongs to and to nobody else.
     *
     * <p>That is what retaining the digest is for. It is not read anywhere else, and it is not enough
     * to read the profile, because everything else is gone.
     *
     * @throws ApiException 404 if no profile has ever existed for this subject, 409 if it is already
     *     erased, so a retry after a timeout gets a definite answer
     */
    @Transactional
    public void eraseOwnProfile(InternalIdentity caller) {
        String digest = cipher.subjectDigest(caller.subject());
        Customer customer =
                customers.findBySubjectDigest(digest).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        if (customer.isErased()) {
            throw CustomerErrorCodes.CUSTOMER_ALREADY_ERASED.exception();
        }
        erase(customer);
    }

    /**
     * Erases a named profile, for the owner or a platform administrator.
     *
     * <p>An already-erased profile has no subject left to authorise against, so the outcome of a
     * repeated call here depends on who is asking: an administrator is told it is already erased, and
     * everyone else is told it is not theirs. The second answer is deliberate. Reporting "already
     * erased" to a caller who does not own the profile would turn a customer id into a probe for which
     * tombstones exist, and a customer confirming their own erasure is served by
     * {@link #eraseOwnProfile} instead, which can answer without disclosing anything to anyone else.
     */
    @Transactional
    public void eraseProfile(InternalIdentity caller, UUID customerId) {
        Customer customer =
                customers.findById(customerId).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        if (customer.isErased() && !isAdmin(caller)) {
            throw CustomerErrorCodes.NOT_THE_OWNER.exception();
        }
        authorization.requireSelfOrAdmin(caller, customer.subject());
        if (customer.isErased()) {
            throw CustomerErrorCodes.CUSTOMER_ALREADY_ERASED.exception();
        }
        erase(customer);
    }

    /** The one place erasure is performed, so the cascade cannot be forgotten on a new entry point. */
    private void erase(Customer customer) {
        // Cleared before the profile is nulled, while the subject is still available.
        for (KycCheck check : checks.findByCustomerIdOrderBySubmittedAtDesc(customer.id())) {
            check.eraseEvidence();
        }
        customer.erase(cipher, clock.instant());
    }

    private static boolean isAdmin(InternalIdentity caller) {
        return caller.roles().contains("PLATFORM_ADMIN");
    }

    /** @return the decrypted profile, or 410 for a tombstone */
    private Customer requireLiveForSubject(String subject) {
        // Looked up by subject first because that is the fast path, and by subject digest second
        // because erasure nulls the subject: a deleted profile is unreachable by the only field that
        // used to identify it, so without the digest fallback a customer who erased their profile
        // would be told they never had one. A 404 there is not a small inaccuracy -- it denies a
        // caller the one definite answer a deletion request is entitled to, which is the reason
        // subject_digest outlives the personal data in the first place.
        Customer customer = customers.findBySubject(subject).orElse(null);
        if (customer == null) {
            customers
                    .findBySubjectDigest(cipher.subjectDigest(subject))
                    .filter(Customer::isErased)
                    .ifPresent(erased -> {
                        throw CustomerErrorCodes.CUSTOMER_NOT_ERASED.exception();
                    });
            throw CustomerErrorCodes.CUSTOMER_NOT_FOUND.exception();
        }
        if (customer.isErased()) {
            // Reachable only if a row keeps its subject after erasure, which the aggregate does not
            // do. Present so that a future column change surfaces as the right 410 rather than as a
            // decryption failure three layers down.
            throw CustomerErrorCodes.CUSTOMER_NOT_ERASED.exception();
        }
        return customer;
    }

    private CustomerProfileView toView(Customer customer, boolean masked) {
        return new CustomerProfileView(
                customer.id(),
                customer.fullName(cipher),
                customer.dateOfBirth(cipher),
                customer.email(cipher),
                customer.phone(cipher),
                customer.address(cipher),
                customer.kycStatus(),
                customer.createdAt(),
                customer.updatedAt(),
                customer.isErased(),
                masked);
    }

    /** @return the KYC history for a customer the caller may read, newest first */
    @Transactional(readOnly = true)
    public List<CustomerProfileView.KycCheckView> kycHistory(InternalIdentity caller, UUID customerId) {
        Customer customer =
                customers.findById(customerId).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        authorization.requireReadAccess(caller, customer.subject());
        return checks.findByCustomerIdOrderBySubmittedAtDesc(customerId).stream()
                .map(this::toCheckView)
                .toList();
    }

    private CustomerProfileView.KycCheckView toCheckView(KycCheck check) {
        List<CustomerProfileView.KycCheckView.CheckResultView> results = check.results().stream()
                .map(result -> new CustomerProfileView.KycCheckView.CheckResultView(
                        result.checkName().name(), result.passed(), result.reason()))
                .toList();
        return new CustomerProfileView.KycCheckView(
                check.id(),
                check.outcome(),
                check.providerReference(),
                check.failureReasons(),
                results,
                check.submittedAt(),
                check.decidedAt());
    }
}
