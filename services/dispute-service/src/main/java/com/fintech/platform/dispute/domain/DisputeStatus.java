package com.fintech.platform.dispute.domain;

/**
 * Where a dispute stands.
 *
 * <p>Three states, and the missing transitions are the design: a resolved dispute never reopens,
 * and evidence lands only on an open one. A chargeback case that can be reopened is a refund that
 * can be re-decided, and a case file that accepts evidence after the decision is a file that was
 * decided before it was complete.
 */
public enum DisputeStatus {
    OPEN,
    RESOLVED_REFUNDED,
    RESOLVED_REJECTED;

    /** True once the case has an outcome, whichever one. */
    public boolean isResolved() {
        return this != OPEN;
    }
}
