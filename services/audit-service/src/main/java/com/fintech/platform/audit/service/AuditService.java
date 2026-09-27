package com.fintech.platform.audit.service;

import com.fintech.platform.audit.persistence.AuditRecordEntity;
import com.fintech.platform.audit.persistence.AuditRecordRepository;
import com.fintech.platform.audit.persistence.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns consumed audit events into trail rows, exactly once each.
 *
 * <p><b>The claim and the row are one transaction.</b> The claim is an {@code INSERT ... ON
 * CONFLICT DO NOTHING}, so there is no window in which the event is marked processed and the row
 * is lost, and no window in which two consumers both record. A redelivery finds the claim taken and
 * returns false, and the fact is counted once however many times the broker hands the event over.
 *
 * <p><b>Nothing here interprets.</b> The action, result and metadata are stored as the producer
 * stated them. A recording service that grades the vocabulary starts disagreeing with the service
 * it records, and the disagreement reads as the trail being wrong when it was the interpretation.
 *
 * <p><b>Nothing here can rewrite history.</b> There is no update path and no delete path — not as
 * an undiscoverable omission but as the design: the entity has no setters, the repository exposes
 * no mutating query, the controller exposes no write endpoint, and the database trigger refuses
 * both anyway. Four layers saying the same thing is what makes the property survive the first
 * deadline that argues against it.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRecordRepository records;

    private final ProcessedEventRepository processed;

    private final Clock clock;

    private final Counter recorded;

    private final Counter duplicates;

    public AuditService(
            AuditRecordRepository records, ProcessedEventRepository processed, Clock clock, MeterRegistry meters) {
        this.records = records;
        this.processed = processed;
        this.clock = clock;
        this.recorded = Counter.builder("audit.records.recorded")
                .description("Audit events recorded, after deduplication")
                .register(meters);
        this.duplicates = Counter.builder("audit.events.duplicates")
                .description("Deliveries of an event whose id has already been claimed")
                .register(meters);
    }

    /** A fact worth keeping, as the producer stated it. */
    public record AuditFact(
            String action,
            String resourceType,
            String resourceId,
            UUID transactionId,
            String actorDigest,
            String result,
            String correlationId,
            String metadata,
            Instant occurredAt) {}

    /**
     * Claims the event and, if it is new, appends the audit row.
     *
     * @return true when the event was recorded, false when it is a redelivery
     */
    @Transactional
    public boolean record(UUID eventId, String topic, AuditFact fact) {
        Instant now = clock.instant();
        if (processed.claim(eventId, topic, now) == 0) {
            duplicates.increment();
            log.info("Ignoring redelivery of {} from {}; already recorded", eventId, topic);
            return false;
        }
        // The managed copy, not the argument. The id is assigned in the constructor, so Spring
        // Data cannot tell this entity is new and save() merges rather than persists: it copies
        // the state into a managed instance and returns it, leaving the argument detached. Phase 8
        // proved what ignoring the return value costs — every send logged, every row PENDING — so
        // here the return value is the row.
        AuditRecordEntity stored = records.save(AuditRecordEntity.record(
                eventId,
                topic,
                fact.action(),
                fact.resourceType(),
                fact.resourceId(),
                fact.transactionId(),
                fact.actorDigest(),
                fact.result(),
                fact.correlationId(),
                fact.metadata(),
                fact.occurredAt(),
                now));
        recorded.increment();
        log.info("Recorded {} {} as {}", fact.action(), eventId, stored.getId());
        return true;
    }
}
