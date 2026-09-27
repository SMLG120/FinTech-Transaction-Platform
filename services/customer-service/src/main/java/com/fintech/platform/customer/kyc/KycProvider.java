package com.fintech.platform.customer.kyc;

import com.fintech.platform.customer.domain.CustomerIdentity;
import java.util.List;

/**
 * An external identity-check provider, as the platform needs one.
 *
 * <p>An interface rather than a client for a named vendor, because the choice of provider is a
 * procurement decision that will outlive this code and because the interesting part of this service is
 * the state machine around the call, not the call. Anything that can turn an identity claim into a
 * decision is a provider; the synthetic implementation in this package is a complete one, so the
 * platform can be exercised end to end without an external dependency or a network call.
 *
 * <p>Implementations are called on a request thread and must therefore be non-blocking or bounded.
 * The distinction that matters for the caller is {@link Decision#REVIEW}: a provider that cannot
 * decide immediately says so, and the customer stays in review rather than being failed.
 */
public interface KycProvider {

    /** What the provider concluded, or that it could not conclude yet. */
    enum Decision {
        APPROVE,
        REJECT,
        /** Not a decision. The submission is genuinely undecided and stays in review. */
        REVIEW
    }

    /**
     * One thing the provider checked, and what it found.
     *
     * @param check which check this was
     * @param passed whether it passed
     * @param reason a caller-safe explanation; never contains the document data itself
     */
    record CheckResult(Check check, boolean passed, String reason) {

        public CheckResult {
            if (check == null) {
                throw new IllegalArgumentException("check must not be null");
            }
            if (!passed && (reason == null || reason.isBlank())) {
                // A refusal with no reason is not actionable for the applicant and not defensible for
                // an auditor, so it is rejected at the boundary rather than stored and returned.
                throw new IllegalArgumentException("a failed check must carry a reason");
            }
        }
    }

    /**
     * @param decision the overall outcome
     * @param checks every check performed, in the order performed
     * @param reference the provider's own identifier for this assessment, for support and dispute
     * @param failureReasons the reasons the decision was a rejection; empty unless {@code REJECT}
     */
    record Assessment(Decision decision, List<CheckResult> checks, String reference, List<String> failureReasons) {

        public Assessment {
            checks = checks == null ? List.of() : List.copyOf(checks);
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
            if (decision == null) {
                throw new IllegalArgumentException("decision must not be null");
            }
            if (decision == Decision.REJECT && failureReasons.isEmpty()) {
                throw new IllegalArgumentException("a rejection must carry at least one reason");
            }
        }

        public static Assessment review(String reference, List<CheckResult> checks) {
            return new Assessment(Decision.REVIEW, checks, reference, List.of());
        }
    }

    /** @return the assessment; never null, and never throws for a merely unsuccessful check */
    Assessment assess(CustomerIdentity identity);

    /** @return whether this provider is able to accept work right now, for readiness reporting */
    boolean available();
}
