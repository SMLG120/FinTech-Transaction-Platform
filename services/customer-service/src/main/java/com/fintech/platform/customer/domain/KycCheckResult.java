package com.fintech.platform.customer.domain;

import com.fintech.platform.customer.kyc.Check;
import com.fintech.platform.customer.kyc.KycProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * One check that a provider performed, and what it found.
 *
 * <p>A row rather than a JSON array on {@link KycCheck}, which is the only reason two questions this
 * data exists to answer can be asked at all: which check rejects the most customers, and how many
 * approvals rested on a check whose rule has since changed. Both are plain SQL over rows.
 *
 * <p>Never contains document data. A failure reason is written for an applicant to read and for an
 * auditor to rely on, so it must be safe to return over the API and safe to log; the document
 * evidence itself stays on {@link KycCheck}, encrypted.
 */
@Entity
@Table(name = "kyc_check_results")
public class KycCheckResult {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kyc_check_id", nullable = false)
    private KycCheck kycCheck;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_name", nullable = false, length = 64)
    private Check checkName;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    /**
     * Why the check failed. Null when it passed.
     *
     * <p>The invariant is enforced three times over, deliberately: here, in {@link
     * KycProvider.CheckResult}, and by {@code kyc_check_results_failure_has_reason}. A failed check with
     * no reason is not actionable for the applicant and not defensible for an auditor, and the column an
     * auditor reads is not the one they can trust to be populated.
     */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** JPA only. */
    protected KycCheckResult() {}

    /**
     * Builds a result row. Does not attach it to the parent; {@link KycCheck#decide} owns the
     * collection so that ownership of the bidirectional link lives in one place.
     */
    static KycCheckResult of(UUID id, KycCheck kycCheck, KycProvider.CheckResult result) {
        Objects.requireNonNull(kycCheck, "kycCheck must not be null");
        KycCheckResult row = new KycCheckResult();
        row.id = Objects.requireNonNull(id, "id must not be null");
        row.kycCheck = kycCheck;
        row.checkName = Objects.requireNonNull(result.check(), "check must not be null");
        row.passed = result.passed();
        row.reason =
                result.passed() ? null : Objects.requireNonNull(result.reason(), "a failed check must carry a reason");
        return row;
    }

    public Check checkName() {
        return checkName;
    }

    public boolean passed() {
        return passed;
    }

    /** @return why it failed, or null when it passed */
    public String reason() {
        return reason;
    }

    public UUID id() {
        return id;
    }

    @Override
    public String toString() {
        return "KycCheckResult[" + checkName + ", passed=" + passed + "]";
    }
}
