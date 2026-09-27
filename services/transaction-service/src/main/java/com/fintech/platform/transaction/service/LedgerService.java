package com.fintech.platform.transaction.service;

import com.fintech.platform.transaction.domain.InsufficientFundsException;
import com.fintech.platform.transaction.domain.JournalEntry;
import com.fintech.platform.transaction.domain.JournalLine;
import com.fintech.platform.transaction.domain.LedgerAccount;
import com.fintech.platform.transaction.domain.LedgerAccountType;
import com.fintech.platform.transaction.domain.LedgerPostings;
import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.PlatformAccount;
import com.fintech.platform.transaction.domain.Posting;
import com.fintech.platform.transaction.error.TransactionErrorCodes;
import com.fintech.platform.transaction.persistence.JournalEntryRepository;
import com.fintech.platform.transaction.persistence.LedgerAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies postings to the ledger.
 *
 * <p>Every method here is {@code @Transactional} and every one of them either writes a balanced
 * {@link JournalEntry} together with the balances it implies, or writes nothing. That is the whole
 * service, and it is deliberately thin: the decisions worth arguing about — whether a posting balances,
 * which accounts may go negative, in what order rows are locked — live in the domain and the
 * repository, so what is left here is the part that should be obvious on sight.
 *
 * <p><b>Why one transaction is not optional.</b> A posting changes balances and appends journal lines.
 * In two transactions, a crash between them leaves money that moved with no record of why, and the
 * conservation trigger in the schema would not fire because the journal was not in the same commit.
 * The single transaction is what makes the journal the primary record rather than a log of one.
 *
 * <p><b>The customer is always an explicit parameter.</b> Earlier this was held in a thread local so that
 * the posting helpers did not have to thread it through, which made a posting reachable from anywhere
 * in the same thread and made "whose money is this?" a question answered by ambient state. An argument
 * is longer and cannot be forgotten.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private static final int MAX_DESCRIPTION_LENGTH = 280;

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final Clock clock;

    public LedgerService(LedgerAccountRepository accounts, JournalEntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.clock = clock;
    }

    /**
     * Reserves an authorised payment's amount, or reports why it could not be reserved.
     *
     * <p>Both customer accounts are locked in one statement in id order, and the overdraft rule is
     * applied by {@link LedgerAccount#apply} while that lock is held. That is the difference between a
     * balance that is always right and one that is right until two cards are presented in the same
     * instant.
     *
     * <p><b>Returns a refusal instead of throwing one, and that is the load-bearing part of this
     * signature.</b> A hold is the one posting whose failure is a normal outcome rather than an error: the
     * customer has no money, and the payment is declined and recorded. Throwing it out of a
     * {@code @Transactional} method would work against that. Spring marks the transaction rollback-only
     * when an exception crosses the transactional proxy, and that marking survives the caller catching it,
     * because the catch happens further up the stack than the proxy. So a service that caught
     * {@code InsufficientFundsException} and wrote a declined row would still fail at commit with
     * {@code UnexpectedRollbackException} — the row would be erased, the client would get a 500 instead of
     * a 422, and the only record that the customer tried to spend money would be the one thing lost. The
     * failure is silent in the sense that matters: the code reads as though declines are handled, and the
     * decline is precisely the case that never works.
     *
     * <p>Catching it <em>here</em>, inside the transactional boundary, means nothing propagates through
     * the proxy, the transaction is never marked, and the refusal travels back as a return value the
     * caller can act on. The refusal is not exceptional at the point it is produced; it only becomes
     * interesting one layer up, where there is a payment to mark as declined.
     *
     * <p>{@link LedgerAccount#apply} throws before it mutates, so a refused hold has changed nothing and
     * there is nothing to undo for the transaction to go on and commit.
     */
    @Transactional
    public HoldOutcome hold(String ownerSubjectDigest, UUID transactionId, Money amount) {
        try {
            JournalEntry entry =
                    post(LedgerPostings.hold(amount), ownerSubjectDigest, transactionId, "Authorisation hold", null);
            return HoldOutcome.held(entry);
        } catch (InsufficientFundsException refused) {
            return HoldOutcome.refused(refused);
        }
    }

    /**
     * The result of a hold: an entry, or the reason there is not one.
     *
     * <p>Exactly one of the two is set, and {@link #isRefused()} is the question worth asking. The
     * accessors are deliberately not {@code entry()} and {@code refusal()}-or-throw: a caller that reached
     * for the entry without checking would get a null dereference, and the type is here to make checking
     * unnecessary.
     */
    public record HoldOutcome(JournalEntry entry, InsufficientFundsException refusal) {

        static HoldOutcome held(JournalEntry entry) {
            return new HoldOutcome(Objects.requireNonNull(entry, "entry must not be null"), null);
        }

        static HoldOutcome refused(InsufficientFundsException refusal) {
            return new HoldOutcome(null, Objects.requireNonNull(refusal, "refusal must not be null"));
        }

        /** Whether the hold was refused for want of money. */
        public boolean isRefused() {
            return refusal != null;
        }

        /**
         * The entry for a hold that succeeded.
         *
         * @throws IllegalStateException if the hold was refused, rather than returning null and letting
         *     the caller fail later with a null pointer somewhere less obvious
         */
        public JournalEntry requireEntry() {
            if (refusal != null) {
                throw new IllegalStateException("the hold was refused: " + refusal.getMessage(), refusal);
            }
            return entry;
        }
    }

    /** Releases a hold that will never be captured, returning the money to spendable. */
    @Transactional
    public JournalEntry release(String ownerSubjectDigest, UUID transactionId, Money amount) {
        return post(LedgerPostings.release(amount), ownerSubjectDigest, transactionId, "Authorisation released", null);
    }

    /** Captures held money out into clearing. */
    @Transactional
    public JournalEntry capture(String ownerSubjectDigest, UUID transactionId, Money amount) {
        return post(LedgerPostings.capture(amount), ownerSubjectDigest, transactionId, "Capture", null);
    }

    /**
     * Funds a customer from outside the platform.
     *
     * <p>Creates the accounts if they do not exist, because funding is the one operation that
     * legitimately brings an account into being. A hold refuses when the account is missing: a payment
     * against an account that was never funded should say so, rather than create an empty one and
     * decline on a zero balance, which reads like a different problem to whoever is told why it failed.
     */
    @Transactional
    public JournalEntry fund(String ownerSubjectDigest, Money amount) {
        ensureAccountsExist(ownerSubjectDigest, amount.currency());
        return post(LedgerPostings.funding(amount), ownerSubjectDigest, null, "Funding", null);
    }

    /**
     * Undoes an earlier entry.
     *
     * <p>Takes the original and mirrors it, rather than being told what the reversal should be. The
     * reversal is therefore exactly the original's arithmetic run backwards and the two cannot drift:
     * there is no separate "reverse a capture" path that could be written with a sign error the forward
     * path does not have.
     */
    @Transactional
    public JournalEntry reverse(String ownerSubjectDigest, UUID transactionId, JournalEntry original) {
        Posting originalPosting = reconstructPosting(original);
        Posting reversal = originalPosting.asReversalOf(originalPosting);
        return post(reversal, ownerSubjectDigest, transactionId, "Reversal of " + original.id(), original.id());
    }

    /**
     * The customer's spendable and held balances.
     *
     * <p>Unlocked, and reported as zeros when the accounts do not exist. A customer who has never been
     * funded has a balance of zero, and that is a true answer rather than a missing one — the caller
     * should not have to distinguish "no money" from "no account".
     */
    @Transactional(readOnly = true)
    public Map<LedgerAccountType, Money> balancesFor(String ownerSubjectDigest, Currency currency) {
        Map<LedgerAccountType, Money> balances = new EnumMap<>(LedgerAccountType.class);
        for (LedgerAccountType type : LedgerAccountType.customerTypes()) {
            balances.put(type, Money.zero(currency));
        }
        for (LedgerAccount account : accounts.findAllForOwner(ownerSubjectDigest, currency.getCurrencyCode())) {
            balances.put(account.type(), account.balance());
        }
        return balances;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Applies a posting and records it, atomically.
     *
     * <p>Lock, then apply, then write. The order is load-bearing: a lock taken after the balances were
     * computed would protect nothing, because the computation would already have been made from an
     * unlocked read.
     */
    private JournalEntry post(
            Posting posting,
            String ownerSubjectDigest,
            UUID transactionId,
            String description,
            UUID reversalOfEntryId) {
        Money amount = posting.amount();
        String currencyCode = amount.currency().getCurrencyCode();
        Instant now = clock.instant();

        Map<LedgerAccountType, LedgerAccount> locked = lockAccounts(posting, ownerSubjectDigest, currencyCode);

        // Apply before writing anything, so a refusal leaves no partial state. The rows are locked, so
        // the balance being tested is one no concurrent payment can change.
        Map<LedgerAccountType, Long> balanceAfterByType = new EnumMap<>(LedgerAccountType.class);
        for (Posting.Leg leg : posting.legs()) {
            LedgerAccount account = locked.get(leg.accountType());
            if (account == null) {
                throw TransactionErrorCodes.ACCOUNT_NOT_FOUND.exception(
                        "no " + leg.accountType() + " account in " + currencyCode);
            }
            long balanceAfter;
            // Rethrown as itself, not translated, and not logged here. A refusal is a domain outcome with a
            // defined type carrying the account, the amount asked for and the shortfall, and whoever
            // posted this needs all three. Converting it to an ApiException here would replace that with a
            // bare HTTP code, and the service that is supposed to record a declined payment would no
            // longer recognise it. Whether a refusal becomes a declined row, a 422, or a rollback is the
            // caller's decision to make, and a log line saying "declined" in the ledger would be asserting
            // an outcome this layer does not know about.
            balanceAfter = account.apply(leg.direction(), leg.amount());
            balanceAfterByType.put(leg.accountType(), balanceAfter);
        }

        JournalEntry entry = JournalEntry.of(
                UUID.randomUUID(), transactionId, posting, reversalOfEntryId, truncate(description), now);
        bindLines(entry, posting, locked, balanceAfterByType);
        return entries.save(entry);
    }

    /**
     * Locks every account a posting touches, platform accounts first.
     *
     * <p>Two decisions, both about avoiding deadlock.
     *
     * <p><b>Platform before customer.</b> Customer and platform accounts are separate rows under
     * different owner references, so they cannot be locked by one statement. Funding locks
     * platform-then-customer because that is the order this method uses; if a payment locked the
     * customer's first, a funding request and a payment touching the same currency and customer would
     * take the two sets in opposite orders and deadlock. Every posting takes platform accounts first,
     * so the relative order is always the same.
     *
     * <p><b>Id order within a set.</b> {@code lockForUpdate} returns rows ordered by id and is
     * {@code SELECT ... FOR UPDATE}, so a row that appears second is still only locked once the
     * statement has fetched it. The important property is that two postings touching the same accounts
     * take them in the same order, which the {@code ORDER BY} guarantees and an unordered loop would
     * not.
     */
    private Map<LedgerAccountType, LedgerAccount> lockAccounts(
            Posting posting, String ownerSubjectDigest, String currencyCode) {
        Map<LedgerAccountType, LedgerAccount> locked = new EnumMap<>(LedgerAccountType.class);

        // One statement per owner, not one per type. Two statements for one customer's two accounts
        // would hold the first lock while acquiring the second, which is correct but needlessly widens
        // the window in which a concurrent posting waits; one statement ordered by id is both the
        // deadlock argument and the shorter critical section.
        // Platform accounts first. Two postings touching customer and platform in the same currency
        // must take them in the same order: funding takes platform-then-customer, so any other posting
        // that touches both must take them in the same order to avoid deadlock. The order is explicit
        // and intentional. Id order is enforced within each statement by the repository query.
        List<LedgerAccountType> platformTypes = posting.accountTypes().stream()
                .filter(type -> !type.isCustomerAccount())
                .toList();
        if (!platformTypes.isEmpty()) {
            for (LedgerAccount account :
                    accounts.lockForUpdate(PlatformAccount.OWNER_REF, platformTypes, currencyCode)) {
                locked.put(account.type(), account);
            }
        }
        List<LedgerAccountType> customerTypes = posting.accountTypes().stream()
                .filter(LedgerAccountType::isCustomerAccount)
                .toList();
        if (!customerTypes.isEmpty()) {
            for (LedgerAccount account : accounts.lockForUpdate(ownerSubjectDigest, customerTypes, currencyCode)) {
                locked.put(account.type(), account);
            }
        }
        return locked;
    }

    /**
     * Wires each line to the account it posted to, with the balance that line left.
     *
     * <p>Positional, because {@link JournalEntry#of} appended the lines in posting order. A mismatch
     * here would attribute a debit to the wrong account, which is the class of error that stays
     * invisible until a customer notices their balance, so the ordering is asserted rather than assumed.
     */
    private void bindLines(
            JournalEntry entry,
            Posting posting,
            Map<LedgerAccountType, LedgerAccount> locked,
            Map<LedgerAccountType, Long> balanceAfterByType) {
        List<JournalLine> lines = entry.lines();
        List<Posting.Leg> legs = posting.legs();
        if (lines.size() != legs.size()) {
            throw new IllegalStateException(
                    "entry " + entry.id() + " has " + lines.size() + " lines for " + legs.size() + " legs");
        }
        for (int i = 0; i < legs.size(); i++) {
            Posting.Leg leg = legs.get(i);
            lines.get(i).bindTo(locked.get(leg.accountType()), balanceAfterByType.get(leg.accountType()));
        }
    }

    /**
     * Rebuilds a posting from a stored entry so a reversal can mirror it.
     *
     * <p>Reads the account rows to recover each line's account <em>type</em>, which the line does not
     * store. That is acceptable here and only here: a reversal is a rare, deliberate event, and the
     * rows it reads are locked again by the posting that follows. On the payment hot path this would be
     * a problem, which is why the hot path never needs to reconstruct anything.
     */
    private Posting reconstructPosting(JournalEntry original) {
        List<Posting.Leg> legs = new ArrayList<>(original.lines().size());
        for (JournalLine line : original.lines()) {
            LedgerAccountType type = accounts.findById(line.accountId())
                    .map(LedgerAccount::type)
                    .orElseThrow(() -> new IllegalStateException("journal line " + line.id()
                            + " names an account that does not exist: " + line.accountId()));
            legs.add(new Posting.Leg(type, line.direction(), line.amount()));
        }
        return Posting.balanced(original.kind(), legs);
    }

    /**
     * Creates the customer's and platform's accounts in a currency, if absent.
     *
     * <p>Called by funding only, and the insert is tolerant of losing the race: see
     * {@link #createIfAbsent}.
     */
    @Transactional
    public void ensureAccountsExist(String ownerSubjectDigest, Currency currency) {
        for (LedgerAccountType type : LedgerAccountType.customerTypes()) {
            createIfAbsent(UUID.randomUUID(), ownerSubjectDigest, type, currency);
        }
        for (LedgerAccountType type :
                List.of(LedgerAccountType.PLATFORM_FUNDING, LedgerAccountType.PLATFORM_CLEARING)) {
            createIfAbsent(UUID.randomUUID(), PlatformAccount.OWNER_REF, type, currency);
        }
    }

    /**
     * Inserts an account unless the unique constraint already holds one.
     *
     * <p>One statement, delegated to the database. See
     * {@link LedgerAccountRepository#insertIfAbsent} for why this is not a check followed by a save:
     * the losing side of a funding race cannot recover inside a transaction that has already raised a
     * constraint violation, so the insert has to be written so that losing is not an error at all.
     */
    private void createIfAbsent(UUID id, String ownerRef, LedgerAccountType type, Currency currency) {
        if (accounts.insertIfAbsent(id, ownerRef, type.name(), currency.getCurrencyCode()) == 1) {
            log.debug("created {} account for {} in {}", type, ownerRef, currency.getCurrencyCode());
        }
    }

    private static String truncate(String description) {
        if (description == null) {
            return null;
        }
        return description.length() <= MAX_DESCRIPTION_LENGTH
                ? description
                : description.substring(0, MAX_DESCRIPTION_LENGTH);
    }
}
