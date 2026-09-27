package com.fintech.platform.audit.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRecordRepository extends JpaRepository<AuditRecordEntity, UUID> {

    /**
     * The trail, newest first, optionally narrowed.
     *
     * <p>One query rather than a derived method per filter combination, because optional filters
     * would otherwise be a method per combination. A null action is not a filter at all, so a
     * request with no parameters returns the most recent page of everything — the alternative,
     * treating an absent filter as "matches null", returns an empty list to an auditor who asked
     * for the trail.
     *
     * <p>The bounds are never null: the controller widens an absent bound to the epoch or to now.
     * A null timestamp bind is not merely an unfiltered query — Postgres cannot infer the
     * parameter's type and answers with a type error instead of rows — so nulls are resolved at
     * the edge rather than in this query.
     */
    @Query("SELECT r FROM AuditRecordEntity r "
            + "WHERE (:action IS NULL OR r.action = :action) "
            + "AND r.occurredAt >= :from AND r.occurredAt <= :to "
            + "ORDER BY r.occurredAt DESC")
    Page<AuditRecordEntity> search(
            @Param("action") String action, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** One resource's history, in the order it happened. */
    @Query("SELECT r FROM AuditRecordEntity r "
            + "WHERE r.resourceType = :resourceType AND r.resourceId = :resourceId "
            + "ORDER BY r.occurredAt ASC")
    java.util.List<AuditRecordEntity> historyOf(
            @Param("resourceType") String resourceType, @Param("resourceId") String resourceId);

    /** One payment's trail, in the order it happened. */
    java.util.List<AuditRecordEntity> findByTransactionIdOrderByOccurredAtAsc(UUID transactionId);

    /** One request traced across every service it touched. */
    java.util.List<AuditRecordEntity> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);
}
