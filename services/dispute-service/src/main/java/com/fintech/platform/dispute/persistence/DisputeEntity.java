package com.fintech.platform.dispute.persistence;

import com.fintech.platform.dispute.domain.DisputeReason;
import com.fintech.platform.dispute.domain.DisputeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A chargeback case: a customer's grievance about a payment, and the platform's decision on it.
 *
 * <p>The lifecycle is one way. A case opens, gathers evidence while open, and resolves exactly once
 * — to a refund or a rejection — after which the row is history. There is no reopen, no edit of the
 * grievance, and no second outcome: a case that can be re-decided is a refund that can be
 * un-refunded, and the ledger downstream does not do that.
 */
@Entity
@Table(name = "disputes")
public class DisputeEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private DisputeReason reason;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DisputeStatus status;

    @Column(name = "opened_by_subject", nullable = false, length = 512)
    private String openedBySubject;

    @Column(name = "resolved_by_subject", length = 512)
    private String resolvedBySubject;

    @Column(name = "resolution", length = 2000)
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected DisputeEntity() {}

    private DisputeEntity(
            UUID id, UUID transactionId, DisputeReason reason, String description, String openedBy, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.description = requireText(description, "description", 2000);
        this.status = DisputeStatus.OPEN;
        this.openedBySubject = requireText(openedBy, "openedBySubject", 512);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static DisputeEntity open(
            UUID transactionId, DisputeReason reason, String description, String openedBy, Instant now) {
        return new DisputeEntity(UUID.randomUUID(), transactionId, reason, description, openedBy, now);
    }

    /**
     * Resolves the case, once.
     *
     * <p>Refuses a case that is not open, because resolving twice would announce two outcomes for
     * one grievance and the downstream ledger would be asked to refund twice.
     */
    public void resolve(boolean refund, String resolvedBy, String resolution, Instant now) {
        if (status != DisputeStatus.OPEN) {
            throw new IllegalStateException("dispute " + id + " is already " + status);
        }
        this.status = refund ? DisputeStatus.RESOLVED_REFUNDED : DisputeStatus.RESOLVED_REJECTED;
        this.resolvedBySubject = requireText(resolvedBy, "resolvedBySubject", 512);
        this.resolution = requireText(resolution, "resolution", 2000);
        this.resolvedAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    /** Touches the row when evidence lands, so the case sorts by last activity. */
    public void noted(Instant now) {
        if (status != DisputeStatus.OPEN) {
            throw new IllegalStateException(
                    "dispute " + id + " is " + status + "; evidence lands only on an open case");
        }
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public DisputeReason getReason() {
        return reason;
    }

    public String getDescription() {
        return description;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public String getOpenedBySubject() {
        return openedBySubject;
    }

    public String getResolvedBySubject() {
        return resolvedBySubject;
    }

    public String getResolution() {
        return resolution;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
