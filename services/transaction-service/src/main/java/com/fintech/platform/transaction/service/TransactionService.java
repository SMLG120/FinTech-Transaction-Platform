package com.fintech.platform.transaction.service;

import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.transaction.config.PaymentProperties;
import com.fintech.platform.transaction.domain.Counterparty;
import com.fintech.platform.transaction.domain.FraudContext;
import com.fintech.platform.transaction.domain.JournalEntry;
import com.fintech.platform.transaction.domain.JournalEntryKind;
import com.fintech.platform.transaction.domain.LedgerAccountType;
import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.domain.TransactionStatus;
import com.fintech.platform.transaction.error.TransactionErrorCodes;
import com.fintech.platform.transaction.persistence.JournalEntryRepository;
import com.fintech.platform.transaction.persistence.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment lifecycle.
 *
 * <p><b>One transaction covers the status change, the ledger posting and the outbox row.</b> That is
 * the whole design, and every method here is one database transaction because of it. The three facts —
 * the payment is authorised, the money is reserved, the event is queued — are either all true or none
 * is, and any of the other arrangements has a failure mode where a customer is told a payment succeeded
 * and no money was reserved, or money is reserved for a payment the platform does not know about.
 *
 * <p><b>Authorise is the operation that needs the lock, and it gets it by posting.</b> The overdraft
 * rule is not a check performed before the posting; it is enforced inside {@code LedgerAccount.apply}
 * while the account's row lock is held. A check-then-act outside the lock would be a check that a
 * concurrent payment invalidates between the check and the write, which is a double spend wearing the
 * costume of a validation.
 *
 * <p><b>Declines are committed, not thrown away.</b> A declined payment is a real event the merchant
 * needs to see, so the row is written with its reason and the refusal is a 422 rather than an exception
 * that rolls the transaction back. Rolling back would erase the only record that the customer tried to
 * spend money.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /**
     * Statuses that count against the daily limit.
     *
     * <p>Authorised and settled, and not the reverse of either. A payment and its refund are one
     * movement of money seen twice, and counting both would make the daily total differ from the amount
     * actually spent — which is the only definition of a limit a customer would accept.
     */
    private static final List<TransactionStatus> LIMIT_COUNTING_STATUSES =
            List.of(TransactionStatus.AUTHORIZED, TransactionStatus.SETTLED);

    private final TransactionRepository transactions;
    private final JournalEntryRepository entries;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final PaymentProperties properties;
    private final Clock clock;

    public TransactionService(
            TransactionRepository transactions,
            JournalEntryRepository entries,
            LedgerService ledger,
            OutboxWriter outbox,
            PaymentProperties properties,
            Clock clock) {
        this.transactions = transactions;
        this.entries = entries;
        this.ledger = ledger;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a payment and authorises it in one step.
     *
     * <p>The caller is a customer paying a merchant, and a payment that is created pending has no
     * meaning for long: nothing in this service decides later, because there is no network to ask. So a
     * payment is authorised as it is created, and the interesting outcomes are the two refusals —
     * insufficient funds and a limit — both of which leave a declined row behind.
     */
    @Transactional
    public Transaction authorize(
            String ownerSubjectDigest, String cardToken, Money amount, Counterparty payee, FraudContext context) {
        Instant now = clock.instant();
        requireCurrencySupported(amount.currency());
        requireWithinLimits(ownerSubjectDigest, amount);

        Transaction transaction =
                Transaction.create(UUID.randomUUID(), ownerSubjectDigest, cardToken, amount, payee, now);
        transactions.save(transaction);
        Versioned event = createdPayload(transaction, context, now);
        outbox.recordTransactionEvent(
                transaction,
                KafkaTopics.TRANSACTION_CREATED,
                "transaction.created",
                event.version(),
                event.body(),
                now);

        return authorizeInternal(transaction, ownerSubjectDigest, now);
    }

    /**
     * Authorises an already-created payment.
     *
     * <p>Separate from {@link #authorize} so the hold and the refusal can share one transaction with
     * whatever created the payment, and so the refusal path is testable without creating one.
     */
    @Transactional
    public Transaction authorize(UUID transactionId, String ownerSubjectDigest) {
        Transaction transaction = requireOwned(transactionId, ownerSubjectDigest);
        return authorizeInternal(transaction, ownerSubjectDigest, clock.instant());
    }

    private Transaction authorizeInternal(Transaction transaction, String ownerSubjectDigest, Instant now) {
        LedgerService.HoldOutcome hold = ledger.hold(ownerSubjectDigest, transaction.id(), transaction.amount());
        if (hold.isRefused()) {
            // The hold was refused, so nothing moved. The payment is recorded as declined rather than
            // discarded: a merchant needs to know the customer tried and why it did not go through, and
            // "the request failed" with no row would leave no trace that it was ever attempted.
            //
            // Logged here rather than in the ledger because this is where the refusal becomes a decline.
            // That is a decision about the payment, and the ledger has no opinion on it.
            log.info("declined {} for insufficient funds", transaction.id());
            transaction.decline("INSUFFICIENT_FUNDS", now);
            Versioned event = declinedPayload(transaction, "INSUFFICIENT_FUNDS", now);
            outbox.recordTransactionEvent(
                    transaction,
                    KafkaTopics.TRANSACTION_DECLINED,
                    "transaction.declined",
                    event.version(),
                    event.body(),
                    now);
            return transaction;
        }

        transaction.authorize(now);
        Versioned event = payload(transaction, now);
        outbox.recordTransactionEvent(
                transaction,
                KafkaTopics.TRANSACTION_AUTHORIZED,
                "transaction.authorized",
                event.version(),
                event.body(),
                now);
        return transaction;
    }

    /**
     * Captures an authorised payment.
     *
     * <p>Capture moves money out of the hold and into clearing. It is a separate operation from
     * authorisation because the gap between them is the merchant's risk window, and pretending otherwise
     * would mean the platform could not tell a customer that their money is being held.
     */
    @Transactional
    public Transaction settle(UUID transactionId, String ownerSubjectDigest) {
        Transaction transaction = requireOwned(transactionId, ownerSubjectDigest);
        Instant now = clock.instant();
        ledger.capture(ownerSubjectDigest, transaction.id(), transaction.amount());
        transaction.settle(now);
        Versioned event = payload(transaction, now);
        outbox.recordTransactionEvent(
                transaction,
                KafkaTopics.TRANSACTION_SETTLED,
                "transaction.settled",
                event.version(),
                event.body(),
                now);
        return transaction;
    }

    /**
     * Reverses a payment, releasing an uncollected hold or refunding a capture.
     *
     * <p>Both end in {@code REVERSED} and both are one status because the caller's question afterwards
     * is the same — is this payment live? — but the postings differ, and the branch below is what keeps
     * them correct. Reversing an authorisation releases the hold; reversing a settlement reverses the
     * capture. Treating them identically would leave money in clearing after a refund, which is
     * invisible until a reconciliation notices the platform is holding funds for a payment that was
     * returned.
     */
    @Transactional
    public Transaction reverse(UUID transactionId, String ownerSubjectDigest) {
        Transaction transaction = requireOwned(transactionId, ownerSubjectDigest);
        Instant now = clock.instant();

        if (transaction.status() == TransactionStatus.AUTHORIZED) {
            // The hold has not been captured, so there is nothing to reverse in the journal — the money
            // simply goes back to spendable. Naming an entry to reverse here would be reversing a hold
            // that is being released, which is a different event with a different name.
            ledger.release(ownerSubjectDigest, transaction.id(), transaction.amount());
        } else if (transaction.status() == TransactionStatus.SETTLED) {
            JournalEntry hold = latestEntry(transaction.id(), JournalEntryKind.HOLD);
            JournalEntry capture = latestEntry(transaction.id(), JournalEntryKind.CAPTURE);
            if (capture == null) {
                throw TransactionErrorCodes.LEDGER_POSTING_FAILED.exception(
                        "transaction " + transactionId + " is settled but has no capture entry to reverse");
            }
            // Reverses the capture and releases the hold, so a refund returns the money all the way to
            // spendable. Reversing only the capture would leave the funds reserved forever, which looks
            // to the customer like money they cannot get back.
            ledger.reverse(ownerSubjectDigest, transaction.id(), capture);
            if (hold != null) {
                ledger.reverse(ownerSubjectDigest, transaction.id(), hold);
            }
        }

        transaction.reverse(now);
        Versioned event = payload(transaction, now);
        outbox.recordTransactionEvent(
                transaction,
                KafkaTopics.TRANSACTION_REVERSED,
                "transaction.reversed",
                event.version(),
                event.body(),
                now);
        return transaction;
    }

    /**
     * Funds a customer from outside the platform.
     *
     * <p>The stand-in for a funding rail until Phase 7. It exists so a payment can be authorised at all,
     * and it is the only way value enters the ledger.
     */
    @Transactional
    public void fund(String ownerSubjectDigest, Money amount) {
        requireCurrencySupported(amount.currency());
        Money ceiling = properties.transactionCeilingFor(amount.currency());
        if (amount.minorUnits() > ceiling.minorUnits()) {
            // Reusing the payment ceiling for a deposit. Not the same question, but a single top-up
            // larger than the largest permitted payment is a mistake or an attack, and a dedicated
            // funding limit would be a second number nobody would remember to set.
            throw TransactionErrorCodes.AMOUNT_ABOVE_LIMIT.exception(
                    "A single funding amount cannot exceed " + ceiling);
        }
        ledger.fund(ownerSubjectDigest, amount);
    }

    /**
     * The payer's balances in a currency.
     *
     * <p>Returns zeros for a customer who has never been funded. A balance of zero is a true answer;
     * "no such account" would force every caller to handle a case that is not an error.
     */
    @Transactional(readOnly = true)
    public Map<LedgerAccountType, Money> balances(String ownerSubjectDigest, Currency currency) {
        return ledger.balancesFor(ownerSubjectDigest, currency);
    }

    /** A payment, if the caller owns it. 404 otherwise. */
    @Transactional(readOnly = true)
    public Transaction get(UUID transactionId, String ownerSubjectDigest) {
        return requireOwned(transactionId, ownerSubjectDigest);
    }

    /** A payer's payments, newest first. */
    @Transactional(readOnly = true)
    public List<Transaction> list(String ownerSubjectDigest, int limit) {
        return transactions.findByOwnerSubjectDigestOrderByCreatedAtDesc(
                ownerSubjectDigest,
                org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
    }

    // ------------------------------------------------------------------ internals

    private Transaction requireOwned(UUID transactionId, String ownerSubjectDigest) {
        return transactions
                .findByIdAndOwnerSubjectDigest(transactionId, ownerSubjectDigest)
                .orElseThrow(() -> TransactionErrorCodes.TRANSACTION_NOT_FOUND.exception(
                        "No transaction " + transactionId + " exists for this customer"));
    }

    private void requireCurrencySupported(Currency currency) {
        if (!properties.supports(currency)) {
            throw TransactionErrorCodes.CURRENCY_NOT_SUPPORTED.exception(
                    currency.getCurrencyCode() + " is not supported in this deployment");
        }
    }

    /**
     * Refuses a payment above the per-transaction or daily ceiling.
     *
     * <p>Both limits are counted from the journal's own entries rather than from the transactions table,
     * so the total is the money that actually moved. A total derived from transaction rows would count a
     * payment whose hold failed as though it had been authorised, and would let a customer past their
     * limit by having a payment declined for funds.
     */
    private void requireWithinLimits(String ownerSubjectDigest, Money amount) {
        Money perTransaction = properties.transactionCeilingFor(amount.currency());
        if (amount.minorUnits() > perTransaction.minorUnits()) {
            throw TransactionErrorCodes.AMOUNT_ABOVE_LIMIT.exception(
                    "A single payment cannot exceed " + perTransaction);
        }

        Money daily = properties.dailyCeilingFor(amount.currency());
        long spentToday = spentToday(ownerSubjectDigest, amount.currency());
        long projected = Math.addExact(spentToday, amount.minorUnits());
        if (projected > daily.minorUnits()) {
            throw TransactionErrorCodes.DAILY_LIMIT_EXCEEDED.exception("This payment would bring today's total to "
                    + Money.minor(projected, amount.currency()) + ", above the daily limit of " + daily);
        }
    }

    /**
     * What the customer has already committed today, in minor units.
     *
     * <p>UTC day boundaries, so the limit resets at the same instant everywhere. A limit that resets when
     * the request happened to be routed is one a customer cannot predict, and the boundary would move
     * with daylight saving if the zone did.
     */
    private long spentToday(String ownerSubjectDigest, Currency currency) {
        Instant now = clock.instant();
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);

        List<UUID> ids =
                transactions
                        .findLiveForOwnerOnDay(ownerSubjectDigest, dayStart, dayEnd, LIMIT_COUNTING_STATUSES)
                        .stream()
                        .map(Transaction::id)
                        .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return entries.sumAmountMinorByTransactionsAndKinds(ids, LIMIT_COUNTING_KINDS);
    }

    /**
     * The journal entries a spending limit is measured against.
     *
     * <p>Holds and captures, not reversals. A payment and its refund are one movement of money seen
     * twice, and counting both would make the daily total differ from the amount actually spent.
     *
     * <p>Spelled out rather than derived from the status names. Deriving it — {@code AUTHORIZED} to
     * {@code HOLD} by string replacement — would couple this to the spelling of two enums in two packages
     * and would silently count nothing if either were ever renamed, which is a limit that reads as
     * working and lets everyone through.
     */
    private static final List<JournalEntryKind> LIMIT_COUNTING_KINDS =
            List.of(JournalEntryKind.HOLD, JournalEntryKind.CAPTURE);

    private JournalEntry latestEntry(UUID transactionId, JournalEntryKind kind) {
        return entries.findByTransactionIdOrderByEffectiveAtAsc(transactionId).stream()
                .filter(entry -> entry.kind() == kind)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    // ---------------------------------------------------------------------------- payloads

    /**
     * The event body, common to every lifecycle event.
     *
     * <p>A map rather than a serialised string, so the same shape serves the response and the event and a
     * field cannot be added to one and forgotten in the other. Amounts go out as decimal strings for the
     * same reason they arrive that way.
     *
     * <p>Carries the aggregate version, which is the deduplication key. Delivery is at least once, so a
     * consumer will see some of these twice, and it cannot tell that from a new event without this
     * number.
     */
    private Versioned payload(Transaction transaction, Instant now) {
        long version = flushedVersion(transaction);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("transactionId", transaction.id().toString());
        body.put("ownerSubjectDigest", transaction.ownerSubjectDigest());
        body.put("amount", transaction.amount().toDecimalString());
        body.put("currency", transaction.currency().getCurrencyCode());
        body.put("status", transaction.status().name());
        body.put("payeeName", transaction.payee().name());
        body.put("occurredAt", now.toString());
        body.put("version", version);
        return new Versioned(body, version);
    }

    /**
     * The {@code transaction.created} body, which is the only one carrying a fraud context.
     *
     * <p>Creation is the right and only place for it. The context describes the <em>attempt</em> — the
     * device it came from, the network it arrived on, the channel — and those are facts about the moment
     * the payment was made, not about its later life. Emitting them again on settlement or reversal would
     * tell the fraud engine that a refund came from a device, which is not a fact and would be scored as
     * though it were.
     *
     * <p>The context is also the last event before authorisation, which is what makes it the right
     * trigger: a payment that is about to be declined for want of funds still gets scored, because a
     * burst of declined attempts is itself the signal. Counting only authorised payments would let an
     * attacker probe a card's limit for free.
     */
    private Versioned createdPayload(Transaction transaction, FraudContext context, Instant now) {
        Versioned event = payload(transaction, now);
        event.body().put("payeeReference", transaction.payee().reference());
        // Always present, even when empty, so a consumer can tell "the producer sends no context" from
        // "this payment had no context to send". Both mean the same thing to a rule, and both are
        // recorded in the decision's facts.
        event.body().put("fraudContext", context == null ? Map.of() : context.toEventBody());
        return event;
    }

    /**
     * An event body together with the version it is to be recorded at.
     *
     * <p>Carried as one value so the number in the body a consumer reads and the number in the outbox row
     * the relay sends cannot come from two separate reads and drift apart.
     */
    private record Versioned(Map<String, Object> body, long version) {}

    /**
     * The payment's version after this state change has been written.
     *
     * <p><b>Flushed, then read back from the row, and both halves are load-bearing.</b> Without the flush
     * the database still holds the previous version. Without the read-back the entity's own field is not
     * the version the row has — {@code Transaction.create} assigns the id, Spring Data's newness detection
     * reads a primitive {@code @Version} as "not new" and takes the merge path, so the instance the
     * service is mutating is detached from the one being written and keeps reporting 0.
     *
     * <p>Together they made {@code transaction.created} and {@code transaction.authorized} both record
     * version 0. Delivery is at least once and consumers are told to deduplicate on the aggregate version,
     * so a consumer that had seen the creation discarded the authorisation as a redelivery and silently
     * stopped applying payments. Nothing threw, nothing was logged, and the events were in the topic in
     * the wrong order — the failure mode where the symptom is an absence.
     *
     * <p>Reading the row rather than trusting the object is the part that is not obvious, and the extra
     * {@code SELECT} is the price of never publishing a number the database has not agreed to. Nothing is
     * committed here: a flush sends the statement and takes the row lock, the transaction still commits or
     * rolls back as one, and the deferred balance and conservation triggers still fire at the end of it.
     */
    private long flushedVersion(Transaction transaction) {
        transactions.saveAndFlush(transaction);
        return transactions.readVersionById(transaction.id());
    }

    private Versioned declinedPayload(Transaction transaction, String reason, Instant now) {
        Versioned event = payload(transaction, now);
        event.body().put("declineReason", reason);
        return event;
    }
}
