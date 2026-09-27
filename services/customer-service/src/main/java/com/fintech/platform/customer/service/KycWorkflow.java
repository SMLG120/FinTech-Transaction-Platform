package com.fintech.platform.customer.service;

import com.fintech.platform.customer.domain.Customer;
import com.fintech.platform.customer.domain.KycCheck;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import com.fintech.platform.customer.kyc.KycProvider;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.persistence.CustomerRepository;
import com.fintech.platform.customer.persistence.KycCheckRepository;
import com.fintech.platform.customer.pii.PiiCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional halves of an identity check.
 *
 * <p>Separate from {@link KycService} so the provider call can happen outside a transaction. An
 * external call held open inside one pins a connection for the length of a third party's outage, and
 * the usual outcome is a pool exhausted by a handful of slow requests. Splitting the work means the
 * submission is committed before the provider is called and the decision is committed after, with no
 * transaction open in between.
 *
 * <p>Each half is public and called through the proxy so that Spring's propagation genuinely applies.
 * Private helpers on a transactional method would look equivalent and provide neither the isolation
 * nor the meaning.
 */
@Component
public class KycWorkflow {

    private final CustomerRepository customers;
    private final KycCheckRepository checks;
    private final PiiCipher cipher;
    private final Clock clock;

    public KycWorkflow(CustomerRepository customers, KycCheckRepository checks, PiiCipher cipher, Clock clock) {
        this.customers = customers;
        this.checks = checks;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * Records the submission and moves the customer into review, committing both.
     *
     * <p>Committed before the provider is called, so that a provider outage leaves a visible in-flight
     * check rather than a customer who appears never to have tried.
     *
     * @return the id of the check now awaiting a decision
     * @throws ApiException 404 if no such customer
     */
    @Transactional
    public UUID open(UUID customerId, String documentReference, String printedName) {
        // Loaded here rather than passed in. The caller is not in a transaction, so an entity it loaded
        // is detached, and moveKycTo on it is discarded at the end of this method. The failure is worth
        // spelling out because it is not the quiet one: the move to PENDING_REVIEW is lost, so the
        // customer's status stays NOT_STARTED, and the later move to the outcome is then refused by
        // KycStateMachine as NOT_STARTED -> APPROVED. The status update is lost silently; it is the
        // state machine that turns that into a named error one request later.
        Customer customer =
                customers.findById(customerId).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        KycCheck check = KycCheck.open(UUID.randomUUID(), customer.id(), clock.instant());
        check.encryptEvidence(customer.subject(), documentReference, printedName, cipher);
        checks.save(check);
        customer.moveKycTo(KycStatus.PENDING_REVIEW, clock.instant());
        return check.id();
    }

    /**
     * Records a decision and moves the customer to match, committing both.
     *
     * <p>Both writes in one transaction because the inconsistent states are worse than a failed
     * request: a check decided without the customer moving, or a customer approved with no decision
     * behind them, would each be a support ticket and a compliance finding.
     *
     * <p>Safe to call again for the same check while the provider is deferring, since
     * {@link KycCheck#decide} only refuses a check it actually concluded. That is what lets a
     * compliance officer finish a check the provider left open.
     *
     * @return what the caller needs to answer the request: which check, the resulting status, and the
     *     reasons if it was a refusal
     */
    @Transactional
    public KycDecision recordDecision(UUID checkId, KycProvider.Assessment assessment) {
        KycCheck check = checks.findById(checkId).orElseThrow(CustomerErrorCodes.KYC_CHECK_NOT_FOUND::exception);
        Instant decidedAt = clock.instant();
        check.decide(assessment, decidedAt);

        Customer customer =
                customers.findById(check.customerId()).orElseThrow(CustomerErrorCodes.CUSTOMER_NOT_FOUND::exception);
        customer.moveKycTo(check.outcome(), decidedAt);
        return new KycDecision(check.id(), check.outcome(), check.failureReasons());
    }

    /**
     * The result of an identity check.
     *
     * <p>Carries the check id and the reasons alongside the status, so the caller can answer the
     * request from one call instead of immediately re-reading the history it just wrote.
     *
     * @param checkId which check
     * @param status the customer's resulting status
     * @param failureReasons applicant-facing reasons, empty unless rejected
     */
    public record KycDecision(UUID checkId, KycStatus status, java.util.List<String> failureReasons) {

        public KycDecision {
            failureReasons = failureReasons == null ? java.util.List.of() : java.util.List.copyOf(failureReasons);
        }
    }
}
