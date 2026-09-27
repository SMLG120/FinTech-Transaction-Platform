package com.fintech.platform.notification.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    /**
     * Claims an event id, or returns false because somebody already has.
     *
     * <p>An {@code INSERT ... ON CONFLICT DO NOTHING} rather than an existence check followed by an
     * insert, so the claim is atomic. A check-then-insert is correct until two consumers of the same
     * partition run it at once, at which point both see the row absent, both send, and the customer is
     * told twice with nothing in the logs to say why.
     */
    @Modifying
    @Query(
            value = "INSERT INTO processed_events (event_id, topic, processed_at) "
                    + "VALUES (:eventId, :topic, :processedAt) ON CONFLICT (event_id) DO NOTHING",
            nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("topic") String topic, @Param("processedAt") Instant processedAt);
}
