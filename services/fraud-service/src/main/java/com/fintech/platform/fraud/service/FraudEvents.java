package com.fintech.platform.fraud.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.platform.fraud.domain.Money;
import com.fintech.platform.fraud.domain.PaymentFacts;
import com.fintech.platform.fraud.domain.RiskAssessment;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The two event bodies this service reads and writes.
 *
 * <p>Records, with every field defaulted, because a consumer's first obligation is to survive a payload
 * that is missing a field rather than to reject it. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * does the same for fields it has never heard of, which is what lets transaction-service add a field to
 * {@code transaction-created} without breaking a deployed fraud service. The versioning in the envelope
 * is the real defence; this is the courtesy that makes a rolling deploy possible.
 *
 * <p><b>Amounts arrive and leave as decimal strings.</b> A JSON number is a double by the time a client
 * has parsed it, and a fraud threshold compared against a double is a threshold that moves with the
 * size of the number. See ADR-0007.
 */
public final class FraudEvents {

    private FraudEvents() {}

    /**
     * The {@code transaction-created} payload, as published by transaction-service.
     *
     * <p>Only the fields the rules need, plus the fraud context the producing service built. The
     * identifiers are HMAC digests computed there: this service has no key that could reverse them, and
     * does not need one.
     *
     * @param fraudContext the digests and channel; null for a payment made with no client, which is
     *     a real case rather than a malformed one and scores the four rules that do not need it
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionCreatedPayload(
            String transactionId,
            String ownerSubjectDigest,
            String amount,
            String currency,
            String payeeName,
            String payeeReference,
            FraudContextPayload fraudContext,
            String occurredAt) {

        /**
         * The fraud context object inside the payload, absent on a payment made with no client at all.
         *
         * <p><b>There is deliberately no {@code cardToken} field.</b> transaction-service digests the
         * card before the event is built, and the only thing this service is able to correlate on is that
         * digest. Naming the token here would be the first step towards somebody using it, and
         * {@code @JsonIgnoreProperties(ignoreUnknown = true)} means a producer that emitted one anyway
         * would have it dropped rather than read — which is the behaviour to want from a card credential
         * arriving at a service that has no business holding it.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record FraudContextPayload(
                String cardReference, String channel, String deviceReference, String networkReference) {}

        /**
         * The domain facts.
         *
         * <p>Parsing happens here, before any state is touched, so the rules never see a {@code Money}
         * that failed to construct and a malformed event never half-scores. The payer digest is the
         * producing service's, which is the only one this service stores: it holds no key that could
         * reproduce transaction-service's digests, and must never be given one.
         *
         * @throws IllegalArgumentException if the payload is missing or has an unusable field. Every one
         *     of these is a dead-letter rather than a degradation, unlike the fraud context: without
         *     {@code occurredAt} the three time-window rules cannot be evaluated at all, and inventing
         *     "now" for a payment that was made hours ago would drop it into the current velocity window
         *     as though it had just arrived.
         */
        public PaymentFacts toFacts() {
            if (transactionId == null || ownerSubjectDigest == null || amount == null || currency == null) {
                throw new IllegalArgumentException("transaction-created payload is missing a required field");
            }
            if (occurredAt == null || occurredAt.isBlank()) {
                throw new IllegalArgumentException("transaction-created payload has no occurredAt");
            }
            FraudContextPayload context = fraudContext;
            return new PaymentFacts(
                    UUID.fromString(transactionId),
                    ownerSubjectDigest,
                    Money.parse(amount, Currency.getInstance(currency)),
                    payeeName,
                    payeeReference,
                    context == null ? null : context.channel(),
                    context == null ? null : context.cardReference(),
                    context == null ? null : context.deviceReference(),
                    context == null ? null : context.networkReference(),
                    Instant.parse(occurredAt));
        }
    }

    /**
     * The {@code fraud-analysis-requested} payload.
     *
     * <p>Names the payment and asks for it to be scored again, and nothing else. The features are
     * re-collected from the stored decision and the current observation table rather than from the
     * original event, which is what makes a re-score meaningful: it asks "would the engine reach the same
     * conclusion now, with everything it has learned since", not "what did it say then".
     *
     * @param reason why the re-score was asked for, kept on the alert timeline
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RescoreRequestPayload(String transactionId, String requestedBy, String reason, String occurredAt) {}

    /**
     * The {@code fraud-analysis-completed} payload.
     *
     * <p>Carries the whole explanation, not just the score, because the obvious next consumer is
     * transaction-service deciding whether to step a payment down — and a consumer that has to call back
     * to ask why a payment was declined will eventually not call back at all.
     */
    public record FraudAnalysisCompleted(
            String transactionId,
            String ownerSubjectDigest,
            String amount,
            String currency,
            String payeeName,
            String merchantReference,
            String channel,
            int score,
            String band,
            String decision,
            boolean alertRequired,
            boolean stepDownRecommended,
            long attempt,
            List<Reason> reasons,
            Map<String, String> facts,
            Instant occurredAt) {

        /** One fired rule, as it appears on the wire. */
        public record Reason(
                String ruleId, String ruleName, int points, String explanation, Map<String, String> evidence) {}

        public static FraudAnalysisCompleted of(RiskAssessment assessment, long attempt) {
            return new FraudAnalysisCompleted(
                    assessment.transactionId().toString(),
                    assessment.ownerSubjectDigest(),
                    assessment.amount().toDecimalString(),
                    assessment.amountCurrency(),
                    assessment.payeeName(),
                    assessment.merchantReference(),
                    assessment.channel(),
                    assessment.scoreValue(),
                    assessment.band().name(),
                    assessment.decision().name(),
                    assessment.warrantsAlert(),
                    assessment.warrantsStepDown(),
                    attempt,
                    assessment.reasons().stream()
                            .map(r -> new Reason(r.ruleId(), r.ruleName(), r.points(), r.explanation(), r.evidence()))
                            .toList(),
                    assessment.facts(),
                    assessment.occurredAt());
        }
    }
}
