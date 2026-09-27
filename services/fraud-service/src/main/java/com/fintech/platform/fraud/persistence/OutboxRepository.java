package com.fintech.platform.fraud.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Outbox storage, the relay's work queue.
 *
 * <p>The same queries as transaction-service's, and the same reason the index on {@code published_at} is
 * partial: at-least-once delivery means the steady state is a handful of unpublished rows, so the queue
 * query has to stay small as the table grows without bound.
 */
public interface OutboxRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /** Unpublished events, oldest first, so a backlog drains in the order it was created. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT e FROM OutboxEventEntity e
             WHERE e.publishedAt IS NULL
             ORDER BY e.createdAt, e.id
            """)
    java.util.List<OutboxEventEntity> findPending(org.springframework.data.domain.Pageable pageable);

    /** Everything one decision announced, published or not. */
    java.util.List<OutboxEventEntity> findByAggregateIdOrderByAggregateVersionAsc(UUID aggregateId);

    /** How many rows are still waiting, for the health indicator and the tests. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT count(e) FROM OutboxEventEntity e WHERE e.publishedAt IS NULL")
    long countPending();

    /** Retried more than a threshold, i.e. the ones worth alerting on. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT e FROM OutboxEventEntity e WHERE e.publishedAt IS NULL AND e.attempts >= :attempts ORDER BY e.createdAt")
    java.util.List<OutboxEventEntity> findStuck(
            @org.springframework.data.repository.query.Param("attempts") int attempts,
            org.springframework.data.domain.Pageable pageable);
}
