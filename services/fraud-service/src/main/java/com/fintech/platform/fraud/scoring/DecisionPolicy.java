package com.fintech.platform.fraud.scoring;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskScore;
import com.fintech.platform.fraud.domain.RuleOutcome;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a score into a decision, and a decision into an instruction to alert a human.
 *
 * <p>Kept separate from the engine and from the rules because this is the part a fraud team argues about.
 * The rules decide what happened; this decides what to do about it. They change for different reasons and
 * at different rates — a new signal is a rule, a new appetite for risk is a threshold here — and putting
 * them in the same class would make every appetite change a code change to a scoring algorithm.
 *
 * <p><b>The band does not decide anything.</b> It is possible to write this as a lookup on
 * {@link com.fintech.platform.fraud.domain.RiskBand} and it would be a mistake: the decision thresholds
 * are configurable, so a deployment can put the decline boundary at 85 and the band boundary stays at 76.
 * Coupling them would make a configuration change silently redefine what a published band name means.
 *
 * <p><b>One strong rule can escalate on its own.</b> Below {@code high-signal-points} a single rule
 * contributes to a total and cannot raise a payment by itself. At or above it, the rule is treated as
 * sufficient: a burst of six payments in a minute (R002, 35) is worth an analyst's time even if each
 * payment is small and nothing else fired. Without this the total is the only thing that matters, and the
 * reasons list can show a genuinely alarming finding on a row that was approved for no visible reason.
 *
 * <p>Which rules qualify is a consequence of the two numbers rather than a list written anywhere: the
 * default floor of 30 puts R001, R002 and R004 above it and R003, R005, R006 and R007 below it. A shared
 * device (R006, 25) therefore does <em>not</em> escalate alone and needs a second signal, which is
 * deliberate — a device legitimately shared between members of one household would otherwise open an
 * alert on every payment any of them made. Change the floor and that set changes with it, so
 * {@code high-signal-points} should be read as part of the scoring table rather than as a separate dial.
 */
@Component
public class DecisionPolicy {

    private final FraudProperties.Decisions config;

    public DecisionPolicy(FraudProperties properties) {
        this.config = properties.getDecisions();
    }

    /**
     * The decision and whether it must raise an alert.
     *
     * @param score the capped total
     * @param reasons the rules that fired, which may escalate on their own
     */
    public Verdict verdict(RiskScore score, List<RuleOutcome> reasons) {
        boolean escalatedBySignal = hasHighSignal(reasons);
        FraudDecision decision;
        if (score.value() >= config.getDeclineThreshold()) {
            decision = FraudDecision.DECLINE;
        } else if (score.value() >= config.getReviewThreshold() || escalatedBySignal) {
            decision = FraudDecision.REVIEW;
        } else {
            decision = FraudDecision.APPROVE;
        }
        boolean alertRequired =
                decision != FraudDecision.APPROVE || score.value() >= config.getAlertThreshold() || escalatedBySignal;
        return new Verdict(decision, alertRequired, escalatedBySignal);
    }

    /**
     * Whether any single fired rule is worth a human's attention by itself.
     *
     * <p>{@code >=} rather than {@code >}: "at least this many points" is how a threshold is written when
     * someone means it, and a rule worth exactly the threshold is exactly the case that must not be
     * decided by an off-by-one.
     */
    private boolean hasHighSignal(List<RuleOutcome> reasons) {
        if (config.getHighSignalPoints() <= 0) {
            return false;
        }
        return reasons.stream().anyMatch(outcome -> outcome.points() >= config.getHighSignalPoints());
    }

    /**
     * A decision and the two things that follow from it.
     *
     * @param decision approve, review or decline
     * @param alertRequired whether a human is expected to look
     * @param escalatedBySignal whether a single rule was sufficient, kept for the reason line
     */
    public record Verdict(FraudDecision decision, boolean alertRequired, boolean escalatedBySignal) {}
}
