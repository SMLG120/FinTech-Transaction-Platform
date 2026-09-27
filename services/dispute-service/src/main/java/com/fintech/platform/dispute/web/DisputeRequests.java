package com.fintech.platform.dispute.web;

import com.fintech.platform.dispute.domain.DisputeReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What the dispute API accepts.
 *
 * <p>Lengths mirror the column caps, so a case that fits validation fits the row. Bean validation
 * rejects the absurd at the edge; the domain still enforces its own invariants, because an
 * annotation is a promise about today and the entity is a promise about every future caller.
 */
public final class DisputeRequests {

    private DisputeRequests() {}

    /** Opens a case on a settled payment. */
    public record OpenDisputeRequest(
            @NotNull UUID transactionId,
            @NotNull DisputeReason reason,
            @NotBlank @Size(max = 2000) String description) {}

    /** Appends a statement to an open case file. */
    public record AddEvidenceRequest(
            @NotBlank @Size(max = 4000) String body) {}

    /** Decides a case. A refund moves money downstream; a rejection ends the case with words. */
    public record ResolveDisputeRequest(
            @NotNull Outcome outcome,
            @NotBlank @Size(max = 2000) String resolution) {

        /** The two things a decision can be. No third outcome, and no "pending" — deciding means decided. */
        public enum Outcome {
            REFUND,
            REJECT
        }
    }
}
