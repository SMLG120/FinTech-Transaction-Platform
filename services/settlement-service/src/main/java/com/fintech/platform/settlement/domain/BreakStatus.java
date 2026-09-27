package com.fintech.platform.settlement.domain;

/**
 * How far through triage a break is.
 *
 * <p>Acknowledgement and resolution are separate states, and the separation is the useful part. "Somebody
 * has looked at this" and "this is explained" are different facts about different things, and a single
 * state would force the platform to record the first one as though it were the second. The audit question
 * a regulator actually asks is the second one, and it cannot be answered from a model that has only the
 * first.
 */
public enum BreakStatus {

    /**
     * Nobody has looked at it.
     */
    OPEN,

    /**
     * Somebody has seen it and said who. Not an explanation.
     */
    ACKNOWLEDGED,

    /**
     * The money is accounted for and the explanation is recorded. Terminal.
     */
    RESOLVED
}
