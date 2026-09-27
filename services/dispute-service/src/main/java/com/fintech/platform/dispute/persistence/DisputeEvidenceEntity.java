package com.fintech.platform.dispute.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One statement in a case file, in the order it was made.
 *
 * <p>Write-once: no setter, no update path. The file is the conversation rather than its latest
 * edit, and a statement that changes after the decision is a statement that was different when it
 * was decided on.
 */
@Entity
@Table(name = "dispute_evidence")
public class DisputeEvidenceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "dispute_id", nullable = false)
    private UUID disputeId;

    @Column(name = "submitted_by_subject", nullable = false, length = 512)
    private String submittedBySubject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected DisputeEvidenceEntity() {}

    private DisputeEvidenceEntity(UUID id, UUID disputeId, String submittedBy, String body, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.disputeId = Objects.requireNonNull(disputeId, "disputeId must not be null");
        if (submittedBy == null || submittedBy.isBlank()) {
            throw new IllegalArgumentException("submittedBySubject must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("evidence body must not be blank");
        }
        if (body.length() > 4000) {
            throw new IllegalArgumentException("evidence body exceeds 4000 characters");
        }
        this.submittedBySubject = submittedBy;
        this.body = body;
        this.submittedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static DisputeEvidenceEntity submit(UUID disputeId, String submittedBy, String body, Instant now) {
        return new DisputeEvidenceEntity(UUID.randomUUID(), disputeId, submittedBy, body, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public String getSubmittedBySubject() {
        return submittedBySubject;
    }

    public String getBody() {
        return body;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
