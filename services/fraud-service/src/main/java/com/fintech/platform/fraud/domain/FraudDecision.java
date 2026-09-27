package com.fintech.platform.fraud.domain;

/**
 * What the risk engine concluded, as opposed to how risky it thinks the payment is.
 *
 * <p>Two different questions, and the platform answers both. {@link RiskBand} is the measurement;
 * this is the action. Keeping them apart is what lets a threshold move — lowering the point at which a
 * payment is declined does not change what "high risk" means, and an alert raised for a HIGH payment
 * still reads HIGH when the decline threshold has moved above it.
 *
 * <p>{@link #REVIEW} exists because the alternative is a false choice. With only approve and decline, a
 * score of 60 has to be either "let it through" or "refuse a customer who is probably fine", and a
 * fraud team picks the second for every score it does not trust, which is how legitimate customers get
 * refused and then call. Stepping a payment up to a human is the honest answer for the middle of the
 * range, and it is also the state an analyst's queue is built from.
 *
 * <p>What the decision <em>does</em> to the payment is a separate question this phase does not answer:
 * see ADR-0008. The decision is recorded, published and alerted on, and nothing in transaction-service
 * reads it yet.
 */
public enum FraudDecision {

    /** Nothing fired, or what fired is not enough to act on. */
    APPROVE,

    /** Worth a human's time. An alert is raised; the payment is not declined on this evidence alone. */
    REVIEW,

    /** The engine says no. */
    DECLINE;

    /** Whether this decision should stop a payment, as opposed to merely annotate it. */
    public boolean isAdverse() {
        return this == DECLINE;
    }
}
