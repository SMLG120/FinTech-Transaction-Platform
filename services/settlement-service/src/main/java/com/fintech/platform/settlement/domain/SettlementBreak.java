package com.fintech.platform.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A reconciliation finding, as a row rather than an exception.
 *
 * <p>Two decisions are encoded here. First, a difference is recorded rather than raised: the money has
 * already moved, so the platform's job is to record the gap accurately and escalate it, and a service
 * that refuses to record it has decided the money agrees before anybody checked. Second, acknowledgement
 * and resolution are separate states, because "somebody has seen this" and "this is explained" are
 * different facts, and the audit question a regulator asks is the second one.
 */
@Entity
@Table(name = "settlement_breaks")
public class SettlementBreak {

    /** The longest {@code detail} and {@code resolution} the schema accepts, as a guard against unbounded input. */
    private static final int MAX_TEXT = 500;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private SettlementCycle cycle;

    /** The transaction the break is about, where there is one. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private BreakKind kind;

    @Column(name = "expected_minor")
    private Long expectedMinor;

    @Column(name = "actual_minor")
    private Long actualMinor;

    /** Signed: positive when the actual exceeded the expected total. */
    @Column(name = "difference_minor", nullable = false)
    private long differenceMinor;

    @Column(name = "detail", nullable = false, length = MAX_TEXT)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BreakStatus status;

    /** Who saw it. Not an explanation. */
    @Column(name = "acknowledged_by", length = 64)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolution", length = MAX_TEXT)
    private String resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected SettlementBreak() {}

    private SettlementBreak(
            SettlementCycle cycle,
            BreakKind kind,
            UUID transactionId,
            Long expectedMinor,
            Long actualMinor,
            long differenceMinor,
            String detail,
            Instant now) {
        this.id = UUID.randomUUID();
        this.cycle = cycle;
        this.kind = kind;
        this.transactionId = transactionId;
        this.expectedMinor = expectedMinor;
        this.actualMinor = actualMinor;
        this.differenceMinor = differenceMinor;
        this.detail = requireText(detail, "detail");
        this.status = BreakStatus.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Records that a declared actual did not match the cycle's expected total.
     *
     * @param cycle the broken cycle
     * @param expected the computed expected total
     * @param actual the declared actual
     * @param now the current instant
     * @return the open break
     */
    public static SettlementBreak amountMismatch(SettlementCycle cycle, Money expected, Money actual, Instant now) {
        long difference = actual.minorUnits() - expected.minorUnits();
        String detail = "declared actual " + actual + " differs from the expected total " + expected + " by "
                + difference + " minor units";
        return new SettlementBreak(
                cycle,
                BreakKind.AMOUNT_MISMATCH,
                null,
                expected.minorUnits(),
                actual.minorUnits(),
                difference,
                detail,
                now);
    }

    /**
     * Records a refund for a payment this service never saw settle.
     *
     * <p>Not recorded as a line with no counterpart. A statement that nets a refund against nothing is a
     * statement whose total cannot be explained, and the orphan is a real finding: the capture event was
     * lost, or the payment predates this service, or it was never a payment. None of those can be
     * resolved by guessing.
     *
     * @param cycle the cycle covering the refund's business date
     * @param transactionId the payment that was supposedly refunded
     * @param amount the refund amount
     * @param now the current instant
     * @return the open break
     */
    public static SettlementBreak orphanReversal(SettlementCycle cycle, UUID transactionId, Money amount, Instant now) {
        String detail = "reversal of " + amount + " for transaction " + transactionId + ", which this service "
                + "never saw settle. Either the capture event was lost, or the payment predates settlement, or "
                + "it was never a payment; none of those can be resolved by posting the refund anyway";
        return new SettlementBreak(
                cycle, BreakKind.ORPHAN_REVERSAL, transactionId, null, null, amount.minorUnits(), detail, now);
    }

    /**
     * Records a movement that arrived for a period which had already been closed and given out.
     *
     * <p>Covers a capture and a refund, because the problem is the same either way: the money moved, the
     * statement is immutable, and the line cannot be added. The {@code movement} phrase is passed in rather
     * than derived so the detail names what actually arrived — "capture of 500.00" reads differently to
     * somebody triaging a queue than "movement of 500.00" does, and the whole purpose of the detail is to
     * be read at 3am by someone who does not have the event log open.
     *
     * <p>No line is written for a movement recorded this way. The alternative — reopening the period — is
     * the thing ADR-0009 rejects: it keeps a total equal to the sum of its lines by making the statement
     * true only until the next write.
     *
     * @param cycle the closed period the movement arrived for
     * @param transactionId the payment it concerns
     * @param amount the amount, signed
     * @param movement how to describe what arrived, such as {@code "capture"} or {@code "refund"}
     * @param settledReference the reference of the period the payment settled in, or null when unknown
     * @param now the current instant
     * @return the open break
     */
    public static SettlementBreak closedPeriodMovement(
            SettlementCycle cycle,
            UUID transactionId,
            Money amount,
            String movement,
            String settledReference,
            Instant now) {
        String origin = settledReference == null || settledReference.isBlank()
                ? "a period this service has not identified"
                : settledReference;
        String detail = movement + " of " + amount + " for transaction " + transactionId + " arrived for cycle "
                + cycle.getReference() + ", which was already closed and given out. The line was not added, because "
                + "a statement that changes after it is sent is not a statement, and the payment settled in " + origin
                + ". The money moved and no statement will show it, which is what this row records: either the "
                + "period must be accounted for manually, or the movement belongs to a later cycle";
        return new SettlementBreak(
                cycle, BreakKind.PERIOD_ALREADY_CLOSED, transactionId, null, null, amount.minorUnits(), detail, now);
    }

    /**
     * Records that somebody has seen this break.
     *
     * <p>Does not resolve it. The separation is the useful part of the model: acknowledgement says a
     * person looked, resolution says the money is accounted for, and a single state would force the
     * platform to record the first as though it were the second.
     *
     * @param actor who is acknowledging it
     * @param now the current instant
     * @throws IllegalStateException if the break is already resolved
     */
    public void acknowledge(String actor, Instant now) {
        if (status == BreakStatus.RESOLVED) {
            throw new IllegalStateException("break on " + cycle.getReference() + " is already resolved and cannot "
                    + "be acknowledged again; a resolved finding is a closed question, and reopening it would "
                    + "let the audit trail show a question being asked twice");
        }
        if (status == BreakStatus.ACKNOWLEDGED) {
            // Re-acknowledging is a no-op rather than an error: two operators looking at the same break is
            // ordinary, and refusing the second makes the UI look broken rather than making it honest.
            return;
        }
        this.status = BreakStatus.ACKNOWLEDGED;
        this.acknowledgedBy = actor;
        this.acknowledgedAt = now;
        this.updatedAt = now;
    }

    /**
     * Records that the money is accounted for, and why.
     *
     * @param explanation what was found and what was done
     * @param now the current instant
     * @throws IllegalArgumentException if the explanation is blank
     * @throws IllegalStateException if nobody has acknowledged the break
     */
    public void resolve(String explanation, Instant now) {
        if (status == BreakStatus.OPEN) {
            throw new IllegalStateException("break on " + cycle.getReference() + " cannot be resolved before it "
                    + "has been acknowledged. Somebody has to have looked at it, and if resolution is allowed "
                    + "from OPEN then the model cannot tell that looking happened.");
        }
        if (status == BreakStatus.RESOLVED) {
            throw new IllegalStateException("break on " + cycle.getReference() + " is already resolved");
        }
        this.resolution = requireText(explanation, "resolution");
        this.status = BreakStatus.RESOLVED;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    /**
     * Refuses blank or oversized text, rather than storing it.
     *
     * @param text the text to check
     * @param field the field's name, for the message
     * @return the text
     */
    private static String requireText(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " is required: a break with no explanation is a break "
                    + "that will be re-opened by the next person who looks at it");
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException(
                    field + " must be at most " + MAX_TEXT + " characters, got " + trimmed.length());
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public SettlementCycle getCycle() {
        return cycle;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public BreakKind getKind() {
        return kind;
    }

    public Long getExpectedMinor() {
        return expectedMinor;
    }

    public Long getActualMinor() {
        return actualMinor;
    }

    public long getDifferenceMinor() {
        return differenceMinor;
    }

    public String getDetail() {
        return detail;
    }

    public BreakStatus getStatus() {
        return status;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return kind + "[" + status + ", diff=" + differenceMinor + "] on " + cycle.getReference();
    }
}
