package com.fintech.platform.fraud.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.domain.RuleOutcome;
import com.fintech.platform.fraud.persistence.FraudAlertEntity;
import com.fintech.platform.fraud.persistence.FraudAlertEventEntity;
import com.fintech.platform.fraud.persistence.RiskDecisionEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The API's view of a decision, an alert and an alert's history.
 *
 * <p><b>These are the only places entity types stop crossing the boundary.</b> Returning an entity from a
 * controller publishes its whole shape, including every accessor, and the first added convenience method
 * becomes part of the API by accident. Each record here names the fields a caller is entitled to see, and
 * a field that is not named here does not exist.
 *
 * <p><b>Amounts are decimal strings, and scores are integers.</b> The string is the same reasoning as
 * everywhere else on this platform: a JSON number is a double by the time a client has parsed it, and a
 * dashboard that divides a rounded amount by a rounded total produces a rate that is wrong in a way
 * nobody can see. The score is a genuine integer and is sent as one.
 *
 * <p><b>Digests are exposed, because they are the point.</b> A fraud analyst correlates a decision with
 * another by subject digest; they cannot resolve it to a person from this API, and that is deliberate.
 * Whoever holds the identity-provider role can, and this service cannot, so a compromised fraud dashboard
 * yields pseudonyms.
 */
public final class FraudResponses {

    private FraudResponses() {}

    private static final TypeReference<List<RuleOutcome>> REASONS = new TypeReference<>() {};

    private static final TypeReference<Map<String, String>> FACTS = new TypeReference<>() {};

    /**
     * One decision.
     *
     * @param reasons the rules that fired, with their points and evidence
     * @param facts what was assessed and what could not be — including the signals that were unavailable
     * @param manuallyAdjusted whether an analyst has overruled the engine
     * @param manualScore the score the engine produced, when it has been overruled
     */
    public record DecisionResponse(
            UUID transactionId,
            String ownerSubjectDigest,
            String amount,
            String currency,
            String payeeName,
            String merchantReference,
            String channel,
            int score,
            RiskBand band,
            FraudDecision decision,
            boolean alertRequired,
            boolean stepDownRecommended,
            List<RuleOutcome> reasons,
            Map<String, String> facts,
            Instant occurredAt,
            Instant evaluatedAt,
            Instant createdAt,
            Instant updatedAt,
            int attempt,
            boolean manuallyAdjusted,
            Integer manualScore,
            String manualAdjustedBy,
            Instant manualAdjustedAt,
            String manualReason) {

        public static DecisionResponse of(RiskDecisionEntity decision, ObjectMapper json) {
            return new DecisionResponse(
                    decision.transactionId(),
                    decision.ownerSubjectDigest(),
                    decision.amount().toDecimalString(),
                    decision.currencyCode(),
                    decision.payeeName(),
                    decision.merchantReference(),
                    decision.channel(),
                    decision.score(),
                    decision.band(),
                    decision.decision(),
                    decision.alertRequired(),
                    decision.decision().isAdverse() && decision.alertRequired(),
                    read(json, decision.reasons(), REASONS, "[]"),
                    read(json, decision.facts(), FACTS, "{}"),
                    decision.occurredAt(),
                    decision.evaluatedAt(),
                    decision.createdAt(),
                    decision.updatedAt(),
                    decision.attempt(),
                    decision.isManuallyAdjusted(),
                    decision.manualScore(),
                    decision.manualAdjustedBy(),
                    decision.manualAdjustedAt(),
                    decision.manualReason());
        }
    }

    /**
     * One alert in the queue.
     *
     * <p>Less than the decision response on purpose. A queue is a list of fifty and a detail view is one,
     * and shipping the reasons and the facts on every row is how a list endpoint ends up returning a
     * megabyte to render a table.
     */
    public record AlertResponse(
            UUID id,
            UUID transactionId,
            String ownerSubjectDigest,
            String amount,
            String currency,
            String payeeName,
            String merchantReference,
            int score,
            RiskBand band,
            FraudDecision decision,
            FraudAlertEntity.AlertState state,
            String summary,
            String claimedBy,
            Instant claimedAt,
            String closedBy,
            Instant closedAt,
            String resolution,
            String resolutionNote,
            Instant createdAt,
            Instant updatedAt) {

        public static AlertResponse of(FraudAlertEntity alert) {
            return new AlertResponse(
                    alert.id(),
                    alert.transactionId(),
                    alert.ownerSubjectDigest(),
                    new BigDecimal(alert.amountMinor())
                            .movePointLeft(fractionDigits(alert.currencyCode()))
                            .toPlainString(),
                    alert.currencyCode(),
                    alert.payeeName(),
                    alert.merchantReference(),
                    alert.score(),
                    alert.band(),
                    alert.decision(),
                    alert.state(),
                    alert.summary(),
                    alert.claimedBy(),
                    alert.claimedAt(),
                    alert.closedBy(),
                    alert.closedAt(),
                    alert.resolution(),
                    alert.resolutionNote(),
                    alert.createdAt(),
                    alert.updatedAt());
        }

        /**
         * How many decimal places this currency has, for rendering an amount that arrived as minor units.
         *
         * <p>{@link java.util.Currency#getDefaultFractionDigits()} rather than a hardcoded 2, for the
         * same reason the money type parses rather than multiplies by 100: JPY has none, and rendering
         * 1000 JPY as "1000.00" is a rounding error in a fraud review.
         */
        private static int fractionDigits(String currencyCode) {
            return java.util.Currency.getInstance(currencyCode).getDefaultFractionDigits();
        }
    }

    /** One entry in an alert's timeline. */
    public record AlertEventResponse(
            UUID id,
            FraudAlertEventEntity.AlertEventAction action,
            String actorDigest,
            String note,
            Instant occurredAt) {

        public static AlertEventResponse of(FraudAlertEventEntity event) {
            return new AlertEventResponse(
                    event.id(), event.action(), event.actorDigest(), event.note(), event.occurredAt());
        }
    }

    /** One alert with its timeline, which is what the detail view returns. */
    public record AlertDetailResponse(AlertResponse alert, List<AlertEventResponse> timeline) {}

    /**
     * Reads a stored document back.
     *
     * <p>Returns an empty document rather than throwing. The stored JSON was written by this service, so
     * unreadable content means the row was corrupted or written by an older version; a decision panel that
     * fails to render because of one bad column is worse than one that renders with no reasons, and the
     * empty result is visible where it matters.
     */
    /**
     * Reads a JSON document stored in a text column, falling back to an empty value of the caller's type.
     *
     * <p><b>The empty value is a parameter, not a guess.</b> The two call sites want different ones — an
     * empty <em>object</em> is not a valid empty <em>list</em>, so a helper that hardcoded one would have
     * turned a payment with no fired rules into a 500 instead of a response with an empty list. Whoever
     * adds a third column says which shape an absent value means, at the call site where it is known.
     *
     * <p>A malformed stored document is also treated as absent rather than failing the request. These
     * columns are written by this service and read by staff; a decision that cannot be explained is
     * still a decision, and refusing to show it because one reason string would not parse would hide the
     * one thing an analyst opened the page to see. The unparseable text is not shown and the fallback
     * stands, which is recorded rather than silent because the alternative — rendering raw JSON into a
     * page that renders rule explanations — is a stored-XSS surface.
     */
    private static <T> T read(ObjectMapper json, String document, TypeReference<T> type, String emptyValue) {
        if (document == null || document.isBlank()) {
            return parse(json, emptyValue, type);
        }
        try {
            return json.readValue(document, type);
        } catch (JsonProcessingException e) {
            return parse(json, emptyValue, type);
        }
    }

    private static <T> T parse(ObjectMapper json, String document, TypeReference<T> type) {
        try {
            return json.readValue(document, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fallback document is not valid JSON: " + document, e);
        }
    }
}
