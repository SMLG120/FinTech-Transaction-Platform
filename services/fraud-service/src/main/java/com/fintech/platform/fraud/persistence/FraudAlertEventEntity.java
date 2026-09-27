package com.fintech.platform.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry in an alert's history.
 *
 * <p>An append-only timeline: raised, claimed, resolved, dismissed, re-scored. The alert row holds the
 * current state, and this holds how it got there — which is the part that answers "who looked at this and
 * what did they conclude" months later, when the only evidence is a log stream somebody may have rotated.
 *
 * <p><b>Append-only, and never updated.</b> There is no {@code updatedAt} and no setter that would allow
 * one. A timeline that can be edited is not a timeline, and the row that records an analyst's conclusion
 * is exactly the row someone might want to edit. Making it impossible at the type level costs nothing
 * here, because nothing legitimately needs to.
 *
 * <p>The actor is a subject digest for the same reason the alert's owner digest is: a fraud analyst's
 * identity is personal data, and this table is queried by reporting.
 */
@Entity
@Table(name = "fraud_alert_events")
public class FraudAlertEventEntity {

    /** What happened. */
    public enum AlertEventAction {
        RAISED,
        CLAIMED,
        RESOLVED,
        DISMISSED,
        RESCORED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private AlertEventAction action;

    /** The analyst's subject digest, or null for a system action. */
    @Column(name = "actor_digest", length = 64)
    private String actorDigest;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected FraudAlertEventEntity() {}

    private FraudAlertEventEntity(UUID alertId, AlertEventAction action, String actorDigest, String note, Instant now) {
        this.alertId = Objects.requireNonNull(alertId, "alertId must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.actorDigest = actorDigest;
        this.note = note;
        this.occurredAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static FraudAlertEventEntity of(
            UUID alertId, AlertEventAction action, String actorDigest, String note, Instant now) {
        return new FraudAlertEventEntity(alertId, action, actorDigest, note, now);
    }

    public UUID id() {
        return id;
    }

    public UUID alertId() {
        return alertId;
    }

    public AlertEventAction action() {
        return action;
    }

    public String actorDigest() {
        return actorDigest;
    }

    public String note() {
        return note;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
