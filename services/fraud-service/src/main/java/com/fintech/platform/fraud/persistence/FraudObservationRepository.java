package com.fintech.platform.fraud.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Observation storage — the engine's memory of what it has seen.
 *
 * <p>All reads are by the full primary key and are single-row lookups, which is the right shape: seven
 * rules need seven facts about one payment, and each is "has this exact pair been seen". The only
 * interesting query is {@link #distinctCustomerCountForDevice}, which is a {@code COUNT(DISTINCT
 * subject)} over the device scope — the shared-device rule, and the one place where counting rows
 * instead of distinct customers would report a customer with three payments on a shared device as three
 * other customers.
 */
public interface FraudObservationRepository extends JpaRepository<FraudObservationEntity, FraudObservationId> {

    Optional<FraudObservationEntity> findByScopeAndKindAndSubjectDigestAndObservationKey(
            String scope, String kind, String subjectDigest, String observationKey);

    /**
     * Records a sighting, or bumps the count if it has been seen.
     *
     * <p>An upsert rather than a read-then-write for the same reason {@code ProcessedEventRepository} is
     * one: two payments from the same device scored by two consumer threads must not both decide they
     * created the row, or {@code first_seen_at} becomes a race. {@code COALESCE} is needed because
     * {@code DO UPDATE SET x = EXCLUDED.x} would otherwise overwrite {@code first_seen_at} with the later
     * sighting, turning a first-seen fact into a last-seen one.
     */
    @Modifying
    @Query(value = """
            INSERT INTO fraud_observations
                (scope, kind, subject_digest, observation_key, first_seen_at, last_seen_at, hit_count)
            VALUES
                (:scope, :kind, :subjectDigest, :observationKey, :now, :now, 1)
            ON CONFLICT (scope, kind, subject_digest, observation_key) DO UPDATE SET
                last_seen_at = GREATEST(fraud_observations.last_seen_at, EXCLUDED.last_seen_at),
                hit_count = fraud_observations.hit_count + 1
            """, nativeQuery = true)
    int recordSighting(
            @Param("scope") String scope,
            @Param("kind") String kind,
            @Param("subjectDigest") String subjectDigest,
            @Param("observationKey") String observationKey,
            @Param("now") java.time.Instant now);

    /**
     * How many distinct customers, other than this one, have used this device.
     *
     * <p>Counted from the card-device scope, because a device used with two cards of the same customer
     * is one customer, not two. The exclusion is by digest, not by a name.
     */
    @Query("""
            SELECT count(DISTINCT o.subjectDigest)
              FROM FraudObservationEntity o
             WHERE o.scope = :scope
               AND o.kind = :kind
               AND o.observationKey = :observationKey
               AND o.subjectDigest <> :excludingSubjectDigest
            """)
    long distinctCustomerCountForDevice(
            @Param("scope") String scope,
            @Param("kind") String kind,
            @Param("observationKey") String observationKey,
            @Param("excludingSubjectDigest") String excludingSubjectDigest);

    /**
     * When this customer last used a network other than the one given.
     *
     * <p>Ordered by {@code first_seen_at} descending, so the top row is the most recent switch. Returns a
     * list because the caller takes the first and a {@code LIMIT 1} in JPQL is not portable; the query
     * is bounded by an index on the scope and subject.
     */
    @Query("""
            SELECT o FROM FraudObservationEntity o
             WHERE o.scope = :scope
               AND o.kind = :kind
               AND o.subjectDigest = :subjectDigest
               AND o.observationKey <> :excludingObservationKey
             ORDER BY o.firstSeenAt DESC
            """)
    java.util.List<FraudObservationEntity> findOtherNetworks(
            @Param("scope") String scope,
            @Param("kind") String kind,
            @Param("subjectDigest") String subjectDigest,
            @Param("excludingObservationKey") String excludingObservationKey,
            org.springframework.data.domain.Pageable pageable);

    /** Rows past their retention, for the pruning job. */
    @Query("""
            SELECT o FROM FraudObservationEntity o
             WHERE o.scope = :scope
               AND o.lastSeenAt < :cutoff
            """)
    java.util.List<FraudObservationEntity> findExpired(
            @Param("scope") String scope,
            @Param("cutoff") java.time.Instant cutoff,
            org.springframework.data.domain.Pageable pageable);
}
