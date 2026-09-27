package com.fintech.platform.customer.persistence;

import com.fintech.platform.customer.domain.KycCheck;
import com.fintech.platform.customer.kyc.KycStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Identity-check lookups.
 *
 * <p>Every read that serves a request is scoped by customer id as well as check id, and that is the
 * point of this class rather than an incidental detail. The gateway has already authenticated the
 * caller by the time a request reaches this service, but authentication is not authorisation: a
 * caller who is a valid customer can still name a different customer's id in the path. {@link
 * #findByIdAndCustomerId} makes the ownership check part of the query, so the unsafe way to read a
 * check is a method that does not exist.
 */
public interface KycCheckRepository extends JpaRepository<KycCheck, UUID> {

    /**
     * Ownership-scoped fetch.
     *
     * @return the check only if it belongs to {@code customerId}, so a mismatch reads as "not found"
     *     rather than as "forbidden"; see ADR-0005 on not confirming that a record exists
     */
    Optional<KycCheck> findByIdAndCustomerId(UUID id, UUID customerId);

    /** History for a customer, newest first. */
    List<KycCheck> findByCustomerIdOrderBySubmittedAtDesc(UUID customerId);

    /**
     * The check still awaiting a decision, if any.
     *
     * @implNote Backed by {@code kyc_checks_current_per_customer}, which permits at most one such row
     *     per customer. The database is what actually prevents a second concurrent submission; this
     *     exists so the service can report the conflict as a conflict.
     */
    @Query("select k from KycCheck k where k.customerId = :customerId and k.decidedAt is null")
    Optional<KycCheck> findCurrentByCustomerId(@Param("customerId") UUID customerId);

    /**
     * Dispute and support lookup by the provider's handle, always within one customer.
     *
     * <p>The customer id is part of the key rather than a convenience. The handle is unique per
     * customer, not platform-wide -- see the migration for why a global constraint would be a
     * denial-of-service -- so a lookup that omitted the customer could match more than one row and
     * fail at the point of use, on a compliance path, with a database exception.
     */
    Optional<KycCheck> findByCustomerIdAndProviderReference(UUID customerId, String providerReference);

    long countByCustomerIdAndOutcome(UUID customerId, KycStatus outcome);
}
