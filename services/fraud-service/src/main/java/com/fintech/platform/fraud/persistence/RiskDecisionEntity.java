package com.fintech.platform.fraud.persistence;

import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.Money;
import com.fintech.platform.fraud.domain.RiskAssessment;
import com.fintech.platform.fraud.domain.RiskBand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * One payment's risk decision, kept forever.
 *
 * <p>Keyed by the payment's id, which makes re-scoring an <b>update to history</b> rather than a second
 * row. That is a choice with a cost: a decision's original score is overwritten, so an analyst cannot
 * later see what the engine said the first time. The alternative — a row per attempt — makes every read
 * of "what did we decide about this payment" ambiguous, and the one that matters is the current one.
 * The attempt count and the manual-adjustment columns are on the row, so the overwrite is visible, and
 * the superseded score is not silently lost: the alert timeline records the prior decision when one
 * exists.
 *
 * <p><b>No card number, token, device identifier, or network address is stored here.</b> Only the digests
 * the producing service computed. A row of these is read by analysts and exported to reporting, and a
 * payment's own identifiers are exactly what must not travel into that.
 *
 * <p>{@code reasons} and {@code facts} are stored as text rather than JSONB. Nothing queries inside them:
 * a decision is read whole or not at all, and the queries that filter this table filter on the scalar
 * columns that were denormalised out of it precisely so they could be indexed.
 */
@Entity
@Table(name = "risk_decisions")
public class RiskDecisionEntity {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "owner_subject_digest", nullable = false, length = 64)
    private String ownerSubjectDigest;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "payee_name", length = 256)
    private String payeeName;

    @Column(name = "merchant_reference", length = 128)
    private String merchantReference;

    @Column(name = "channel", length = 16)
    private String channel;

    @Column(name = "card_reference", length = 64)
    private String cardReference;

    @Column(name = "device_reference", length = 64)
    private String deviceReference;

    @Column(name = "network_reference", length = 64)
    private String networkReference;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, length = 16)
    private RiskBand band;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private FraudDecision decision;

    @Column(name = "alert_required", nullable = false)
    private boolean alertRequired;

    /** The reasons, as the JSON the API returns. */
    @Column(name = "reasons", nullable = false, columnDefinition = "text")
    private String reasons;

    /** What was assessed, and what could not be. The JSON the API returns as {@code facts}. */
    @Column(name = "facts", nullable = false, columnDefinition = "text")
    private String facts;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** How many times the engine has scored this payment, which is 1 unless an analyst re-scored. */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** Set when an analyst changed the score by hand, with the value they replaced. */
    @Column(name = "manual_score")
    private Integer manualScore;

    @Column(name = "manual_adjusted_by", length = 64)
    private String manualAdjustedBy;

    @Column(name = "manual_adjusted_at")
    private Instant manualAdjustedAt;

    @Column(name = "manual_reason", columnDefinition = "text")
    private String manualReason;

    protected RiskDecisionEntity() {}

    private RiskDecisionEntity(RiskAssessment assessment, String reasons, String facts, Instant now) {
        this.transactionId = assessment.transactionId();
        this.ownerSubjectDigest = assessment.ownerSubjectDigest();
        this.amountMinor = assessment.amount().minorUnits();
        this.currencyCode = assessment.amountCurrency();
        this.payeeName = assessment.payeeName();
        this.merchantReference = assessment.merchantReference();
        this.channel = assessment.channel();
        this.cardReference = assessment.cardReference();
        this.deviceReference = assessment.deviceReference();
        this.networkReference = assessment.networkReference();
        this.score = assessment.scoreValue();
        this.band = assessment.band();
        this.decision = assessment.decision();
        this.alertRequired = assessment.warrantsAlert();
        this.reasons = reasons;
        this.facts = facts;
        this.occurredAt = assessment.occurredAt();
        this.evaluatedAt = assessment.evaluatedAt();
        this.createdAt = now;
        this.updatedAt = now;
        this.attempt = 1;
    }

    /** The first decision about a payment. */
    public static RiskDecisionEntity firstAssessment(
            RiskAssessment assessment, String reasons, String facts, Instant now) {
        return new RiskDecisionEntity(assessment, reasons, facts, now);
    }

    /**
     * Replaces this decision with a newer one, keeping the attempt count and any manual adjustment.
     *
     * <p>The previous score is overwritten deliberately — see the class comment — and the reason lines
     * are replaced with the new ones, so the row always explains the score it currently holds. A
     * re-score that left the old reasons behind would produce a row whose explanations do not add up to
     * its number.
     */
    public void rescore(RiskAssessment assessment, String reasons, String facts, Instant now) {
        this.amountMinor = assessment.amount().minorUnits();
        this.currencyCode = assessment.amountCurrency();
        this.payeeName = assessment.payeeName();
        this.merchantReference = assessment.merchantReference();
        this.channel = assessment.channel();
        this.cardReference = assessment.cardReference();
        this.deviceReference = assessment.deviceReference();
        this.networkReference = assessment.networkReference();
        this.score = assessment.scoreValue();
        this.band = assessment.band();
        this.decision = assessment.decision();
        this.alertRequired = assessment.warrantsAlert();
        this.reasons = reasons;
        this.facts = facts;
        this.evaluatedAt = assessment.evaluatedAt();
        this.updatedAt = now;
        this.attempt = this.attempt + 1;
    }

    /**
     * Records an analyst's override.
     *
     * <p>The prior score is kept in {@link #manualScore} rather than discarded: an override that leaves
     * no trace of what it replaced is indistinguishable from the engine having produced the new score,
     * and the next thing anyone will want to know is how often the model is wrong.
     */
    public void manualAdjustment(
            int adjustedScore, RiskBand adjustedBand, String analystDigest, String reason, Instant now) {
        this.manualScore = this.score;
        this.score = adjustedScore;
        this.band = adjustedBand;
        this.decision = decisionFor(adjustedScore);
        // Always true. An analyst who has overridden a score has already expressed a concern, and an
        // override that could quietly remove the alert from a payment would be a way to close a real
        // finding without leaving a trace — which is also why the override itself is an audited action
        // rather than a field update.
        this.alertRequired = true;
        this.manualAdjustedBy = analystDigest;
        this.manualAdjustedAt = now;
        this.manualReason = reason;
        this.updatedAt = now;
    }

    /**
     * The decision an overridden score implies, using the published band boundaries.
     *
     * <p>An analyst's score is a judgement, not a rule evaluation, so the band boundaries are the honest
     * mapping: REVIEW for anything a human should see, DECLINE above the top band. There is no
     * configurable threshold here because the analyst is stating a conclusion, and a conclusion is not a
     * re-run of the policy.
     */
    private static FraudDecision decisionFor(int adjustedScore) {
        if (adjustedScore >= RiskBand.CRITICAL.lowestInclusive()) {
            return FraudDecision.DECLINE;
        }
        if (adjustedScore >= RiskBand.HIGH.lowestInclusive()) {
            return FraudDecision.REVIEW;
        }
        return FraudDecision.APPROVE;
    }

    /**
     * The facts of this payment, reconstructed from the stored columns.
     *
     * <p>For a re-score. The alternative — keeping the original event — would mean this service retaining
     * the {@code transaction-created} payload forever, including the payee's name, for a payment that may
     * be re-examined years later. The columns here are the same facts, and they are what the API already
     * exposes, so nothing is available to a re-score that was not already visible to anyone who could read
     * the decision.
     *
     * <p>{@code merchantReference} is stored as the payee's own reference, so a re-score re-derives the
     * merchant identity exactly as the first scoring did — including falling back to the payee's name when
     * there was no reference.
     */
    public com.fintech.platform.fraud.domain.PaymentFacts toFacts() {
        return new com.fintech.platform.fraud.domain.PaymentFacts(
                transactionId,
                ownerSubjectDigest,
                amount(),
                payeeName,
                merchantReference,
                channel,
                cardReference,
                deviceReference,
                networkReference,
                occurredAt);
    }

    public UUID transactionId() {
        return transactionId;
    }

    public String ownerSubjectDigest() {
        return ownerSubjectDigest;
    }

    public Money amount() {
        return Money.minor(amountMinor, Currency.getInstance(currencyCode));
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

    public String channel() {
        return channel;
    }

    public String cardReference() {
        return cardReference;
    }

    public String deviceReference() {
        return deviceReference;
    }

    public String networkReference() {
        return networkReference;
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

    public boolean alertRequired() {
        return alertRequired;
    }

    public String reasons() {
        return reasons;
    }

    public String facts() {
        return facts;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int attempt() {
        return attempt;
    }

    public Integer manualScore() {
        return manualScore;
    }

    public String manualAdjustedBy() {
        return manualAdjustedBy;
    }

    public Instant manualAdjustedAt() {
        return manualAdjustedAt;
    }

    public String manualReason() {
        return manualReason;
    }

    /** Whether an analyst has changed this score by hand. */
    public boolean isManuallyAdjusted() {
        return manualAdjustedBy != null;
    }

    /** Whether this decision is still the engine's own. */
    public boolean isSystemAssessed() {
        return manualAdjustedBy == null;
    }
}
