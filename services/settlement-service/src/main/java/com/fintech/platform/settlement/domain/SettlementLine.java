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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One movement of money on a statement.
 *
 * <p>A line is either a capture or a refund of a capture, and it is a signed amount rather than a
 * positive amount plus a direction flag. That is deliberate: a statement sums one column, and a refund
 * is a movement in the opposite direction rather than a special case the sum has to know about. The
 * kind is kept as well, not instead, because "what moved on the 14th" is asked by people and by reports
 * long after the sign has been folded into a total.
 *
 * <p>It carries both the transaction it is about and the cycle it belongs to, and those are not the same
 * for a refund of a payment from an earlier period — {@code cycle} is the period being settled,
 * {@code transactionId} is the payment being reversed. Keeping them as separate columns rather than
 * deriving the cycle from the transaction is what allows a closed statement to stay closed.
 */
@Entity
@Table(name = "settlement_lines")
public class SettlementLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private SettlementCycle cycle;

    /** The payment this line is about. See the class comment on why this is not the cycle. */
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private SettlementLineKind kind;

    /** The signed amount. See the class comment. */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected SettlementLine() {}

    private SettlementLine(
            SettlementCycle cycle, UUID transactionId, SettlementLineKind kind, long amountMinor, Instant now) {
        this.id = UUID.randomUUID();
        this.cycle = cycle;
        this.transactionId = transactionId;
        this.kind = kind;
        this.amountMinor = amountMinor;
        this.businessDate = cycle.getBusinessDate();
        this.createdAt = now;
    }

    /**
     * Records a capture into an open cycle.
     *
     * @param cycle the cycle to add to, which must be open
     * @throws IllegalStateException if the cycle is closed, or the amount's sign contradicts the kind
     * @param transactionId the settled payment
     * @param amount the payment's amount, which must not be negative
     * @param now the current instant
     * @return the new line
     * @throws IllegalStateException if the cycle is not open, or the amount's sign contradicts the kind
     */
    public static SettlementLine capture(SettlementCycle cycle, UUID transactionId, Money amount, Instant now) {
        cycle.requireOpenForLine();
        requireKindSign(SettlementLineKind.CAPTURE, amount);
        requireSameCycleCurrency(cycle, amount, "capture");
        return new SettlementLine(cycle, transactionId, SettlementLineKind.CAPTURE, amount.minorUnits(), now);
    }

    /**
     * Records a refund into an open cycle.
     *
     * <p>The cycle passed here covers the <em>refund's</em> business date, not the original payment's.
     * That is the cross-cycle case, and passing the wrong cycle is the mistake this method's contract
     * exists to make hard: the line would look correct and the statement for the wrong period would be
     * short. The caller negates the original amount; this method will not do it silently, because a
     * reversal that quietly corrected its own sign would make the sign untestable.
     *
     * @param cycle the cycle covering the refund's own business date
     * @param transactionId the payment being refunded, possibly settled in an earlier cycle
     * @param amount the refund amount, which must be negative
     * @param now the current instant
     * @return the new line
     * @throws IllegalStateException if the cycle is not open, or the amount's sign contradicts the kind
     */
    public static SettlementLine reversal(SettlementCycle cycle, UUID transactionId, Money amount, Instant now) {
        cycle.requireOpenForLine();
        requireKindSign(SettlementLineKind.REVERSAL, amount);
        requireSameCycleCurrency(cycle, amount, "reversal");
        return new SettlementLine(cycle, transactionId, SettlementLineKind.REVERSAL, amount.minorUnits(), now);
    }

    /**
     * The line's amount, as money in its cycle's currency.
     *
     * @return the signed amount
     */
    public Money amount() {
        return new Money(amountMinor, cycle.currency());
    }

    /**
     * Refuses an amount whose sign contradicts the line kind.
     *
     * <p>Checked here rather than trusted from the event. A reversal arriving with a positive amount is a
     * consumer bug, and finding it at the line is far clearer than finding it in a statement total that
     * is short by twice the refund.
     *
     * @param kind the kind being created
     * @param amount the amount
     */
    private static void requireKindSign(SettlementLineKind kind, Money amount) {
        if (!kind.agreesWithSign(amount.minorUnits())) {
            throw new IllegalStateException("a " + kind + " line must be "
                    + (kind == SettlementLineKind.CAPTURE ? "non-negative" : "non-positive") + ", but the amount is "
                    + amount + ". The sign is not decoration: it is how a statement total knows a refund is a "
                    + "refund, and a reversal recorded as positive would credit the period instead of "
                    + "debiting it.");
        }
    }

    /**
     * Refuses a line whose currency is not its cycle's.
     *
     * @param cycle the cycle
     * @param amount the amount
     * @param what the line kind, for the message
     */
    private static void requireSameCycleCurrency(SettlementCycle cycle, Money amount, String what) {
        if (!cycle.currency().equals(amount.currency())) {
            throw new IllegalArgumentException(
                    "cannot add a " + what + " in " + amount.currency().getCurrencyCode() + " to "
                            + cycle.getReference() + ", which settles in " + cycle.getCurrencyCode());
        }
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

    public SettlementLineKind getKind() {
        return kind;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return kind + " " + transactionId + " amount=" + amount();
    }
}
