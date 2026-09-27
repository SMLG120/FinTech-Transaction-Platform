package com.fintech.platform.fraud.service;

import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.persistence.FraudAlertEntity;
import com.fintech.platform.fraud.persistence.FraudAlertEventEntity;
import com.fintech.platform.fraud.persistence.FraudAlertEventRepository;
import com.fintech.platform.fraud.persistence.FraudAlertRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The analyst queue: claiming, closing, and the audit trail both leave.
 *
 * <p><b>Every state change is a method on the entity under a row lock, and every one is audited.</b> The
 * two halves are the same decision: an alert's history is the only record of which analyst looked at
 * which finding, and a system where the history can be written without the change — or the change without
 * the history — is a system whose queue cannot be audited. Both are in one transaction here, so neither
 * can happen alone.
 *
 * <p><b>An analyst is identified by digest.</b> The caller has already been authorised by the gateway and
 * its roles have been verified; this service digests the subject for the same reason every other service
 * does, and never stores a raw sub. The digest on a timeline entry is enough to answer "did I look at
 * this" and to correlate with the identity provider, and is not enough to name anybody to a reader of the
 * table.
 *
 * <p><b>Claim and close return outcomes, not exceptions, for the ordinary conflicts.</b> "Somebody else has
 * it" and "that alert is already closed" are the normal results of two people working a queue, and they
 * belong in a 409 with the current state in the body. Exceptions are for the cases that should not happen.
 */
@Service
public class AlertService {

    private final FraudAlertRepository alerts;

    private final FraudAlertEventRepository alertEvents;

    private final OutboxWriter outbox;

    private final FraudProperties properties;

    private final Clock clock;

    public AlertService(
            FraudAlertRepository alerts,
            FraudAlertEventRepository alertEvents,
            OutboxWriter outbox,
            FraudProperties properties,
            Clock clock) {
        this.alerts = alerts;
        this.alertEvents = alertEvents;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /** The unworked queue, highest risk first. */
    @Transactional(readOnly = true)
    public Page<FraudAlertEntity> queue(
            RiskBand band, String ownerSubjectDigest, String claimedByDigest, int page, int size) {
        return alerts.findQueue(
                FraudAlertRepository.OPEN_STATES, band, ownerSubjectDigest, claimedByDigest, pageRequest(page, size));
    }

    /** Everything, newest first. */
    @Transactional(readOnly = true)
    public Page<FraudAlertEntity> recent(int page, int size) {
        return alerts.findRecent(pageRequest(page, size));
    }

    /** One alert and its timeline. */
    @Transactional(readOnly = true)
    public AlertDetail detail(UUID alertId) {
        FraudAlertEntity alert = alerts.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        return new AlertDetail(alert, alertEvents.findByAlertIdOrderByOccurredAtDesc(alertId));
    }

    /** The alerts raised for one payment. */
    @Transactional(readOnly = true)
    public List<FraudAlertEntity> forTransaction(UUID transactionId) {
        return alerts.findByTransactionIdOrderByCreatedAtDesc(transactionId);
    }

    /**
     * Takes an open alert for an analyst.
     *
     * @return the claimed alert, or empty when somebody else already has it
     */
    @Transactional
    public java.util.Optional<FraudAlertEntity> claim(UUID alertId, String analystDigest) {
        FraudAlertEntity alert =
                alerts.findByIdForUpdate(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        if (!alert.claim(analystDigest, clock.instant())) {
            return java.util.Optional.empty();
        }
        alertEvents.save(FraudAlertEventEntity.of(
                alertId, FraudAlertEventEntity.AlertEventAction.CLAIMED, analystDigest, null, clock.instant()));
        recordAudit(alert, "fraud-alert-claimed", analystDigest, Map.of("alertId", alertId.toString()));
        return java.util.Optional.of(alert);
    }

    /**
     * Closes an alert the analyst has claimed.
     *
     * @param resolved true to resolve, false to dismiss as a false positive
     * @return the closed alert, or empty when the analyst does not hold it or it is already closed
     */
    @Transactional
    public java.util.Optional<FraudAlertEntity> close(
            UUID alertId, String analystDigest, boolean resolved, String resolution, String note) {
        FraudAlertEntity alert =
                alerts.findByIdForUpdate(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        FraudAlertEntity.AlertState target =
                resolved ? FraudAlertEntity.AlertState.RESOLVED : FraudAlertEntity.AlertState.DISMISSED;
        if (!alert.close(analystDigest, target, resolution, note, clock.instant())) {
            return java.util.Optional.empty();
        }
        alertEvents.save(FraudAlertEventEntity.of(
                alertId,
                resolved
                        ? FraudAlertEventEntity.AlertEventAction.RESOLVED
                        : FraudAlertEventEntity.AlertEventAction.DISMISSED,
                analystDigest,
                note,
                clock.instant()));
        recordAudit(
                alert,
                resolved ? "fraud-alert-resolved" : "fraud-alert-dismissed",
                analystDigest,
                Map.of(
                        "alertId", alertId.toString(),
                        "transactionId", alert.transactionId().toString(),
                        "resolution", resolution == null ? "" : resolution));
        return java.util.Optional.of(alert);
    }

    /**
     * Publishes an {@code audit-events} record for an action on a fraud alert.
     *
     * <p>The actor is a digest, the resource is the alert, and the result is the action's outcome — the
     * four fields an auditor asks for and the ones a log line written by a request thread is missing
     * whenever the request was served by a different instance.
     *
     * <p>What is deliberately absent is the analyst's IP address. The platform's audit events do not carry
     * one for staff actions, because {@code X-Forwarded-For} in this topology is the caller's own header
     * and recording it would put an unauthenticated value in an audit record while looking as though it
     * were a verified one. When a trustworthy client address is available it belongs here; until then the
     * digest and the correlation id are what there is, and the gap is recorded in the security notes
     * rather than papered over with a value that cannot be believed.
     */
    private void recordAudit(
            FraudAlertEntity alert, String action, String analystDigest, Map<String, String> metadata) {
        outbox.record(
                "FraudAlert",
                alert.id(),
                1,
                KafkaTopics.AUDIT_EVENTS,
                action,
                new AuditEvent(
                        action,
                        alert.id().toString(),
                        "fraud-alert",
                        alert.transactionId().toString(),
                        analystDigest,
                        "SUCCESS",
                        metadata),
                clock.instant());
    }

    /**
     * The audit record itself.
     *
     * <p>It has no envelope method on purpose. An earlier version had one, and it was dead code that
     * also got two things wrong: it read {@code Instant.now()} through a static helper, bypassing the
     * {@link Clock} this service is built on and making the timestamp untestable, and it took a
     * correlation id as a parameter while the one that actually publishes — {@link OutboxWriter} — takes
     * it from {@link com.fintech.platform.common.correlation.CorrelationId#current()}. Two ways to build
     * an audit envelope is one too many, and the one nobody called was the one that would have dropped
     * the correlation id.
     */
    public record AuditEvent(
            String action,
            String resourceId,
            String resourceType,
            String transactionId,
            String actorDigest,
            String result,
            Map<String, String> metadata) {}

    /** An alert and its timeline. */
    public record AlertDetail(FraudAlertEntity alert, List<FraudAlertEventEntity> timeline) {}

    /**
     * A page request, with the size clamped.
     *
     * <p>Clamped rather than validated because a list endpoint that 400s on {@code size=10000} is a list
     * endpoint whose largest page is chosen by whoever asks first. The cap is here so one request cannot
     * ask the database for a hundred thousand rows.
     */
    private static Pageable pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200));
    }

    /** Thrown when an alert id does not exist. Mapped to 404. */
    public static class AlertNotFoundException extends RuntimeException {

        public AlertNotFoundException(UUID alertId) {
            super("no fraud alert with id " + alertId);
        }
    }
}
