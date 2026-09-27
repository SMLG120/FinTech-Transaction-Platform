package com.fintech.platform.fraud.domain;

import java.util.Optional;

/**
 * One condition that makes a payment look wrong, and what that is worth.
 *
 * <p>Implemented as an ordinary Spring bean with an ordinary method, not as a lambda in a list or a
 * class annotated {@code @Rule}. The registration is by bean type, so adding a rule means adding a class
 * with an id and a {@code points()} method and nothing else — there is no registry to update, no
 * annotation to remember, and no way for a rule to exist without being scored.
 *
 * <p><b>Rules are stateless and hold no per-payment state.</b> Everything a rule reads arrives in
 * {@link RiskFeature}, which the collector has already gathered. A rule that cached a counter or a
 * "seen devices" set would be a rule whose behaviour depended on which consumer thread ran it and
 * whether the service had been restarted, and a fraud rule that gives different answers for the same
 * payment depending on that is not a rule.
 *
 * <p><b>Points are code, not configuration.</b> The thresholds a rule compares against are
 * configuration, because those legitimately differ per deployment. The points are not, because the score's
 * bands ({@code 0–25 LOW}, {@code 76+ CRITICAL}) are a published promise about what a number means. A
 * tunable that lets a deployment double one rule's weight makes every published band wrong in that
 * deployment, and nothing in the response would say so.
 *
 * <p><b>A rule returns nothing when it does not fire</b>, and must not throw when the facts it needs are
 * missing. An absent device fingerprint is not an error; it is a payment that cannot be judged on
 * device, and the decision's facts record that it was not.
 */
public interface RiskRule {

    /**
     * A stable identifier, stored on every decision and shown to analysts.
     *
     * <p>{@code R0nn} so it sorts in firing order and so a name change does not orphan the history: the
     * id is what a support ticket quotes.
     */
    String id();

    /** A human-readable name, for the dashboard and the alert. */
    String name();

    /** How much this rule contributes when it fires. */
    int points();

    /**
     * @param feature everything the engine knows about this payment
     * @return what was found, or empty when the rule did not fire
     */
    Optional<RuleFinding> evaluate(RiskFeature feature);
}
