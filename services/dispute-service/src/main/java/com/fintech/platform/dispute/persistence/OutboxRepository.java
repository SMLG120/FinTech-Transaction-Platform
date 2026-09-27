package com.fintech.platform.dispute.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Outbox storage, the relay's work queue. The same queries and the same partial-index reasoning as the others. */
public interface OutboxRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /** Unpublished events, oldest first, so a backlog drains in the order it was created. */
    @Query("""
            SELECT e FROM OutboxEventEntity e
             WHERE e.publishedAt IS NULL
             ORDER BY e.createdAt, e.id
            """)
    List<OutboxEventEntity> findPending(Pageable pageable);

    /** How many rows are still waiting, for the health indicator and the tests. */
    @Query("SELECT count(e) FROM OutboxEventEntity e WHERE e.publishedAt IS NULL")
    long countPending();

    /** Retried more than a threshold, i.e. the ones worth alerting on. */
    @Query("""
            SELECT e FROM OutboxEventEntity e
             WHERE e.publishedAt IS NULL AND e.attempts >= :attempts
             ORDER BY e.createdAt
            """)
    List<OutboxEventEntity> findStuck(@Param("attempts") int attempts, Pageable pageable);
}
