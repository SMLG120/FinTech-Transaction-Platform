package com.fintech.platform.fraud.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The result of scoring one payment: the score, the band, the decision, and everything needed to argue
 * about it later.
 *
 * <p>Immutable and complete. This is what gets persisted, published, and shown to an analyst, and it is
 * assembled once by {@code RiskEngine.evaluate} so that the number on the row, the number in the alert and
 * the number in the {@code fraud-analysis-completed} event cannot be three separate computations that
 * happened to agree.
 *
 * <p><b>{@code alertRequired} is decided once, here, by the policy that also chose the decision.</b> A
 * caller that recomputed "is this above the alert threshold" from the score would get a different answer
 * for a payment that was escalated by a single strong rule rather than by its total, and the alert queue
 * and the decision would then disagree about which payments need a human.
 *
 * <p><b>{@code facts} records what was not checked, not only what was.</b> An analyst looking at a
 * MEDIUM with two reasons should be able to see that velocity was unavailable and no merchant history
 * existed, instead of inferring a clean result from the absence of findings. Absence of evidence is the
 * most misleading thing a risk explanation can contain.
 */
public record RiskAssessment(
        UUID transactionId,
        Instant occurredAt,
        Instant evaluatedAt,
        Money amount,
        String amountCurrency,
        String ownerSubjectDigest,
        String payeeName,
        String merchantReference,
        String channel,
        String cardReference,
        String deviceReference,
        String networkReference,
        RiskScore score,
        FraudDecision decision,
        boolean alertRequired,
        List<RuleOutcome> reasons,
        Map<String, String> facts) {

    public RiskAssessment {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(score, "score must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    /** The score on its own, for the places that only need the number. */
    public int scoreValue() {
        return score.value();
    }

    public RiskBand band() {
        return score.band();
    }

    /** Whether a human is expected to look at this payment. */
    public boolean warrantsAlert() {
        return alertRequired;
    }

    /**
     * Whether an alert is warranted and the decision is adverse.
     *
     * <p>This is the condition the roadmap calls a step-down, and it is recorded rather than acted on:
     * transaction-service does not read fraud decisions yet, so a payment this engine declines is still
     * AUTHORIZED. See ADR-0008 for why the phase ships that way and what would have to be true to close
     * the loop.
     */
    public boolean warrantsStepDown() {
        return alertRequired && decision.isAdverse();
    }

    /** The one-line explanation stored on the alert and shown in the queue. */
    public String summary() {
        if (reasons.isEmpty()) {
            return "No rule fired; scored " + score.value() + " (" + score.band() + ").";
        }
        return score.value() + " (" + score.band() + "), " + decision + ": "
                + reasons.getFirst().reason();
    }
}
