package com.fintech.platform.fraud.persistence;

import com.fintech.platform.fraud.domain.RiskBand;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The analyst's queue.
 *
 * <p><b>Claiming is a pessimistic lock, then a domain call.</b> {@link #findByIdForUpdate} takes a row
 * lock for the rest of the transaction, and {@code FraudAlertEntity.claim} is what decides whether it
 * succeeds. The alternative — a conditional {@code UPDATE ... WHERE state = 'OPEN'} — is one round trip
 * instead of two, and puts the state machine in a JPQL string where it cannot be unit tested and where
 * its four states have to be spelled {@code OPEN} in four places. Two statements inside one transaction
 * is not a performance problem at the rate humans claim alerts.
 *
 * <p>The queue is ordered by score, descending, then by age. Score first because it is a priority list
 * rather than a queue: a CRITICAL opened five minutes ago is more urgent than a HIGH opened yesterday, and
 * ordering by age would bury it. Age as the tiebreak keeps the order stable while an analyst scrolls.
 */
public interface FraudAlertRepository extends JpaRepository<FraudAlertEntity, UUID> {

    /** The states that are still somebody's responsibility. Bound as a parameter, never spelled inline. */
    Set<FraudAlertEntity.AlertState> OPEN_STATES =
            Set.of(FraudAlertEntity.AlertState.OPEN, FraudAlertEntity.AlertState.CLAIMED);

    Optional<FraudAlertEntity> findByTransactionId(UUID transactionId);

    /**
     * The alert, locked for update.
     *
     * <p>{@code PESSIMISTIC_WRITE} rather than {@code OPTIMISTIC}, because the conflict here is routine
     * and the loser has to be told. With optimistic locking the second transaction would fail at commit
     * time, after it had already told the analyst it succeeded.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM FraudAlertEntity a WHERE a.id = :alertId")
    Optional<FraudAlertEntity> findByIdForUpdate(@Param("alertId") UUID alertId);

    /**
     * The unworked queue, highest risk first.
     *
     * <p>Open and claimed are both returned. A claimed-but-unanswered alert is still someone's
     * responsibility, and a queue showing only unclaimed work would let it be forgotten while sitting in
     * a name.
     */
    @Query("""
            SELECT a FROM FraudAlertEntity a
             WHERE a.state IN :states
               AND (:band IS NULL OR a.band = :band)
               AND (:ownerSubjectDigest IS NULL OR a.ownerSubjectDigest = :ownerSubjectDigest)
               AND (:claimedBy IS NULL OR a.claimedBy = :claimedBy)
             ORDER BY a.score DESC, a.createdAt
            """)
    Page<FraudAlertEntity> findQueue(
            @Param("states") Set<FraudAlertEntity.AlertState> states,
            @Param("band") RiskBand band,
            @Param("ownerSubjectDigest") String ownerSubjectDigest,
            @Param("claimedBy") String claimedBy,
            Pageable pageable);

    /** Every alert for one payment, newest first, for the decision detail view. */
    List<FraudAlertEntity> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    /** Everything, newest first, for the history view. */
    @Query("SELECT a FROM FraudAlertEntity a ORDER BY a.createdAt DESC")
    Page<FraudAlertEntity> findRecent(Pageable pageable);

    @Query("SELECT a.state, count(a) FROM FraudAlertEntity a GROUP BY a.state")
    List<Object[]> countByState();

    /** Open alerts past their SLA, for the dashboard's breach panel. */
    @Query("""
            SELECT a FROM FraudAlertEntity a
             WHERE a.state IN :states
               AND a.createdAt < :cutoff
             ORDER BY a.score DESC, a.createdAt
            """)
    Page<FraudAlertEntity> findBreaching(
            @Param("states") Set<FraudAlertEntity.AlertState> states,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /** How many alerts are in a state, which is how the false-positive rate is computed. */
    @Query("SELECT count(a) FROM FraudAlertEntity a WHERE a.state = :state")
    long countByState(@Param("state") FraudAlertEntity.AlertState state);
}
