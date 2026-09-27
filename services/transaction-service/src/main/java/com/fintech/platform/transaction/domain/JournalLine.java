package com.fintech.platform.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a {@link JournalEntry}: an account, a direction, an amount, and the balance it left.
 *
 * <p>The amount is always positive and the sign lives in {@link PostingDirection}. Allowing a signed
 * amount would let "debit -100" and "credit 100" be two spellings of one fact, and a ledger that
 * contains both is a ledger whose balances are wrong somewhere and unreadable everywhere.
 *
 * <p>{@link #balanceAfterMinor} is the balance on the account immediately after this line applied.
 * It is the column that makes a ledger auditable without replaying it: the sequence of
 * {@code balanceAfter} values for one account is a running balance, so a reported total can be checked
 * against the last line's stored balance instead of by re-summing the table.
 */
@Entity
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private JournalEntry entry;

    /**
     * The account, as an id rather than a {@code @ManyToOne} to {@link LedgerAccount}.
     *
     * <p>Deliberate. A journal line is immutable history, and a foreign key to the live account row
     * would make reading the history depend on a row that is still being updated, so every read of an
     * old entry would contend with the posting that is writing to the same account today. The id keeps
     * the link and drops the coupling.
     */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private PostingDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    protected JournalLine() {}

    static JournalLine of(JournalEntry entry, Posting.Leg leg) {
        Objects.requireNonNull(entry, "entry must not be null");
        JournalLine line = new JournalLine();
        line.id = UUID.randomUUID();
        line.entry = entry;
        // The account id is filled in by the service once it has resolved the account type to a row.
        // A line cannot exist in a saved entry without one, so the service is the only writer.
        line.accountId = null;
        line.direction = leg.direction();
        line.amountMinor = leg.amount().minorUnits();
        line.balanceAfterMinor = 0;
        return line;
    }

    /**
     * Binds this line to the account it names and records the balance the line produced.
     *
     * <p>Called by the service while it holds the account's row lock, in the same transaction that
     * writes the new balance. Binding and balancing in one step is what stops a line being saved with a
     * balance that no posting ever produced.
     *
     * <p>Public because the service is the only caller and lives in another package. Deliberately
     * narrow: it is the single way to move a line out of its unbound state, so a line that is persisted
     * without having been through here has no account and no recorded balance, and the accessors below
     * refuse to pretend otherwise.
     */
    public void bindTo(LedgerAccount account, long balanceAfterMinor) {
        Objects.requireNonNull(account, "account must not be null");
        this.accountId = account.id();
        this.balanceAfterMinor = balanceAfterMinor;
    }

    public UUID id() {
        return id;
    }

    public JournalEntry entry() {
        return entry;
    }

    public UUID accountId() {
        if (accountId == null) {
            throw new IllegalStateException("journal line " + id + " has not been posted to an account yet");
        }
        return accountId;
    }

    public PostingDirection direction() {
        return direction;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public Money amount() {
        return Money.minor(amountMinor, entry.currency());
    }

    public long balanceAfterMinor() {
        return balanceAfterMinor;
    }

    @Override
    public String toString() {
        return direction + " " + amountMinor + " -> " + balanceAfterMinor;
    }
}
