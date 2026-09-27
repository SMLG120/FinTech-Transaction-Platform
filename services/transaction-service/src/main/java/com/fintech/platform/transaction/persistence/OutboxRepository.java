package com.fintech.platform.transaction.persistence;

import com.fintech.platform.transaction.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Outbox storage.
 *
 * <p>{@link #findPending} is the relay's work queue and the only query that matters on the hot path.
 * At-least-once delivery means the steady state is a handful of unpublished rows, so the partial index
 * on {@code published_at IS NULL} turns a table that grows forever into a scan that stays small — which
 * is the reason the index is partial rather than a plain one.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Unpublished events, oldest first.
     *
     * <p>Oldest first so that a backlog drains in the order it was created. A relay that published newest
     * first would deliver a {@code transaction-settled} before the {@code transaction-authorized} that
     * caused it, which is the ordering problem the event key exists to prevent.
     */
    @Query("""
            SELECT e FROM OutboxEvent e
             WHERE e.publishedAt IS NULL
             ORDER BY e.createdAt, e.id
            """)
    List<OutboxEvent> findPending(Pageable pageable);

    /** Unpublished events for one aggregate, for diagnostics when an event seems to be missing. */
    List<OutboxEvent> findByAggregateIdAndPublishedAtIsNullOrderByCreatedAtAsc(UUID aggregateId);

    /** Everything a payment ever emitted, published or not. */
    List<OutboxEvent> findByAggregateIdOrderByAggregateVersionAsc(UUID aggregateId);

    /** How many rows are still waiting, for the health indicator and the tests. */
    @Query("SELECT count(e) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    long countPending();

    /** Events that have been retried more than a threshold, i.e. the ones worth alerting on. */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL AND e.attempts >= :attempts ORDER BY e.createdAt")
    List<OutboxEvent> findStuck(@Param("attempts") int attempts, Pageable pageable);
}
