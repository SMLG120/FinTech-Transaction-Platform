package com.fintech.platform.dispute.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidenceEntity, UUID> {

    /** One case file, in the order it was spoken. */
    List<DisputeEvidenceEntity> findByDisputeIdOrderBySubmittedAtAsc(UUID disputeId);

    /** How many statements a case file holds, without loading it. */
    long countByDisputeId(UUID disputeId);
}
