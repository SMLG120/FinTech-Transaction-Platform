package com.fintech.platform.transaction.persistence;

import com.fintech.platform.transaction.domain.JournalEntry;
import com.fintech.platform.transaction.domain.JournalEntryKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Journal entry storage. Read-only in practice: entries are written once and never edited.
 *
 * <p>There is no {@code delete} method and no update path, and that is a property of the design rather
 * than an oversight of the interface. A journal that can be edited is a journal that cannot be
 * reconciled, because the only correct fix for a wrong entry is a reversal that names it. Spring Data
 * hands out {@code deleteById} for free, so the real enforcement is {@code journal_entries.reversal_of_entry_id}
 * plus the service never calling it — but nothing here invites the call.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /** A payment's whole history, oldest first. The order matters for reading a balance. */
    List<JournalEntry> findByTransactionIdOrderByEffectiveAtAsc(UUID transactionId);

    /** Every entry that moved value in a window, for the reconciliation a settlement run will do. */
    @Query("""
            SELECT e FROM JournalEntry e
             WHERE e.effectiveAt >= :from
               AND e.effectiveAt < :to
             ORDER BY e.effectiveAt, e.id
            """)
    List<JournalEntry> findByEffectiveAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * The magnitude of one kind of movement in a window.
     *
     * <p>Read by the daily spending-limit check, which is why it is a projection rather than a sum the
     * service performs in memory. The limit is evaluated on every authorisation, so it is on the hot
     * path, and summing entries in Java would mean loading a customer's whole day of payments on every
     * payment attempt.
     *
     * <p>Kinds are passed in rather than hard-coded, because what counts against a limit is a policy
     * decision — an authorisation and a capture of the same money must not both be counted, or every
     * payment costs twice against the customer's daily total.
     */
    @Query("""
            SELECT COALESCE(SUM(e.amountMinor), 0) FROM JournalEntry e
             WHERE e.transactionId IN :transactionIds
               AND e.kind IN :kinds
            """)
    long sumAmountMinorByTransactionsAndKinds(
            @Param("transactionIds") List<UUID> transactionIds, @Param("kinds") List<JournalEntryKind> kinds);

    /**
     * Every entry, with its lines, in one query.
     *
     * <p>A fetch join because an entry without its lines is not a useful thing to return. The collection
     * is lazy, so a plain {@code findAll()} hands back entries whose {@code lines()} throws once the
     * session is gone — and the caller who asked for a journal is, by definition, outside the session
     * that loaded it. That is not a mistake the caller can be expected to notice, because nothing about
     * the call site says the lines are missing.
     *
     * <p>{@code DISTINCT} because a join multiplies rows: an entry with four lines comes back four
     * times, and the duplication is a property of the query rather than of the data.
     *
     * <p>This is the read path for auditing and for the tests that assert on the ledger. Writes do not
     * go through here, and the payment hot path never loads a whole journal.
     */
    @Query("SELECT DISTINCT e FROM JournalEntry e LEFT JOIN FETCH e.lines ORDER BY e.effectiveAt, e.id")
    List<JournalEntry> findAllWithLines();
}
