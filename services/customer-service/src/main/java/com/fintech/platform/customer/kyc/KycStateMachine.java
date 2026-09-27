package com.fintech.platform.customer.kyc;

import com.fintech.platform.customer.error.CustomerErrorCodes;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only legal moves between {@link KycStatus} values.
 *
 * <p>Written as one table rather than as {@code if} statements spread across services, because the
 * question "can a rejected customer resubmit?" has exactly one answer and it needs to be the same
 * answer in the HTTP layer, the event consumer and the batch job. Deriving the table from a single
 * declaration also means the set of states a customer can be in is enumerable, which is what the
 * tests assert against.
 *
 * <p>Two transitions deserve explanation because both look like mistakes otherwise:
 *
 * <ul>
 *   <li>{@code REJECTED -> PENDING_REVIEW} exists. A refusal is a decision about the evidence
 *       submitted, not a permanent verdict on the person, and a customer who fixes a document problem
 *       has to be able to try again. Without this, the only route out of rejection is erasure.
 *   <li>{@code APPROVED -> EXPIRED} is the only edge out of approval that the platform initiates
 *       itself. Nothing in the API may move a customer here on request; a customer cannot expiring
 *       their own approval is the entire reason the state is separate from a timestamp.
 * </ul>
 *
 * <p>There is deliberately no {@code EXPIRED -> APPROVED} shortcut and no path out of
 * {@code APPROVED} other than expiry, so an approved customer is never silently un-approved by a
 * state change that leaves no trace.
 */
public final class KycStateMachine {

    private static final Map<KycStatus, Set<KycStatus>> ALLOWED = buildTable();

    private KycStateMachine() {}

    private static Map<KycStatus, Set<KycStatus>> buildTable() {
        Map<KycStatus, Set<KycStatus>> table = new EnumMap<>(KycStatus.class);
        table.put(KycStatus.NOT_STARTED, EnumSet.of(KycStatus.PENDING_REVIEW));
        table.put(KycStatus.PENDING_REVIEW, EnumSet.of(KycStatus.UNDER_REVIEW, KycStatus.REJECTED, KycStatus.APPROVED));
        table.put(KycStatus.UNDER_REVIEW, EnumSet.of(KycStatus.APPROVED, KycStatus.REJECTED));
        table.put(KycStatus.APPROVED, EnumSet.of(KycStatus.EXPIRED));
        table.put(KycStatus.REJECTED, EnumSet.of(KycStatus.PENDING_REVIEW));
        // Terminal. A customer whose approval lapsed has to be re-verified, and re-verification means
        // starting from a submission rather than resuming one that was closed out months ago.
        table.put(KycStatus.EXPIRED, EnumSet.of(KycStatus.PENDING_REVIEW));
        return Map.copyOf(table);
    }

    /**
     * The statuses that have a declared row.
     *
     * <p>Package-private and separate from {@link #allowedFrom} because an absent row and a
     * deliberately terminal status both surface as an empty set through the public API. Without this,
     * adding a status and forgetting to give it edges would be indistinguishable from choosing to
     * make it terminal, and the test that is supposed to catch exactly that would pass.
     */
    static Set<KycStatus> declaredStatuses() {
        return ALLOWED.keySet();
    }

    /** @return the states reachable from {@code from} in one step */
    public static Set<KycStatus> allowedFrom(KycStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean canTransition(KycStatus from, KycStatus to) {
        return allowedFrom(from).contains(to);
    }

    /**
     * @return {@code to}, so this can be used inline in an assignment
     * @throws com.fintech.platform.common.error.ApiException with
     *     {@link CustomerErrorCodes#KYC_INVALID_TRANSITION} if the move is not legal, naming both
     *     states so the caller can see what the platform thought the state was
     */
    public static KycStatus require(KycStatus from, KycStatus to) {
        if (!canTransition(from, to)) {
            throw CustomerErrorCodes.KYC_INVALID_TRANSITION.exception(
                    "cannot move identity check from " + from + " to " + to,
                    java.util.Map.of("from", from.name(), "to", to.name(), "allowedFrom", allowedFrom(from)));
        }
        return to;
    }
}
