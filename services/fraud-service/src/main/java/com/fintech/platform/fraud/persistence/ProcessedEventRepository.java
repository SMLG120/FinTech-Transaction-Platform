package com.fintech.platform.fraud.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The event-deduplication ledger.
 *
 * <p>{@link #claim} is the whole mechanism, and it is an {@code INSERT ... ON CONFLICT DO NOTHING}
 * rather than a {@code findById} followed by a save. That difference is the entire at-least-once story:
 * a read-then-write has a window between the two statements in which a second consumer thread sees no
 * row, decides the event is new, and processes it again. The insert does not have that window. Either
 * this consumer inserted the row, or another one already had, and the boolean says which.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    /**
     * Claims an event for processing.
     *
     * @return true if this caller claimed it and should process it; false if it was already processed
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, topic, processed_at)
            VALUES (:eventId, :topic, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("topic") String topic, @Param("processedAt") Instant processedAt);

    /**
     * Deletes claims older than a cut-off.
     *
     * <p>Run on a schedule and in bounded batches, because {@code DELETE} over a large predicate takes a
     * lock that blocks the inserts of every live consumer. A fraud service that deadlocks its own
     * deduplication table during a cleanup has stopped deduplicating, which is the one job it must not
     * stop doing.
     */
    @Modifying
    @Query(value = "DELETE FROM processed_events WHERE processed_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
