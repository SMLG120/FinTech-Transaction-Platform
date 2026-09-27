package com.fintech.platform.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A settlement cycle: one business date, one currency, and the statement lines belonging to it.
 *
 * <p>This is the unit of immutability in the platform. A transaction is already terminal at
 * {@code REVERSED} in transaction-service, so a refund of money that has already been paid out cannot be
 * expressed by moving the transaction; and a statement that changes after it has been given to somebody
 * cannot be reconciled against, which defeats the reason for having one. So the cycle is what freezes: a
 * closed cycle's lines, its expected total and its reconciliation never change, and a reversal arriving
 * later is carried in the cycle covering the reversal's own business date instead. See ADR-0009.
 *
 * <p>Totals are stored on the row rather than summed on read. A statement has to have a single number
 * that was true at a moment, not a query that returns whatever the lines happen to say today — a
 * {@code SUM} over the lines of a closed cycle is a second implementation of the total, and the two will
 * disagree the first time a line is written outside the method that maintains the total.
 */
@Entity
@Table(name = "settlement_cycles")
public class SettlementCycle {

    @Id
    private UUID id;

    /** The human-facing name. See {@link CycleReference} for why it is derived. */
    @Column(name = "reference", nullable = false, unique = true, length = 64)
    private String reference;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SettlementCycleStatus status;

    /** Sum of the cycle's net lines at the moment of closing. See the class comment. */
    @Column(name = "expected_minor", nullable = false)
    private long expectedMinor;

    /**
     * The clearing figure declared for this period.
     *
     * <p>Null until declared, which is what makes "not yet reconciled" distinguishable from "reconciled to
     * zero": a period that genuinely balanced has an actual, and it is zero, and a column that defaulted
     * to zero would make the two cases the same row.
     */
    @Column(name = "actual_minor")
    private Long actualMinor;

    @Column(name = "difference_minor")
    private Long differenceMinor;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected SettlementCycle() {}

    /**
     * Opens a cycle for a business date and currency.
     *
     * <p>Opening is idempotent in intent but not in effect: the reference is derived from the date and
     * currency so that a second call produces the same reference and collides on the unique index, rather
     * than producing a second statement for the same money.
     *
     * @param businessDate the day being settled
     * @param currency the currency being settled
     * @param now the current instant
     * @return an open, empty cycle
     */
    public static SettlementCycle open(LocalDate businessDate, Currency currency, Instant now) {
        Objects.requireNonNull(businessDate, "business date is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(now, "now is required");
        CycleReference reference = CycleReference.of(businessDate, currency);
        SettlementCycle cycle = new SettlementCycle();
        cycle.id = UUID.randomUUID();
        cycle.reference = reference.value();
        cycle.businessDate = businessDate;
        cycle.currencyCode = currency.getCurrencyCode();
        cycle.status = SettlementCycleStatus.OPEN;
        cycle.expectedMinor = 0L;
        cycle.openedAt = now;
        cycle.createdAt = now;
        cycle.updatedAt = now;
        return cycle;
    }

    /**
     * Whether lines may still be added.
     *
     * <p>The single check behind every immutability rule in this service. It is a method rather than an
     * inline {@code status == OPEN} so that there is exactly one place where the rule is written, and a
     * caller cannot satisfy it by comparing against the wrong status constant.
     *
     * @return true when the cycle is still open
     */
    public boolean isOpen() {
        return status == SettlementCycleStatus.OPEN;
    }

    /**
     * Refuses a line against a cycle that is not open.
     *
     * <p>Called by {@link SettlementLine}'s factories rather than only by the service layer, and that
     * placement is the point. The immutability rule has to hold for every path that can write a line,
     * including a future one; a check in the service is a check on today's service, whereas a check in the
     * line's own constructor is a property of the line. The service still checks, because it has to decide
     * what to <em>record</em> when the period is closed — but a line that exists at all is a line whose
     * cycle was open when it was made.
     *
     * @throws IllegalStateException if the cycle is not open
     */
    public void requireOpenForLine() {
        requireOpen("add a line to");
    }

    /**
     * Closes the cycle, freezing the lines and the expected total.
     *
     * @param expected the total of the cycle's lines
     * @param now the current instant
     * @throws IllegalStateException if the cycle is not open
     */
    public void close(Money expected, Instant now) {
        requireOpen("close");
        Objects.requireNonNull(expected, "expected total is required");
        requireOwnCurrency(expected);
        this.expectedMinor = expected.minorUnits();
        this.status = SettlementCycleStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    /**
     * Records the independently declared actual for this period and compares it with the expected total.
     *
     * <p>The two figures are compared rather than assumed equal, and the whole point of the design is
     * that they are not the same number computed twice: the expected total is derived from consumed
     * events, and the actual is declared by an operator because a bank feed does not exist here. See
     * ADR-0009 for why deriving the actual from our own database instead was rejected.
     *
     * @param actual the declared clearing figure
     * @param now the current instant
     * @return the signed difference, actual minus expected
     * @throws IllegalStateException if the cycle is not closed
     * @throws IllegalArgumentException if the actual is in a different currency
     */
    public Money declareActual(Money actual, Instant now) {
        requireClosed("declare an actual for");
        Objects.requireNonNull(
                actual,
                "declared actual is required; a reconciliation that compares nothing "
                        + "proves nothing, and defaulting it to the expected total would prove exactly that");
        requireOwnCurrency(actual);
        this.actualMinor = actual.minorUnits();
        this.differenceMinor = actual.subtract(expected()).minorUnits();
        this.updatedAt = now;
        return new Money(this.differenceMinor, currency());
    }

    /**
     * Settles the cycle as reconciled, if and only if the declared actual matched.
     *
     * <p>A cycle with an outstanding break cannot be reconciled. That refusal is the mechanism by which a
     * break cannot be closed by an operator who simply disagrees with it: the money has to be accounted
     * for, and the acknowledgement is recorded on the break itself so the trail distinguishes "somebody
     * has seen this" from "this is explained".
     *
     * @param now the current instant
     * @throws IllegalStateException if the cycle is not closed, has no actual, or does not balance
     */
    public void reconcile(Instant now) {
        if (status != SettlementCycleStatus.CLOSED) {
            throw new IllegalStateException(
                    "only a closed cycle can be reconciled, but " + reference + " is " + status);
        }
        if (actualMinor == null) {
            throw new IllegalStateException("cannot reconcile " + reference + ": no actual has been declared. "
                    + "Reconciling against a figure this service derived itself would be the check that always "
                    + "passes, which is worse than no check at all.");
        }
        if (differenceMinor != 0) {
            throw new IllegalStateException("cannot reconcile " + reference + ": the declared actual differs "
                    + "from the expected total by " + differenceMinor + " minor units, and a cycle with an "
                    + "unexplained difference is the one thing a reconciliation exists to prevent marking as "
                    + "done");
        }
        this.status = SettlementCycleStatus.RECONCILED;
        this.settledAt = now;
        this.updatedAt = now;
    }

    /**
     * Marks the cycle as broken, because its declared actual did not match.
     *
     * <p>Not an exception. The difference is the finding, and a service that refuses to record it has
     * decided the money agrees before anybody checked.
     *
     * @param now the current instant
     * @throws IllegalStateException if the cycle is not closed
     */
    public void breakWith(Instant now) {
        requireClosed("break");
        this.status = SettlementCycleStatus.BROKEN;
        this.updatedAt = now;
    }

    /**
     * The expected total as money in the cycle's currency.
     *
     * @return the total frozen at closing
     */
    public Money expected() {
        return new Money(expectedMinor, currency());
    }

    /**
     * The declared actual as money, if one has been declared.
     *
     * @return the actual, or null when none has been declared
     */
    public Money actual() {
        return actualMinor == null ? null : new Money(actualMinor, currency());
    }

    /**
     * The signed difference between the declared actual and the expected total, if both exist.
     *
     * @return the difference, or null when no actual has been declared
     */
    public Money difference() {
        return differenceMinor == null ? null : new Money(differenceMinor, currency());
    }

    /**
     * The cycle's currency.
     *
     * @return the currency this cycle settles
     */
    public Currency currency() {
        return Currency.getInstance(currencyCode);
    }

    /**
     * This cycle's reference.
     *
     * @return the derived reference
     */
    public CycleReference cycleReference() {
        return new CycleReference(reference, businessDate, currency());
    }

    /**
     * Refuses a figure in the wrong currency.
     *
     * <p>A statement total in one currency is a statement about one currency, so a mismatched figure here
     * is a caller bug that would otherwise surface as a total that is off by an amount nobody can explain.
     *
     * @param money the figure to check
     */
    private void requireOwnCurrency(Money money) {
        if (!currency().equals(money.currency())) {
            throw new IllegalArgumentException(reference + " settles in " + currencyCode + ", not "
                    + money.currency().getCurrencyCode());
        }
    }

    /**
     * Refuses to act on a cycle that is not open.
     *
     * @param action the action's name, for the message
     */
    private void requireOpen(String action) {
        if (!isOpen()) {
            throw new IllegalStateException("cannot " + action + " " + reference + ", which is " + status
                    + ". A cycle that is not open has been counted, and a count that changes after it is "
                    + "given to somebody is not a count. Post the movement to the cycle covering its own "
                    + "business date instead.");
        }
    }

    /**
     * Refuses to act on a cycle that is not closed.
     *
     * @param action the action's name, for the message
     */
    private void requireClosed(String action) {
        if (status != SettlementCycleStatus.CLOSED) {
            throw new IllegalStateException("cannot " + action + " " + reference + ", which is " + status
                    + ". Only a closed cycle has a frozen total to compare an actual against");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public SettlementCycleStatus getStatus() {
        return status;
    }

    public long getExpectedMinor() {
        return expectedMinor;
    }

    public Long getActualMinor() {
        return actualMinor;
    }

    public Long getDifferenceMinor() {
        return differenceMinor;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return reference + "[" + status + ", expected=" + expected() + "]";
    }
}
