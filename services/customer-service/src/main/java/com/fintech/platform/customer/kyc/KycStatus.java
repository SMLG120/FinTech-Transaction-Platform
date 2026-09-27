package com.fintech.platform.customer.kyc;

/**
 * Where a customer's identity check has got to.
 *
 * <p>The set is small on purpose. Every state here is a decision somebody has to be able to explain to
 * a regulator, and {@link #EXPIRED} exists because an approval that nobody re-checks is an approval
 * that silently outlives the evidence behind it.
 *
 * <p>Transitions are not a property of the enum. A status that can legally become any other status is
 * not a state machine, it is a field, and the illegal ones are found in production rather than in a
 * test. The legal moves live in {@link KycStateMachine}.
 */
public enum KycStatus {
    /** Registered, nothing submitted. The starting state. */
    NOT_STARTED,

    /** Submitted and waiting for a provider decision. */
    PENDING_REVIEW,

    /** A reviewer or the provider is actively assessing the submission. */
    UNDER_REVIEW,

    /** Verified. The customer may hold a card. */
    APPROVED,

    /** Refused. Carries a reason, since "no" is not an acceptable answer to a customer. */
    REJECTED,

    /** Previously approved, now past the point where the evidence is still current. */
    EXPIRED
}
