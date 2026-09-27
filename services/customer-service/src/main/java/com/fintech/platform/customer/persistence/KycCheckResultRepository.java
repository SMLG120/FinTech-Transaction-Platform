package com.fintech.platform.customer.persistence;

import com.fintech.platform.customer.domain.KycCheckResult;
import com.fintech.platform.customer.kyc.Check;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Per-check result lookups, including the reporting questions the {@code kyc_check_results_by_check_idx}
 * index exists to answer.
 */
public interface KycCheckResultRepository extends JpaRepository<KycCheckResult, UUID> {

    List<KycCheckResult> findByKycCheckIdInOrderByCheckNameAsc(List<UUID> kycCheckIds);

    /**
     * How often each check has been run and how often it rejected.
     *
     * <p>Exists because "which check is causing the most rejections" is a question a compliance
     * officer asks during onboarding and is unanswerable against a JSON blob. It is a report over the
     * whole table, so it belongs behind an authenticated reporting endpoint rather than on a request
     * path.
     */
    @Query("""
            select r.checkName as checkName,
                   count(r) as performed,
                   sum(case when r.passed then 0 else 1 end) as failed
            from KycCheckResult r
            group by r.checkName
            order by r.checkName
            """)
    List<CheckFailureRate> failureRatesByCheck();

    /** @param checkName which check
     * @param performed how many times it was run
     * @param failed how many times it rejected the applicant */
    record CheckFailureRate(Check checkName, long performed, long failed) {}
}
