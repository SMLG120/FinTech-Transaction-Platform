package com.fintech.platform.settlement.domain;

/**
 * The only two things that happen to money between a payment settling and the next cycle.
 */
public enum SettlementLineKind {

    /**
     * A payment settled into this period.
     *
     * <p>Always positive: a capture of a negative amount is not a capture.
     */
    CAPTURE,

    /**
     * A payment that had settled was refunded.
     *
     * <p>Always negative in gross. A refund is recorded in the cycle covering the <em>refund's</em>
     * business date, not the original payment's, which is what lets a closed statement stay closed. See
     * ADR-0009.
     */
    REVERSAL;

    /**
     * Whether an amount's sign agrees with this kind.
     *
     * <p>Expressed as a kind check rather than a sign check on the amount, because the sign is a
     * consequence and this is the rule. A reversal that arrived with a positive amount would be a bug in
     * the consumer, and finding it here is clearer than finding it in a statement total that is off by
     * twice the refund.
     *
     * @param amountMinor the line's amount in minor units
     * @return true when the sign agrees with the kind
     */
    public boolean agreesWithSign(long amountMinor) {
        return this == CAPTURE ? amountMinor >= 0 : amountMinor <= 0;
    }
}
