package com.fintech.platform.dispute.web;

import com.fintech.platform.dispute.persistence.DisputeEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * What the dispute API returns.
 *
 * <p>The case as the parties see it: the grievance, its state, who opened and who decided, and the
 * file. Amounts never appear because this service never learns them — the eligibility check reads
 * the payment's figure to confirm it is settled and keeps nothing. An amount this service recorded
 * would be a second ledger disagreeing with the first at the worst possible moment: during a fight
 * about money.
 */
public final class DisputeResponses {

    private DisputeResponses() {}

    /** One case. */
    public record DisputeView(
            UUID id,
            UUID transactionId,
            String reason,
            String description,
            String status,
            int evidenceCount,
            String resolvedBy,
            String resolution,
            Instant createdAt,
            Instant resolvedAt) {

        /**
         * Renders a case.
         *
         * @param dispute the case
         * @param evidenceCount how many statements its file holds
         * @return the view
         */
        public static DisputeView of(DisputeEntity dispute, int evidenceCount) {
            return new DisputeView(
                    dispute.getId(),
                    dispute.getTransactionId(),
                    dispute.getReason().name(),
                    dispute.getDescription(),
                    dispute.getStatus().name(),
                    evidenceCount,
                    dispute.getResolvedBySubject(),
                    dispute.getResolution(),
                    dispute.getCreatedAt(),
                    dispute.getResolvedAt());
        }
    }

    /** One case with its file. */
    public record DisputeDetailView(DisputeView dispute, java.util.List<EvidenceView> evidence) {

        /**
         * Renders a case with its file, in the order it was spoken.
         *
         * @param dispute the case
         * @param file its evidence, already ordered
         * @param callerSubject the reader, so their own statements are marked
         * @return the detail view
         */
        public static DisputeDetailView of(
                DisputeEntity dispute, java.util.List<DisputeEvidenceEntity> file, String callerSubject) {
            return new DisputeDetailView(
                    DisputeView.of(dispute, file.size()),
                    file.stream()
                            .map(statement -> EvidenceView.of(
                                    statement, statement.getSubmittedBySubject().equals(callerSubject)))
                            .toList());
        }
    }

    /** One statement in a case file. */
    public record EvidenceView(UUID id, String body, boolean submittedByMe, Instant submittedAt) {

        /**
         * Renders a statement.
         *
         * <p>The submitter's subject is absent, deliberately — but the caller is told whether the
         * words are theirs. A customer reading the file needs to tell their own statements from
         * the staff's, and a boolean answers that without naming anyone: no per-statement roster
         * of everyone who touched the case travels in the response.
         */
        public static EvidenceView of(DisputeEvidenceEntity statement, boolean submittedByMe) {
            return new EvidenceView(statement.getId(), statement.getBody(), submittedByMe, statement.getSubmittedAt());
        }
    }
}
