package com.fintech.platform.customer.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.error.ErrorCode;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class KycStateMachineTest {

    @Nested
    @DisplayName("the transition table")
    class Table {

        @Test
        @DisplayName("declares a row for every status, so a new status cannot be added without deciding its edges")
        void coversEveryStatus() {
            assertThat(KycStateMachine.declaredStatuses())
                    .as("every status has an explicit row, terminal or not")
                    .containsExactlyInAnyOrder(KycStatus.values());
        }

        @Test
        @DisplayName("lets every undecided state reach a decision, so no customer can get stuck")
        void everyUndecidedStateCanReachADecision() {
            for (KycStatus start : KycStatus.values()) {
                Set<KycStatus> reachable = reachableFrom(start);
                assertThat(reachable.contains(KycStatus.APPROVED) || reachable.contains(KycStatus.REJECTED))
                        .as("from %s a decision must be reachable, but only %s are", start, reachable)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("allows cycles, because re-submission is a cycle and refusing it strands a customer")
        void permitsResubmissionCycles() {
            // Not a defect: PENDING_REVIEW -> REJECTED -> PENDING_REVIEW is the resubmission path, and
            // APPROVED -> EXPIRED -> PENDING_REVIEW is re-verification. An acyclic graph here would mean
            // a rejected customer could never try again with better evidence.
            assertThat(KycStateMachine.canTransition(KycStatus.PENDING_REVIEW, KycStatus.REJECTED))
                    .isTrue();
            assertThat(KycStateMachine.canTransition(KycStatus.REJECTED, KycStatus.PENDING_REVIEW))
                    .isTrue();
        }

        private Set<KycStatus> reachableFrom(KycStatus start) {
            Set<KycStatus> seen = new HashSet<>();
            collect(start, seen);
            return seen;
        }

        private void collect(KycStatus current, Set<KycStatus> seen) {
            if (!seen.add(current)) {
                return;
            }
            for (KycStatus next : KycStateMachine.allowedFrom(current)) {
                collect(next, seen);
            }
        }

        @ParameterizedTest
        @EnumSource(KycStatus.class)
        @DisplayName("never allows a transition to itself")
        void neverSelfTransitions(KycStatus status) {
            assertThat(KycStateMachine.allowedFrom(status)).doesNotContain(status);
        }

        @Test
        @DisplayName("permits re-submission after a rejection, since a refusal is about the evidence")
        void allowsResubmissionAfterRejection() {
            assertThat(KycStateMachine.canTransition(KycStatus.REJECTED, KycStatus.PENDING_REVIEW))
                    .isTrue();
        }

        @Test
        @DisplayName("permits expiry only out of approval, never into it from a live state")
        void expiresOnlyFromApproval() {
            for (KycStatus from : KycStatus.values()) {
                if (from == KycStatus.APPROVED) {
                    continue;
                }
                assertThat(KycStateMachine.allowedFrom(from))
                        .as("EXPIRED reachable from %s", from)
                        .doesNotContain(KycStatus.EXPIRED);
            }
        }

        @Test
        @DisplayName("cannot skip straight to approval without a submission, closing the back door around review")
        void cannotSkipReview() {
            assertThat(KycStateMachine.canTransition(KycStatus.NOT_STARTED, KycStatus.APPROVED))
                    .isFalse();
            assertThat(KycStateMachine.canTransition(KycStatus.REJECTED, KycStatus.APPROVED))
                    .isFalse();
            assertThat(KycStateMachine.canTransition(KycStatus.EXPIRED, KycStatus.APPROVED))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("require")
    class Require {

        @Test
        @DisplayName("returns the target state for a legal move")
        void allowsLegalMove() {
            assertThat(KycStateMachine.require(KycStatus.NOT_STARTED, KycStatus.PENDING_REVIEW))
                    .isEqualTo(KycStatus.PENDING_REVIEW);
        }

        @Test
        @DisplayName("rejects an illegal move with the platform's conflict code")
        void rejectsIllegalMove() {
            assertThatThrownBy(() -> KycStateMachine.require(KycStatus.NOT_STARTED, KycStatus.APPROVED))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException api = (ApiException) thrown;
                        assertThat(api.getErrorCode().code()).isEqualTo("KYC_INVALID_TRANSITION");
                        assertThat(api.getErrorCode().httpStatus()).isEqualTo(409);
                    });
        }

        @Test
        @DisplayName("reports what it thought the state was, because a wrong current state is the likely bug")
        void reportsBothStates() {
            assertThatThrownBy(() -> KycStateMachine.require(KycStatus.APPROVED, KycStatus.PENDING_REVIEW))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ErrorCode code = ((ApiException) thrown).getErrorCode();
                        assertThat(code).isNotNull();
                        assertThat(((ApiException) thrown).getMessage())
                                .contains("APPROVED")
                                .contains("PENDING_REVIEW");
                        assertThat(((ApiException) thrown).getDetails())
                                .containsEntry("from", "APPROVED")
                                .containsEntry("to", "PENDING_REVIEW");
                    });
        }

        @Test
        @DisplayName("lists the moves that were available, so the caller is not left guessing")
        void reportsAllowedMoves() {
            assertThatThrownBy(() -> KycStateMachine.require(KycStatus.NOT_STARTED, KycStatus.APPROVED))
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getDetails())
                            .containsEntry("allowedFrom", Set.of(KycStatus.PENDING_REVIEW)));
        }
    }
}
