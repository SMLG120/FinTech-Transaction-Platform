package com.fintech.platform.audit.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.UUID;

/**
 * The event bodies this service records.
 *
 * <p>One shape, because every producer on the {@code audit-events} topic writes the same contract:
 * who did what to which resource, with what outcome. The fraud engine's {@code AuditEvent} is the
 * first writer; the next producer's fields arrive through {@code metadata} rather than through a
 * new payload type, which is what keeps the trail open-vocabulary without a migration per producer.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} for the same rolling-deploy reason as the
 * other services: a producer that adds a field must not break a deployed audit service, because a
 * trail that stops recording during a deploy is a trail with a hole in it.
 */
public final class AuditPayloads {

    private AuditPayloads() {}

    /**
     * An {@code audit-events} payload, as published through a producer's outbox.
     *
     * <p>Every field defaulted, because a recorder's first obligation is to survive a payload that
     * is missing a field rather than to reject it with a confusing error. What is required — the
     * action, the resource, the result — is checked in {@link #requireRecordable}, which dead-letters
     * the event when it is absent rather than writing a row that says nothing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditEventPayload(
            String action,
            String resourceId,
            String resourceType,
            String transactionId,
            String actorDigest,
            String result,
            Map<String, String> metadata) {

        public UUID transactionUuidOrNull() {
            if (transactionId == null || transactionId.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(transactionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("transactionId is not a UUID: " + transactionId, e);
            }
        }

        public void requireRecordable() {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("audit event carries no action");
            }
            if (resourceId == null || resourceId.isBlank()) {
                throw new IllegalArgumentException("audit event carries no resourceId");
            }
            if (resourceType == null || resourceType.isBlank()) {
                throw new IllegalArgumentException("audit event carries no resourceType");
            }
            if (result == null || result.isBlank()) {
                throw new IllegalArgumentException("audit event carries no result");
            }
        }
    }
}
