package com.fintech.platform.fraud.scoring;

import com.fintech.platform.fraud.domain.RiskAssessment;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RiskScore;
import com.fintech.platform.fraud.domain.RuleFinding;
import com.fintech.platform.fraud.domain.RuleOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every rule against a feature and turns what they found into an assessment.
 *
 * <p>Pure and synchronous. It reads no clock, opens no connection, and knows nothing about how a feature
 * was collected — which is what lets the rules, the policy and this class be tested without a container,
 * and what lets the same code score a live event and a replayed one.
 *
 * <p><b>Every rule is asked, and every rule is recorded, including the ones that did not fire.</b> A
 * decision that lists only what fired reads as "nothing else was wrong", which is not what was found. The
 * rules that declined are collected separately and travel in the assessment's facts, so an analyst
 * comparing a MEDIUM with an expected HIGH can see that the velocity rule had nothing to say because its
 * counter was unavailable, rather than having to guess.
 *
 * <p><b>One rule throwing does not lose the other six.</b> A rule that fails is a bug or a broken
 * dependency, and the honest response to a payment that looks like account takeover is not to record
 * nothing. The exception is logged with the rule's id, counted, and the rule is treated as not having
 * fired, with that recorded in the facts. The payment still gets a score, an alert if the total warrants
 * one, and a visible marker that the score is incomplete.
 */
@Component
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final List<RiskRule> rules;

    private final DecisionPolicy policy;

    public RiskEngine(List<RiskRule> rules, DecisionPolicy policy) {
        // Sorted by id so the reasons list is in a stable order for a given set of rules. A list whose
        // order depends on Spring's bean discovery order makes a decision's reasons shuffle between
        // restarts, which looks in a diff like the engine changed its mind.
        this.rules = rules.stream().sorted(Comparator.comparing(RiskRule::id)).toList();
        this.policy = policy;
    }

    /** The rules in this engine, for the health endpoint and for tests that assert the whole set. */
    public List<RiskRule> rules() {
        return rules;
    }

    public RiskAssessment evaluate(RiskFeature feature) {
        List<RuleOutcome> fired = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (RiskRule rule : rules) {
            Optional<RuleFinding> finding;
            try {
                finding = rule.evaluate(feature);
            } catch (RuntimeException e) {
                // Deliberately caught per rule. A rule that throws on one payment's shape must not take
                // the other six with it, and must not leave the payment unscored.
                log.error("Rule {} ({}) failed for transaction {}", rule.id(), rule.name(), feature.transactionId(), e);
                failed.add(rule.id());
                continue;
            }
            if (finding.isPresent()) {
                fired.add(new RuleOutcome(
                        rule.id(),
                        rule.name(),
                        rule.points(),
                        finding.get().explanation(),
                        finding.get().evidence()));
            } else {
                skipped.add(rule.id());
            }
        }

        int rawPoints = fired.stream().mapToInt(RuleOutcome::points).sum();
        RiskScore score = RiskScore.of(rawPoints);
        DecisionPolicy.Verdict verdict = policy.verdict(score, fired);

        return new RiskAssessment(
                feature.transactionId(),
                feature.occurredAt(),
                feature.snapshot().evaluatedAt(),
                feature.amount(),
                feature.amount().currency().getCurrencyCode(),
                feature.facts().ownerSubjectDigest(),
                feature.facts().payeeName(),
                feature.facts().merchantReference(),
                feature.facts().channel(),
                feature.facts().cardReference(),
                feature.facts().deviceReference(),
                feature.facts().networkReference(),
                score,
                verdict.decision(),
                verdict.alertRequired(),
                List.copyOf(fired),
                facts(feature, rawPoints, fired.size(), skipped, failed, verdict));
    }

    /**
     * What the engine looked at, what it concluded from it, and — the part that is easy to omit — what it
     * could not look at.
     *
     * <p>Values are plain strings because this map is rendered verbatim in a decision panel and stored as
     * JSON; anything needing structure belongs in the reasons list, where it has a rule id attached.
     */
    private java.util.Map<String, String> facts(
            RiskFeature feature,
            int rawPoints,
            int firedCount,
            List<String> skipped,
            List<String> failed,
            DecisionPolicy.Verdict verdict) {
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("rulesEvaluated", Integer.toString(rules.size()));
        facts.put("rulesFired", Integer.toString(firedCount));
        facts.put("rawPoints", Integer.toString(rawPoints));
        facts.put("score", Integer.toString(RiskScore.of(rawPoints).value()));
        facts.put("escalatedBySingleSignal", Boolean.toString(verdict.escalatedBySignal()));
        facts.put("velocityCount", Integer.toString(feature.velocity().count()));
        facts.put("velocityWindow", feature.velocity().windowSeconds() + "s");
        facts.put("velocityAvailable", Boolean.toString(feature.velocity().available()));
        facts.put("devicePresent", Boolean.toString(feature.facts().hasDevice()));
        facts.put("deviceSeenOnCard", describe(feature.snapshot().deviceSeenOnCard()));
        facts.put("deviceSeenByCustomer", describe(feature.snapshot().deviceSeenByCustomer()));
        facts.put("otherCustomersOnDevice", Integer.toString(feature.snapshot().otherCustomersOnDevice()));
        facts.put("merchantIdentityPresent", Boolean.toString(feature.facts().hasMerchantIdentity()));
        facts.put("merchantIdentitySource", feature.facts().merchantIdentitySource());
        facts.put("merchantSeen", describe(feature.snapshot().merchantSeen()));
        facts.put("cardPresent", Boolean.toString(feature.facts().hasCard()));
        facts.put("networkPresent", Boolean.toString(feature.facts().hasNetwork()));
        facts.put("networkChangeKnown", Boolean.toString(feature.snapshot().lastNetworkChangedAt() != null));
        if (!skipped.isEmpty()) {
            facts.put("rulesNotFired", String.join(",", skipped));
        }
        if (!failed.isEmpty()) {
            facts.put("rulesFailed", String.join(",", failed));
        }
        return facts;
    }

    /**
     * Three states, not two.
     *
     * <p>{@code null} becomes {@code "unknown"} rather than {@code "false"} because "we could not read the
     * observation" and "the observation says no" lead to different rules firing, and a fact map that
     * flattened them would misreport what the engine knew.
     */
    private String describe(Boolean value) {
        if (value == null) {
            return "unknown";
        }
        return Boolean.toString(value);
    }
}
