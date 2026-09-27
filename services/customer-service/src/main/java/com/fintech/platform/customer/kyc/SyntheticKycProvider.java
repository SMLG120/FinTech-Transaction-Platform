package com.fintech.platform.customer.kyc;

import com.fintech.platform.customer.domain.CustomerIdentity;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * An identity-check provider that decides from rules instead of a network call.
 *
 * <p>This is a complete implementation of {@link KycProvider}, not a stub, so the platform's KYC
 * behaviour can be exercised end to end with no external dependency, no credentials and no network.
 * Every decision is a pure function of the submitted identity and the injected {@link Clock}, so the
 * same submission always produces the same assessment and a test can pin it.
 *
 * <p>The rules are deliberately simple and deliberately visible. What matters is that each of the
 * five {@link Check}s is independently decidable, so a rejection always names which check failed
 * rather than collapsing to a single opaque "declined".
 *
 * <p>The clock is injected rather than read from the system. A minimum-age check that calls
 * {@code LocalDate.now()} cannot be tested at a boundary, and the boundary is the interesting part.
 */
@Component
public class SyntheticKycProvider implements KycProvider {

    /** The platform does not open accounts to anyone under this age. */
    public static final int MINIMUM_AGE_YEARS = 18;

    /** An approval older than this has to be re-evidenced rather than relied on indefinitely. */
    public static final int APPROVAL_VALIDITY_MONTHS = 24;

    private static final List<String> SUPPORTED_COUNTRIES = List.of("GB", "DE", "FR");

    /** Set of synthetic document references known to have been reported lost or stolen. */
    private static final List<String> REPORTED_LOST_REFERENCES = List.of("SYNTH-LOST-0001", "SYNTH-LOST-0002");

    private final Clock clock;

    public SyntheticKycProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Assessment assess(CustomerIdentity identity) {
        // The profile-only path carries an explicit placeholder document. Assessing it would produce a
        // decision that looks real and was reached by checking a document nobody submitted, so this
        // refuses rather than inventing an outcome.
        if (identity.document().isUnsubmitted()) {
            throw new IllegalArgumentException(
                    "cannot assess an identity with no document submitted; the placeholder is for the profile-only path");
        }

        List<CheckResult> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        run(
                checks,
                failures,
                Check.NAME_MATCHES_DOCUMENT,
                identity.name().matches(identity.document().printedName()),
                "the name on the document does not match the name claimed");
        run(
                checks,
                failures,
                Check.AGE_ELIGIBLE,
                isAdult(identity),
                "applicant is under " + MINIMUM_AGE_YEARS + " years old");
        run(
                checks,
                failures,
                Check.DOCUMENT_CURRENT,
                !identity.document().isExpiredOn(LocalDate.now(clock)),
                "identity document is expired");
        run(
                checks,
                failures,
                Check.JURISDICTION_SUPPORTED,
                isJurisdictionSupported(identity),
                "country is not served by the platform");
        run(
                checks,
                failures,
                Check.DOCUMENT_NOT_REPORTED_LOST,
                isDocumentNotReportedLost(identity),
                "document has been reported lost or stolen");

        String reference = identity.document().reference();
        if (!failures.isEmpty()) {
            return new Assessment(Decision.REJECT, checks, reference, failures);
        }
        return new Assessment(Decision.APPROVE, checks, reference, List.of());
    }

    @Override
    public boolean available() {
        return true;
    }

    private boolean isAdult(CustomerIdentity identity) {
        return identity.dateOfBirth().ageYearsOn(LocalDate.now(clock)) >= MINIMUM_AGE_YEARS;
    }

    private boolean isJurisdictionSupported(CustomerIdentity identity) {
        return SUPPORTED_COUNTRIES.contains(identity.address().country());
    }

    /**
     * Rejects documents on the provider's lost-or-stolen list.
     *
     * <p>Keyed on the reference printed on the document, which is how a real bureau matches: the
     * applicant's details are not part of the question, only the document.
     */
    private boolean isDocumentNotReportedLost(CustomerIdentity identity) {
        return !REPORTED_LOST_REFERENCES.contains(identity.document().reference());
    }

    private static void run(
            List<CheckResult> checks, List<String> failures, Check check, boolean passed, String failureReason) {
        checks.add(new CheckResult(check, passed, passed ? null : failureReason));
        if (!passed) {
            failures.add(failureReason);
        }
    }
}
