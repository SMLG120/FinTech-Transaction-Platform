package com.fintech.platform.fraud.domain;

import java.util.Map;
import java.util.Objects;

/**
 * What one rule found, in words a human can act on.
 *
 * <p>A finding is the explanation <em>without</em> the arithmetic. The rule knows what it saw and why that
 * matters; it does not need to know its own identifier, its own name or how many points it is worth,
 * because {@code RuleEngine} owns that bookkeeping. Splitting it this way means a rule cannot be written
 * with the wrong id, and a change to the scoring table cannot leave a rule's self-reported points
 * disagreeing with the score that was actually computed.
 *
 * <p><b>The evidence map is for the analyst, not for the client.</b> It carries the observed value next to
 * the threshold it was compared against — {@code amount=6000.00, threshold=5000.00} — because "unusually
 * large" is not a finding an analyst can act on and "6000 against a 5000 threshold" is. It holds only
 * values the engine already holds as digests and numbers; see {@link PaymentFacts} for what those are.
 */
public record RuleFinding(String explanation, Map<String, String> evidence) {

    public RuleFinding {
        Objects.requireNonNull(explanation, "explanation must not be null");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("a finding must explain itself");
        }
        if (explanation.length() > 200) {
            // The reasons list is stored in a column and rendered in a panel. A 400-character sentence is
            // a bug in a rule, and refusing it here names the rule in the stack trace instead of
            // producing a row nobody can display.
            throw new IllegalArgumentException("a finding's explanation is at most 200 characters");
        }
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    /** A finding with no supporting numbers, for a rule whose trigger is a fact rather than a comparison. */
    public static RuleFinding of(String explanation) {
        return new RuleFinding(explanation, Map.of());
    }
}
