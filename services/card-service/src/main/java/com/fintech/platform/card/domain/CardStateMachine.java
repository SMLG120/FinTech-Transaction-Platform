package com.fintech.platform.card.domain;

import com.fintech.platform.card.error.CardErrorCodes;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only legal moves between {@link CardStatus} values.
 *
 * <p>One table rather than {@code if} statements in the service, for the reason
 * {@code KycStateMachine} gives in customer-service: "can a lost card be unfrozen?" has exactly one
 * answer, and it has to be that answer in the HTTP layer, the batch job that expires cards and
 * whatever event consumer arrives in a later phase.
 *
 * <p>The edge that matters is the absent one. {@code LOST} has no route back to {@code ACTIVE}, and
 * that is the real-world rule rather than a simplification: a card someone has physically lost cannot
 * be made safe again, so the only correct answer to a lost card is a replacement, and an endpoint that
 * could unfreeze one would be a security control that the API quietly does not offer. A holder who
 * freezes a card by mistake has {@code FROZEN -> ACTIVE}; a holder who reports it lost has not, and
 * the difference is the whole point of the two states.
 *
 * <p>Both terminal states keep no outgoing edges, so a cancelled or expired card can never be
 * resurrected by a state change that leaves no trace. The replacement is a new card with a new number
 * and a new token, which is what makes the old number traceable as retired rather than merely absent.
 */
public final class CardStateMachine {

    private static final Map<CardStatus, Set<CardStatus>> ALLOWED = buildTable();

    private CardStateMachine() {}

    private static Map<CardStatus, Set<CardStatus>> buildTable() {
        Map<CardStatus, Set<CardStatus>> table = new EnumMap<>(CardStatus.class);
        table.put(
                CardStatus.ACTIVE,
                EnumSet.of(CardStatus.FROZEN, CardStatus.LOST, CardStatus.CANCELLED, CardStatus.EXPIRED));
        table.put(
                CardStatus.FROZEN,
                EnumSet.of(CardStatus.ACTIVE, CardStatus.LOST, CardStatus.CANCELLED, CardStatus.EXPIRED));
        // No edge back to ACTIVE or FROZEN. See the class comment.
        table.put(CardStatus.LOST, EnumSet.of(CardStatus.CANCELLED, CardStatus.EXPIRED));
        table.put(CardStatus.CANCELLED, EnumSet.noneOf(CardStatus.class));
        table.put(CardStatus.EXPIRED, EnumSet.noneOf(CardStatus.class));
        return Map.copyOf(table);
    }

    /**
     * The statuses that have a declared row.
     *
     * <p>Separate from {@link #allowedFrom} for the same reason as its counterpart in customer-service:
     * an absent row and a deliberately terminal status both surface as an empty set, so without this
     * a new status added without edges would be indistinguishable from one deliberately made terminal,
     * and the test meant to catch exactly that would pass.
     */
    static Set<CardStatus> declaredStatuses() {
        return ALLOWED.keySet();
    }

    /** @return the states reachable from {@code from} in one step */
    public static Set<CardStatus> allowedFrom(CardStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean canTransition(CardStatus from, CardStatus to) {
        return allowedFrom(from).contains(to);
    }

    public static boolean isTerminal(CardStatus status) {
        return allowedFrom(status).isEmpty();
    }

    /**
     * @return {@code to}, so this can be used inline in a mutator
     * @throws com.fintech.platform.common.error.ApiException with
     *     {@link CardErrorCodes#CARD_INVALID_TRANSITION} if the move is not legal, naming both states
     *     so the caller can see what the platform thought the state was
     */
    public static CardStatus require(CardStatus from, CardStatus to) {
        if (!canTransition(from, to)) {
            throw CardErrorCodes.CARD_INVALID_TRANSITION.exception(
                    "cannot move card from " + from + " to " + to,
                    Map.of(
                            "from",
                            from.name(),
                            "to",
                            to.name(),
                            "allowedFrom",
                            allowedFrom(from).stream().map(Enum::name).sorted().toList()));
        }
        return to;
    }
}
