package com.fintech.platform.transaction.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One balanced movement of value, and the lines that compose it.
 *
 * <p>This is the record of what happened. {@link LedgerAccount#balanceMinor()} is derived from it, and
 * the two are only ever written in the same database transaction by the same posting, so a balance
 * that disagrees with its journal is not a state this schema can be left in by a crash.
 *
 * <p>Entries are never edited or deleted. A mistake is fixed by posting a {@link
 * JournalEntryKind#REVERSAL} that names the offending entry, which is why {@link #reversalOfEntryId()} exists:
 * a correction with no reference to what it corrects is indistinguishable from an ordinary movement
 * once enough time has passed.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    /**
     * The transaction this entry belongs to, or null for one that is not a payment.
     *
     * <p>Nullable rather than "every entry is a payment" because funding is a movement of value with no
     * transaction behind it. Forcing a fake transaction id onto a deposit would be a lie in the one
     * table an auditor reads closely.
     */
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private JournalEntryKind kind;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /**
     * The magnitude of the entry, repeated from its lines.
     *
     * <p>Redundant, and kept anyway: it is the column every report and every reconciliation reads, and
     * summing lines to answer "how much settled today?" turns one indexed scan into a group-by over
     * the largest table in the service.
     */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    /** The entry this one undoes, for a reversal. Null for every other kind. */
    @Column(name = "reversal_of_entry_id")
    private UUID reversalOfEntryId;

    /** Short operator-supplied text. Free-form by design; it is for humans reading the journal. */
    @Column(name = "description", length = 280)
    private String description;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {}

    /**
     * Builds an entry from a balanced {@link Posting}.
     *
     * <p>Re-checks the balance rather than trusting the posting. The posting's own constructor already
     * guarantees it, and this second check is the point: a refactor that lets callers reach a line set
     * without a posting cannot produce an entry, and the cost is one sum over a list of at most four.
     */
    public static JournalEntry of(
            UUID id,
            UUID transactionId,
            Posting posting,
            UUID reversalOfEntryId,
            String description,
            Instant effectiveAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(posting, "posting must not be null");
        JournalEntry entry = new JournalEntry();
        entry.id = id;
        entry.transactionId = transactionId;
        entry.kind = posting.kind();
        entry.currencyCode = posting.currency().getCurrencyCode();
        entry.amountMinor = posting.amount().minorUnits();
        entry.reversalOfEntryId = reversalOfEntryId;
        entry.description = description;
        entry.effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        entry.createdAt = effectiveAt;
        for (Posting.Leg leg : posting.legs()) {
            entry.lines.add(JournalLine.of(entry, leg));
        }
        entry.verifyBalanced();
        return entry;
    }

    /**
     * Asserts that debits equal credits.
     *
     * <p>The single definition of what double-entry means here. Every test that claims the ledger
     * balances should be calling this, and the database should keep a constraint matching it, so that
     * neither the domain nor a future hand-written insert can make the two disagree.
     */
    public void verifyBalanced() {
        long debits = 0;
        long credits = 0;
        for (JournalLine line : lines) {
            if (line.direction() == PostingDirection.DEBIT) {
                debits = Math.addExact(debits, line.amountMinor());
            } else {
                credits = Math.addExact(credits, line.amountMinor());
            }
        }
        if (debits != credits) {
            throw new IllegalStateException(
                    "journal entry " + id + " does not balance: debits " + debits + " != credits " + credits);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public JournalEntryKind kind() {
        return kind;
    }

    public Currency currency() {
        return Currency.getInstance(currencyCode);
    }

    public long amountMinor() {
        return amountMinor;
    }

    public Money amount() {
        return Money.minor(amountMinor, currency());
    }

    public UUID reversalOfEntryId() {
        return reversalOfEntryId;
    }

    public String description() {
        return description;
    }

    public Instant effectiveAt() {
        return effectiveAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /**
     * The entry's lines, as an immutable snapshot.
     *
     * <p>{@code List.copyOf} so a caller cannot add a line to a journal that is already written. The
     * copy is what forces the lazy collection to load, which means this must be called while a session
     * is open; use {@code JournalEntryRepository.findAllWithLines} when reading from outside one.
     */
    public List<JournalLine> lines() {
        return List.copyOf(lines);
    }

    /**
     * Scalars only, deliberately.
     *
     * <p>No line count. {@code lines} is lazy, so reading its size on a detached entry throws
     * {@code LazyInitializationException} — and a {@code toString} that throws is a trap, because the
     * usual reason to call it is to write a log line, so the failure surfaces in a log statement where
     * nobody is looking for it and the original cause is long gone. Every field here is a column on the
     * row, so there is no lazy access however the instance was obtained.
     */
    @Override
    public String toString() {
        return "JournalEntry[" + kind + " " + amount() + " tx=" + transactionId + "]";
    }
}
