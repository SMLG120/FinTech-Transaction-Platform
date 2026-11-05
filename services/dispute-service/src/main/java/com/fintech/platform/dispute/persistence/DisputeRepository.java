package com.fintech.platform.dispute.persistence;

import com.fintech.platform.dispute.domain.DisputeStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisputeRepository extends JpaRepository<DisputeEntity, UUID> {

    /** The open case on a payment, if one is being worked. */
    Optional<DisputeEntity> findByTransactionIdAndStatus(UUID transactionId, DisputeStatus status);

    /** Every case on a payment, newest first — history accumulates, including resolved cases. */
    List<DisputeEntity> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    /** One customer's cases, newest first. */
    Page<DisputeEntity> findByOpenedBySubjectOrderByCreatedAtDesc(String openedBySubject, Pageable pageable);

    /** The open queue, oldest first: the cases waiting longest are worked first. */
    Page<DisputeEntity> findByStatusOrderByCreatedAtAsc(DisputeStatus status, Pageable pageable);

    /** Every case, newest first. */
    Page<DisputeEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Loads a case under a row lock, for the operations that change its state.
     *
     * <p>Resolving is check-then-act ("is it still OPEN?" then "resolve it"), and two agents clicking
     * REFUND and REJECT at the same moment would otherwise both see OPEN and both publish an outcome.
     * The lock makes the second caller wait, re-read RESOLVED and get DISPUTE_NOT_OPEN.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DisputeEntity d WHERE d.id = :id")
    Optional<DisputeEntity> findByIdForUpdate(@Param("id") UUID id);
}
