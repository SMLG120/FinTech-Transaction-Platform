package com.fintech.platform.fraud.persistence;

import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskBand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Decision storage, and the queries the dashboard and the analyst screens are built from.
 *
 * <p>Every filter here is on a scalar column rather than on the {@code reasons} or {@code facts} text.
 * That is deliberate and it is why those documents are stored as text: a decision row is read whole or
 * filtered by indexed columns, and nothing needs to ask a question about the inside of a reason string.
 * When a query does need the documents — the current payment's full explanation — it fetches the one row.
 */
public interface RiskDecisionRepository extends JpaRepository<RiskDecisionEntity, UUID> {

    /** The decision for one payment, or empty if it has not been scored. */
    Optional<RiskDecisionEntity> findByTransactionId(UUID transactionId);

    /**
     * Recent decisions, filtered.
     *
     * <p>Filters are all optional and are applied by Spring Data rather than by hand, so an unset filter is
     * absent from the {@code WHERE} clause instead of compared against null — the difference between
     * "no filter" and "filtered to nothing", which is the mistake that makes a list endpoint return an
     * empty page for a request that named no filters at all.
     */
    @Query("""
            SELECT d FROM RiskDecisionEntity d
             WHERE (:band IS NULL OR d.band = :band)
               AND (:decision IS NULL OR d.decision = :decision)
               AND (:ownerSubjectDigest IS NULL OR d.ownerSubjectDigest = :ownerSubjectDigest)
               AND (:merchantReference IS NULL OR d.merchantReference = :merchantReference)
               AND (:from IS NULL OR d.occurredAt >= :from)
               AND (:to IS NULL OR d.occurredAt <= :to)
             ORDER BY d.occurredAt DESC, d.transactionId
            """)
    Page<RiskDecisionEntity> search(
            @Param("band") RiskBand band,
            @Param("decision") FraudDecision decision,
            @Param("ownerSubjectDigest") String ownerSubjectDigest,
            @Param("merchantReference") String merchantReference,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** How many decisions are in each band, for the dashboard's distribution. */
    @Query("SELECT d.band, count(d) FROM RiskDecisionEntity d WHERE d.occurredAt >= :since GROUP BY d.band")
    List<Object[]> countByBandSince(@Param("since") Instant since);

    /** How many decisions took each outcome, for the dashboard. */
    @Query("SELECT d.decision, count(d) FROM RiskDecisionEntity d WHERE d.occurredAt >= :since GROUP BY d.decision")
    List<Object[]> countByDecisionSince(@Param("since") Instant since);

    /**
     * The merchants with the most adverse decisions.
     *
     * <p>Grouped on {@code merchantReference} and only where one exists, because a null group would
     * collect every consumer payment into a single "merchant" that is not one. The top rows are what a
     * fraud team looks at first: a merchant that started attracting declines is either being targeted or
     * is the target.
     */
    @Query("""
            SELECT d.merchantReference, count(d) AS total, avg(d.score)
              FROM RiskDecisionEntity d
             WHERE d.merchantReference IS NOT NULL
               AND d.occurredAt >= :since
             GROUP BY d.merchantReference
             ORDER BY count(d) DESC
            """)
    List<Object[]> mostActiveMerchants(@Param("since") Instant since, Pageable pageable);

    /** The customers with the highest average risk, for the dashboard's risky-customers panel. */
    @Query("""
            SELECT d.ownerSubjectDigest, count(d) AS total, avg(d.score)
              FROM RiskDecisionEntity d
             WHERE d.occurredAt >= :since
             GROUP BY d.ownerSubjectDigest
             ORDER BY avg(d.score) DESC, count(d) DESC
            """)
    List<Object[]> riskiestCustomers(@Param("since") Instant since, Pageable pageable);

    /** Decisions that were declined in the window, most recent first. */
    @Query("""
            SELECT d FROM RiskDecisionEntity d
             WHERE d.decision = :decision AND d.occurredAt >= :since
             ORDER BY d.occurredAt DESC
            """)
    List<RiskDecisionEntity> recentWithDecision(
            @Param("decision") FraudDecision decision, @Param("since") Instant since, Pageable pageable);

    /** Total decisions in the window, for the rates the dashboard shows. */
    @Query("SELECT count(d) FROM RiskDecisionEntity d WHERE d.occurredAt >= :since")
    long countSince(@Param("since") Instant since);
}
