package com.fintech.platform.audit.web;

import com.fintech.platform.audit.persistence.AuditRecordEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * What the audit API returns.
 *
 * <p>The record as the producer stated it, with the receipt attached. The actor is the digest the
 * producer computed — never resolved to a name, email or subject here, because resolving it would
 * turn the trail into the customer list its own schema refuses to be. An auditor who needs the
 * person behind a staff digest follows the producing service's own audited join; this API does not
 * do it for them, which is what keeps the join audited rather than convenient.
 */
public final class AuditResponses {

    private AuditResponses() {}

    /** One trail row: the fact, when it happened, and when it was recorded. */
    public record AuditRecordView(
            UUID id,
            UUID eventId,
            String action,
            String resourceType,
            String resourceId,
            UUID transactionId,
            String actorDigest,
            String result,
            String correlationId,
            String metadata,
            Instant occurredAt,
            Instant receivedAt) {

        /**
         * Renders a trail row for an auditor.
         *
         * @param record the row
         * @return the view
         */
        public static AuditRecordView of(AuditRecordEntity record) {
            return new AuditRecordView(
                    record.getId(),
                    record.getEventId(),
                    record.getAction(),
                    record.getResourceType(),
                    record.getResourceId(),
                    record.getTransactionId(),
                    record.getActorDigest(),
                    record.getResult(),
                    record.getCorrelationId(),
                    record.getMetadata(),
                    record.getOccurredAt(),
                    record.getReceivedAt());
        }
    }
}
