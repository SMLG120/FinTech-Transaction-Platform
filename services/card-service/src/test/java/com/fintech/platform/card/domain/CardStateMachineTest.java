package com.fintech.platform.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.common.error.ApiException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CardStateMachine")
class CardStateMachineTest {

    @Nested
    @DisplayName("every status")
    class Coverage {

        @Test
        @DisplayName("has a declared row, so a new status cannot be added and silently left terminal")
        void everyStatusIsDeclared() {
            // The failure this guards against is adding a status, forgetting its edges, and having the
            // "a lost card can never be reactivated" test still pass because the new status was
            // terminal by omission rather than by decision. Comparing against the enum's own values is
            // the only way to notice.
            assertThat(CardStateMachine.declaredStatuses())
                    .containsExactlyInAnyOrderElementsOf(Set.of(CardStatus.values()));
        }

        @Test
        @DisplayName("is either terminal or has at least one way out, so no card is ever stranded")
        void noStatusIsStranded() {
            for (CardStatus status : CardStatus.values()) {
                if (CardStateMachine.isTerminal(status)) {
                    assertThat(CardStateMachine.allowedFrom(status))
                            .as("%s is terminal", status)
                            .isEmpty();
                } else {
                    assertThat(CardStateMachine.allowedFrom(status))
                            .as("%s has somewhere to go", status)
                            .isNotEmpty();
                }
            }
        }

        @Test
        @DisplayName("cannot be resurrected, and the only cycle in the graph is the one freeze/thaw is")
        void terminationIsOneWay() {
            // Freeze and thaw have to be repeatable — a cardholder who freezes a card for a holiday and
            // thaws it on return is the ordinary case — so ACTIVE and FROZEN form a genuine cycle and
            // "no card can ever return to a previous state" would be the wrong thing to assert.
            //
            // The property that actually matters is that nothing else is in a cycle: once a card has
            // been reported lost, cancelled or expired, no path of any length brings it back. Computed
            // by transitive closure over the whole graph rather than by checking edges pairwise, so a
            // multi-step route back to ACTIVE through an intermediate state would also be caught.
            Set<Set<CardStatus>> cycles = stronglyConnectedComponents();
            assertThat(cycles)
                    .filteredOn(c -> c.size() > 1)
                    .containsExactly(Set.of(CardStatus.ACTIVE, CardStatus.FROZEN));
        }

        /** Tarjan's algorithm, in twenty lines, because the edge-wise check is not the property. */
        private Set<Set<CardStatus>> stronglyConnectedComponents() {
            Map<CardStatus, Set<CardStatus>> reach = new EnumMap<>(CardStatus.class);
            for (CardStatus from : CardStatus.values()) {
                Set<CardStatus> seen = EnumSet.noneOf(CardStatus.class);
                Deque<CardStatus> queue = new ArrayDeque<>(CardStateMachine.allowedFrom(from));
                while (!queue.isEmpty()) {
                    CardStatus next = queue.remove();
                    if (seen.add(next)) {
                        queue.addAll(CardStateMachine.allowedFrom(next));
                    }
                }
                reach.put(from, seen);
            }
            Set<Set<CardStatus>> components = new HashSet<>();
            for (CardStatus status : CardStatus.values()) {
                Set<CardStatus> component = EnumSet.noneOf(CardStatus.class);
                for (CardStatus other : CardStatus.values()) {
                    if (reach.get(status).contains(other) && reach.get(other).contains(status)) {
                        component.add(other);
                    }
                }
                components.add(Set.copyOf(component));
            }
            return components;
        }
    }

    @Nested
    @DisplayName("lost")
    class Lost {

        @Test
        @DisplayName("cannot be reactivated, because a card someone has lost is not made safe by asking")
        void cannotReturnToActive() {
            // The single most important edge in this table, and the one a plausible-looking
            // implementation would add. LOST -> FROZEN or LOST -> ACTIVE would let a reported-stolen
            // card be returned to circulation by whoever holds the report permission.
            assertThat(CardStateMachine.allowedFrom(CardStatus.LOST))
                    .doesNotContain(CardStatus.ACTIVE, CardStatus.FROZEN);
        }

        @Test
        @DisplayName("can still be closed or retired, so the record is not left dangling")
        void canStillBeFinished() {
            assertThat(CardStateMachine.allowedFrom(CardStatus.LOST))
                    .containsExactlyInAnyOrder(CardStatus.CANCELLED, CardStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("frozen")
    class Frozen {

        @Test
        @DisplayName("can be thawed, which is the whole difference from lost")
        void canReturnToActive() {
            assertThat(CardStateMachine.canTransition(CardStatus.FROZEN, CardStatus.ACTIVE))
                    .isTrue();
        }

        @Test
        @DisplayName("can be reported lost or closed from a freeze, so a cardholder is never trapped")
        void canStillEscalate() {
            // A customer who freezes a card and then decides to close it must not have to thaw it
            // first. Forbidding this would mean the only route out of a freeze is a two-step dance, and
            // the second step can fail.
            assertThat(CardStateMachine.allowedFrom(CardStatus.FROZEN))
                    .contains(CardStatus.LOST, CardStatus.CANCELLED, CardStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminal {

        @Test
        @DisplayName("have no way out at all")
        void haveNoEdges() {
            assertThat(CardStateMachine.isTerminal(CardStatus.CANCELLED)).isTrue();
            assertThat(CardStateMachine.isTerminal(CardStatus.EXPIRED)).isTrue();
            assertThat(CardStateMachine.allowedFrom(CardStatus.CANCELLED)).isEmpty();
            assertThat(CardStateMachine.allowedFrom(CardStatus.EXPIRED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("require")
    class Require {

        @Test
        @DisplayName("passes a legal move through and returns the target")
        void allowsLegalMove() {
            assertThat(CardStateMachine.require(CardStatus.ACTIVE, CardStatus.FROZEN))
                    .isEqualTo(CardStatus.FROZEN);
        }

        @Test
        @DisplayName("rejects an illegal move with the code the HTTP layer maps to 409")
        void rejectsIllegalMove() {
            assertThatThrownBy(() -> CardStateMachine.require(CardStatus.LOST, CardStatus.ACTIVE))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(CardErrorCodes.CARD_INVALID_TRANSITION);
        }

        @Test
        @DisplayName("names both states and what was allowed, so the caller can see what the platform thought")
        void explainsTheRefusal() {
            // "Invalid transition" alone is unactionable for a client author: they cannot tell whether
            // they sent the wrong state, the platform lost an update, or the card moved underneath them.
            assertThatThrownBy(() -> CardStateMachine.require(CardStatus.CANCELLED, CardStatus.ACTIVE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException api = (ApiException) e;
                        assertThat(api.getDetails())
                                .containsEntry("from", "CANCELLED")
                                .containsEntry("to", "ACTIVE");
                        assertThat(api.getDetails().get("allowedFrom")).isEqualTo(java.util.List.of());
                    });
        }
    }
}
