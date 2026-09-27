package com.fintech.platform.fraud.persistence;

import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskAssessment;
import com.fintech.platform.fraud.domain.RiskBand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment a human is expected to look at.
 *
 * <p>One alert per decision, keyed by the decision's id — which is the payment's id. There is no separate
 * alert sequence: an alert <em>is</em> the decision, plus the lifecycle state that a decision does not
 * have. A second table with its own identity would allow two open alerts for one payment, and two analysts
 * each resolving "the same" suspicious payment is how one of them resolves it without reading it.
 *
 * <p><b>The lifecycle is a state machine and only {@link #state} moves it.</b> OPEN, then CLAIMED by one
 * analyst, then RESOLVED or DISMISSED. There is no way back to OPEN, because a re-opened alert with the
 * same id looks like a new finding to a queue that has already been worked through, and the new analyst
 * has none of the context the first one had. A finding that genuinely needs a second look is a new
 * decision — a re-score raising a fresh alert — not an un-resolution.
 *
 * <p><b>An analyst claiming an alert is the concurrency control.</b> Two analysts clicking at the same
 * moment is not a rare event, it is a Tuesday, and the loser must be told rather than silently overwriting
 * the winner. The claim is a conditional update on the state, so exactly one succeeds; the other gets a
 * 409 and sees who has it.
 */
@Entity
@Table(name = "fraud_alerts")
public class FraudAlertEntity {

    /** The lifecycle. See the class comment. */
    public enum AlertState {
        /** Raised, nobody has picked it up. */
        OPEN,
        /** One analyst owns it. */
        CLAIMED,
        /** Looked at, and the finding was real. */
        RESOLVED,
        /** Look at, and a false positive. */
        DISMISSED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "owner_subject_digest", nullable = false, length = 64)
    private String ownerSubjectDigest;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, length = 16)
    private RiskBand band;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private FraudDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private AlertState state;

    /** The one-line summary from the assessment, so a queue does not need the decision joined in. */
    @Column(name = "summary", nullable = false, length = 512)
    private String summary;

    /** The reasons, copied from the decision. */
    @Column(name = "reasons", nullable = false, columnDefinition = "text")
    private String reasons;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "payee_name", length = 256)
    private String payeeName;

    @Column(name = "merchant_reference", length = 128)
    private String merchantReference;

    /** The analyst who claimed it, or null while open. */
    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "closed_by", length = 64)
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** What the analyst concluded, required when resolving. */
    @Column(name = "resolution", length = 64)
    private String resolution;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FraudAlertEntity() {}

    private FraudAlertEntity(UUID id, RiskAssessment assessment, String reasons, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.transactionId = assessment.transactionId();
        this.ownerSubjectDigest = assessment.ownerSubjectDigest();
        this.score = assessment.scoreValue();
        this.band = assessment.band();
        this.decision = assessment.decision();
        this.state = AlertState.OPEN;
        this.summary = truncate(assessment.summary(), 512);
        this.reasons = reasons;
        this.amountMinor = assessment.amount().minorUnits();
        this.currencyCode = assessment.amountCurrency();
        this.payeeName = assessment.payeeName();
        this.merchantReference = assessment.merchantReference();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static FraudAlertEntity raisedFor(UUID id, RiskAssessment assessment, String reasons, Instant now) {
        return new FraudAlertEntity(id, assessment, reasons, now);
    }

    /**
     * Takes ownership, or refuses because someone else has it.
     *
     * <p>Returns false rather than throwing so the service can turn it into a 409 with the current
     * owner's digest in the body — which is the one case where telling the loser who won is genuinely
     * useful rather than a disclosure risk, since both are fraud analysts looking at the same queue.
     */
    public boolean claim(String analystDigest, Instant now) {
        if (state != AlertState.OPEN) {
            return false;
        }
        this.state = AlertState.CLAIMED;
        this.claimedBy = analystDigest;
        this.claimedAt = now;
        this.updatedAt = now;
        return true;
    }

    /**
     * Closes the alert.
     *
     * <p>Only from CLAIMED, and only by the analyst who claimed it. A resolve that did not require the
     * claim would let anyone close anyone's alert, and an alert that anyone can close is an alert that
     * gets closed.
     */
    public boolean close(String analystDigest, AlertState target, String resolution, String note, Instant now) {
        if (state != AlertState.CLAIMED || !analystDigest.equals(claimedBy)) {
            return false;
        }
        if (target != AlertState.RESOLVED && target != AlertState.DISMISSED) {
            throw new IllegalArgumentException("an alert closes as RESOLVED or DISMISSED, not " + target);
        }
        this.state = target;
        this.closedBy = analystDigest;
        this.closedAt = now;
        this.resolution = resolution;
        this.resolutionNote = note;
        this.updatedAt = now;
        return true;
    }

    public boolean isOpen() {
        return state == AlertState.OPEN;
    }

    public boolean isClaimedBy(String analystDigest) {
        return state == AlertState.CLAIMED && analystDigest.equals(claimedBy);
    }

    public UUID id() {
        return id;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public String ownerSubjectDigest() {
        return ownerSubjectDigest;
    }

    public int score() {
        return score;
    }

    public RiskBand band() {
        return band;
    }

    public FraudDecision decision() {
        return decision;
    }

    public AlertState state() {
        return state;
    }

    public String summary() {
        return summary;
    }

    public String reasons() {
        return reasons;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public String payeeName() {
        return payeeName;
    }

    public String merchantReference() {
        return merchantReference;
    }

    public String claimedBy() {
        return claimedBy;
    }

    public Instant claimedAt() {
        return claimedAt;
    }

    public String closedBy() {
        return closedBy;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public String resolution() {
        return resolution;
    }

    public String resolutionNote() {
        return resolutionNote;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Whether this alert has been open longer than the configured SLA.
     *
     * <p>Measured from creation for an unclaimed alert and from the claim for a claimed one, because
     * "open" and "claimed but not answered" are different queues with different reasons for being
     * late — nobody picked it up, or the analyst picked it up and stalled.
     */
    public boolean isBreachingSla(Instant now, int breachHours) {
        if (state == AlertState.RESOLVED || state == AlertState.DISMISSED) {
            return false;
        }
        Instant since = state == AlertState.CLAIMED ? claimedAt : createdAt;
        return since != null && since.isBefore(now.minusSeconds(breachHours * 3600L));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
    }
}
