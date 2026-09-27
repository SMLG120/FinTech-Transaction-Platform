package com.fintech.platform.transaction.domain;

import com.fintech.platform.transaction.error.TransactionErrorCodes;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only legal moves between {@link TransactionStatus} values.
 *
 * <p>One table rather than {@code if} statements in the service, for the reason
 * {@code CardStateMachine} gives: "can an authorised payment be declined?" has exactly one answer, and
 * it has to be that answer in the HTTP layer, in the batch job that releases expired authorisations,
 * and in whatever event consumer arrives in a later phase.
 *
 * <p>The edges that matter are the absent ones. {@code DECLINED} and {@code REVERSED} have no outgoing
 * edges, so a payment that failed or was undone can never be resurrected by a state change that leaves
 * no trace. A payment is not re-decided into a success; it is a new payment with a new id, and the old
 * one stays declined.
 *
 * <p>{@code SETTLED -> REVERSED} exists because a refund after capture is a real event and pretending
 * otherwise would leave a merchant with no way to return money. {@code AUTHORIZED -> REVERSED} exists
 * for the same reason one step earlier: an authorisation that is never captured has to be voidable, and
 * both end in the same status so that "is this payment still live?" is one question with one answer.
 *
 * <p>What is deliberately missing is {@code AUTHORIZED -> SETTLED -> ...} looping, and
 * {@code PENDING -> SETTLED}. A payment cannot skip authorisation, because a settlement of money that
 * was never held is a payment that came from nothing.
 */
public final class TransactionStateMachine {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = buildTable();

    private TransactionStateMachine() {}

    private static Map<TransactionStatus, Set<TransactionStatus>> buildTable() {
        Map<TransactionStatus, Set<TransactionStatus>> table = new EnumMap<>(TransactionStatus.class);
        table.put(TransactionStatus.PENDING, EnumSet.of(TransactionStatus.AUTHORIZED, TransactionStatus.DECLINED));
        table.put(TransactionStatus.AUTHORIZED, EnumSet.of(TransactionStatus.SETTLED, TransactionStatus.REVERSED));
        // A refund after capture. See the class comment.
        table.put(TransactionStatus.SETTLED, EnumSet.of(TransactionStatus.REVERSED));
        // No outgoing edges. See the class comment.
        table.put(TransactionStatus.DECLINED, EnumSet.noneOf(TransactionStatus.class));
        table.put(TransactionStatus.REVERSED, EnumSet.noneOf(TransactionStatus.class));
        return Map.copyOf(table);
    }

    /**
     * The statuses that have a declared row.
     *
     * <p>Separate from {@link #allowedFrom} for the same reason as its counterpart in card-service: an
     * absent row and a deliberately terminal status both surface as an empty set, so without this a
     * new status added without edges would be indistinguishable from one deliberately made terminal,
     * and the test meant to catch exactly that would pass.
     */
    static Set<TransactionStatus> declaredStatuses() {
        return ALLOWED.keySet();
    }

    /** @return the states reachable from {@code from} in one step */
    public static Set<TransactionStatus> allowedFrom(TransactionStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean canTransition(TransactionStatus from, TransactionStatus to) {
        return allowedFrom(from).contains(to);
    }

    public static boolean isTerminal(TransactionStatus status) {
        return allowedFrom(status).isEmpty();
    }

    /**
     * @return {@code to}, so this can be used inline in a mutator
     * @throws com.fintech.platform.common.error.ApiException if the move is not legal
     */
    public static TransactionStatus require(TransactionStatus from, TransactionStatus to) {
        if (!canTransition(from, to)) {
            throw TransactionErrorCodes.invalidStateTransition(from, to);
        }
        return to;
    }
}
