package com.fintech.platform.fraud.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The append-only alert timeline.
 *
 * <p>Read newest-first, because the question being asked is almost always "what happened to this alert",
 * and the answer is the last entry.
 */
public interface FraudAlertEventRepository extends JpaRepository<FraudAlertEventEntity, UUID> {

    List<FraudAlertEventEntity> findByAlertIdOrderByOccurredAtDesc(UUID alertId);

    List<FraudAlertEventEntity> findByAlertIdOrderByOccurredAtAsc(UUID alertId);
}
