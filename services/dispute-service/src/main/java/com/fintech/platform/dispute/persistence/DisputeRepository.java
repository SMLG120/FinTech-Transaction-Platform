package com.fintech.platform.dispute.persistence;

import com.fintech.platform.dispute.domain.DisputeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
