package com.fintech.platform.fraud.domain;

import java.util.Map;
import java.util.Objects;

/**
 * One rule that fired, and what it contributed to the score.
 *
 * <p>This record <em>is</em> the explainability. The score on its own says a payment was risky; this says
 * which of seven rules fired, how many points each was worth, what the engine saw and what it compared it
 * against. An analyst disputing a decision — and analysts do dispute them — is looking at a list of
 * these, and if the list is not complete the decision is not reviewable.
 *
 * <p>Immutable and self-contained: it carries the rule's id, name and points rather than a reference to
 * the rule, because the row it is stored in outlives the code that produced it. A rule renamed next year
 * must not silently change what last month's alerts say they were.
 */
public record RuleOutcome(
        String ruleId, String ruleName, int points, String explanation, Map<String, String> evidence) {

    public RuleOutcome {
        requireText(ruleId, "ruleId");
        requireText(ruleName, "ruleName");
        if (points <= 0) {
            // A rule that fires while contributing nothing is a rule that appears in a decision
            // explanation and does not affect the score. That is always a mistake: either it should not
            // fire, or it should be worth something.
            throw new IllegalArgumentException("rule " + ruleId + " fired while contributing no points");
        }
        Objects.requireNonNull(explanation, "explanation must not be null");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** The one-line form shown in the reasons list. */
    public String reason() {
        return explanation;
    }
}
