package com.fintech.platform.customer.service;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.customer.domain.Customer;
import com.fintech.platform.customer.domain.CustomerIdentity;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import com.fintech.platform.customer.kyc.KycProvider;
import com.fintech.platform.customer.persistence.CustomerRepository;
import com.fintech.platform.customer.persistence.KycCheckRepository;
import com.fintech.platform.customer.pii.PiiCipher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates an identity check: authorise, persist the submission, call the provider, persist the
 * decision.
 *
 * <p>Not {@code @Transactional}. The provider call belongs between two transactions rather than inside
 * one, and the two halves live in {@link KycWorkflow}. Annotating this class would quietly put the
 * external call back inside a transaction, which is the thing this split exists to prevent.
 *
 * <p>What the customer claimed is taken from the stored profile, and only the identity document and
 * nationality come from the request. A submission that let the caller restate their own name and date
 * of birth would be checking the claim against itself, which is the one thing an identity check must
 * not do. Nationality is asked for here rather than kept on the profile because it is evidence, not
 * profile data, and holding it permanently for a field no current check reads is not a trade worth
 * making.
 */
@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final CustomerRepository customers;
    private final KycCheckRepository checks;
    private final KycWorkflow workflow;
    private final CustomerAuthorization authorization;
    private final KycProvider provider;
    private final PiiCipher cipher;

    public KycService(
            CustomerRepository customers,
            KycCheckRepository checks,
            KycWorkflow workflow,
            CustomerAuthorization authorization,
            KycProvider provider,
            PiiCipher cipher) {
        this.customers = customers;
        this.checks = checks;
        this.workflow = workflow;
        this.authorization = authorization;
        this.provider = provider;
        this.cipher = cipher;
    }

    /**
     * Submits an identity document for assessment.
     *
     * @param document the document as printed; encrypted before it is stored
     * @param nationality the applicant's nationality, evidence rather than profile data
     * @return the check id, the resulting status and any failure reasons
     * @throws ApiException 404 if no such customer, 403 if not the owner, 409 if a check is already
     *     open or the state machine forbids the move, 503 if the provider cannot be reached
     */
    public KycWorkflow.KycDecision submit(
            InternalIdentity caller,
            UUID customerId,
            CustomerIdentity.IdentityDocument document,
            CustomerIdentity.Nationality nationality) {
        Customer customer =
                customers.findById(customerId).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        authorization.requireSelfOrAdmin(caller, customer.subject());
        if (customer.isErased()) {
            throw CustomerErrorCodes.CUSTOMER_NOT_ERASED.exception();
        }
        if (checks.findCurrentByCustomerId(customerId).isPresent()) {
            // The partial unique index would reject this anyway; checking first turns a constraint
            // violation into a 409 the caller can act on, and names the reason.
            throw CustomerErrorCodes.KYC_INVALID_TRANSITION.exception(
                    "an identity check is already in progress for this customer");
        }

        // Commit the submission first. If the provider then fails, what is left behind is a visible
        // in-flight check and a customer in PENDING_REVIEW, which is the truth; the alternative is
        // losing the attempt entirely.
        UUID checkId = workflow.open(
                customerId, document.reference(), document.printedName().value());

        KycProvider.Assessment assessment;
        try {
            assessment = provider.assess(toIdentity(customer, document, nationality));
        } catch (RuntimeException e) {
            // Deliberately a 503 and a log line, not a rethrow. The submission is already durable, so
            // the customer is not left believing they never tried, and the reason a provider is
            // unavailable is the provider's business rather than the caller's.
            log.warn("identity check {} could not reach the provider; leaving it open", checkId, e);
            throw CustomerErrorCodes.KYC_PROVIDER_UNAVAILABLE.exception();
        }
        return workflow.recordDecision(checkId, assessment);
    }

    /**
     * Concludes a check the provider deferred, on a human's authority.
     *
     * <p>The provider could not decide; a compliance officer can. Same check, not a new one, so the
     * original submission and the human's decision end up in the same audit trail.
     */
    public KycWorkflow.KycDecision decideManually(
            InternalIdentity caller, UUID checkId, KycProvider.Assessment assessment) {
        authorization.requireComplianceRole(caller);
        return workflow.recordDecision(checkId, assessment);
    }

    /**
     * Rebuilds the full claimed identity from the stored profile plus the submitted document.
     *
     * <p>Decryption happens here and nowhere else, so the plaintext exists for the length of one
     * provider call. A failure is reported as {@code PII_NOT_DECRYPTABLE} rather than allowed to
     * surface as a decryption exception, because a caller cannot act on the stack trace and the
     * distinction would otherwise leak whether the stored value is malformed.
     */
    private CustomerIdentity toIdentity(
            Customer customer, CustomerIdentity.IdentityDocument document, CustomerIdentity.Nationality nationality) {
        String subject = customer.subject();
        try {
            return new CustomerIdentity(
                    new CustomerIdentity.ClaimedName(customer.fullName(cipher)),
                    new CustomerIdentity.DateOfBirth(customer.dateOfBirth(cipher), nationality),
                    new CustomerIdentity.Email(customer.email(cipher)),
                    customer.address(cipher),
                    document);
        } catch (RuntimeException e) {
            log.error("stored profile for customer {} could not be decrypted", customer.id(), e);
            throw CustomerErrorCodes.PII_NOT_DECRYPTABLE.exception();
        }
    }
}
