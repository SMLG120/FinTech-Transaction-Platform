package com.fintech.platform.transaction.domain;

import com.fintech.platform.transaction.error.TransactionErrorCodes;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment, from the moment it is accepted to the moment it is settled or undone.
 *
 * <p>The aggregate is the payment and its status, not the ledger. {@link LedgerAccount} and
 * {@link JournalEntry} are separate aggregates, and the fact that a transition here and a posting there
 * must succeed or fail together is enforced by the service holding one database transaction over both
 * rather than by either object knowing about the other. A status change without its posting is
 * money that was never reserved; a posting without its status change is money that moved with no
 * payment to explain it, and the second is the one an auditor finds first.
 *
 * <p>Immutable in the directions that matter. {@code payee}, {@code amount} and {@code cardToken} are
 * set at construction and never change: a payment that could be edited after it was authorised is a
 * payment whose receipt and whose ledger disagree. {@link #status} moves only through
 * {@link TransactionStateMachine}.
 *
 * <p>Carries the card <b>token</b>, never a card number. card-service returns a token and this service
 * has no way to obtain the number it stands for, which is the design: a service that cannot read a PAN
 * cannot leak one from a log line or a debug dump.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    /**
     * The payer, as a keyed digest of the subject.
     *
     * <p>A digest rather than the subject for the same reason card-service does it: a transaction row
     * is data an auditor and a support agent both read, and a raw sub is personal data in both those
     * places. The digest is what lets "is this caller the payer?" be answered without storing who the
     * payer is.
     */
    @Column(name = "owner_subject_digest", nullable = false, length = 64)
    private String ownerSubjectDigest;

    /**
     * The tokenised card the payment was made with.
     *
     * <p>A token. The 16 digits this platform never stores are not an option here, and a token that
     * can be traced back to a specific card is enough to satisfy the receipt and the ledger link
     * without being enough to authorise a new payment.
     */
    @Column(name = "card_token", nullable = false, length = 64)
    private String cardToken;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Embedded
    private Counterparty payee;

    /**
     * Why the payment was declined, or null.
     *
     * <p>Set only alongside {@code DECLINED}. A decline reason on a live payment would be a claim the
     * platform is making about money that has not moved, and a caller branching on it would be
     * branching on a thing the platform may later contradict.
     */
    @Column(name = "decline_reason", length = 64)
    private String declineReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Transaction() {}

    /**
     * Creates a payment in {@link TransactionStatus#PENDING}.
     *
     * <p>The amount is validated here rather than accepted, because a zero or negative amount is not a
     * payment that will fail later — it is a payment whose ledger entry cannot be built, and finding
     * that out at the posting stage means the transaction row already exists and needs cleaning up.
     */
    public static Transaction create(
            UUID id, String ownerSubjectDigest, String cardToken, Money amount, Counterparty payee, Instant createdAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ownerSubjectDigest, "ownerSubjectDigest must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(payee, "payee must not be null");
        if (ownerSubjectDigest.isBlank()) {
            throw new IllegalArgumentException("ownerSubjectDigest must not be blank");
        }
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        if (cardToken.isBlank()) {
            throw new IllegalArgumentException("cardToken must not be blank");
        }
        if (!amount.isPositive()) {
            throw TransactionErrorCodes.AMOUNT_NOT_ALLOWED.exception(
                    "A payment amount must be positive, got " + amount);
        }
        Transaction transaction = new Transaction();
        transaction.id = id;
        transaction.ownerSubjectDigest = ownerSubjectDigest;
        transaction.cardToken = cardToken;
        transaction.amountMinor = amount.minorUnits();
        transaction.currencyCode = amount.currency().getCurrencyCode();
        transaction.status = TransactionStatus.PENDING;
        transaction.payee = payee;
        transaction.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        return transaction;
    }

    /**
     * Records an approval and returns {@code this}.
     *
     * @throws com.fintech.platform.common.error.ApiException if the payment is not pending, which is
     *     what stops a replayed authorisation from reserving the money twice
     */
    public Transaction authorize(Instant at) {
        TransactionStateMachine.require(status, TransactionStatus.AUTHORIZED);
        this.status = TransactionStatus.AUTHORIZED;
        this.authorizedAt = Objects.requireNonNull(at, "at must not be null");
        return this;
    }

    /**
     * Records a refusal and returns {@code this}.
     *
     * <p>The reason is required rather than optional. A decline with no stated reason is one a support
     * agent cannot act on and a merchant cannot reconcile against, and "we said no" is not a category
     * the platform can offer a customer of a decline.
     */
    public Transaction decline(String reason, Instant at) {
        TransactionStateMachine.require(status, TransactionStatus.DECLINED);
        Objects.requireNonNull(reason, "a decline reason is required");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("decline reason must not be blank");
        }
        this.status = TransactionStatus.DECLINED;
        this.declineReason = reason;
        return this;
    }

    /**
     * Records capture and returns {@code this}.
     *
     * <p>Rejects a capture of a payment that was never authorised, which the state machine already
     * prevents, and which matters because a capture moves money out of a hold that has to exist: a
     * capture of an unauthorised payment is money leaving an account that never had it reserved.
     */
    public Transaction settle(Instant at) {
        if (status != TransactionStatus.AUTHORIZED) {
            throw TransactionErrorCodes.invalidStateTransition(status, TransactionStatus.SETTLED);
        }
        this.status = TransactionStatus.SETTLED;
        this.settledAt = Objects.requireNonNull(at, "at must not be null");
        return this;
    }

    /**
     * Records a reversal and returns {@code this}.
     *
     * <p>Reachable from both {@code AUTHORIZED} and {@code SETTLED}, and the distinction is kept in
     * the journal rather than here: the two produce different postings — one releases a hold, the other
     * reverses a capture — and the entry that named the original is what says which happened. The status
     * is the same because the question a caller asks afterwards is the same in both cases.
     */
    public Transaction reverse(Instant at) {
        TransactionStateMachine.require(status, TransactionStatus.REVERSED);
        this.status = TransactionStatus.REVERSED;
        this.reversedAt = Objects.requireNonNull(at, "at must not be null");
        return this;
    }

    public UUID id() {
        return id;
    }

    public String ownerSubjectDigest() {
        return ownerSubjectDigest;
    }

    public String cardToken() {
        return cardToken;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public Currency currency() {
        return Currency.getInstance(currencyCode);
    }

    public Money amount() {
        return Money.minor(amountMinor, currency());
    }

    public TransactionStatus status() {
        return status;
    }

    public Counterparty payee() {
        return payee;
    }

    public String declineReason() {
        return declineReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant authorizedAt() {
        return authorizedAt;
    }

    public Instant settledAt() {
        return settledAt;
    }

    public Instant reversedAt() {
        return reversedAt;
    }

    /**
     * The optimistic-locking version held on this instance.
     *
     * <p><b>Not safe to use as an event's aggregate version.</b> This field does not reliably track what
     * the database holds: after a flush the row can be at version 1 while this object still reads 0,
     * because {@code Transaction.create} assigns the id and Spring Data's newness detection then takes the
     * merge path, leaving the caller's instance detached from the one being written. Anything that
     * publishes a version to the outside world must read it from the database — see
     * {@code TransactionService#flushedVersion}.
     */
    public long version() {
        return version;
    }

    /**
     * Whether the money behind this payment is still the platform's to undo.
     *
     * <p>True for a hold and for a capture, false once reversed and false for a decline that never
     * moved anything. Read by the service to decide between a release posting and a capture reversal.
     */
    public boolean hasMoneyInPlay() {
        return status == TransactionStatus.AUTHORIZED || status == TransactionStatus.SETTLED;
    }

    /** Whether this transaction settled, which is what a spending-limit total should count. */
    public boolean countsAgainstDailyLimit() {
        return status == TransactionStatus.AUTHORIZED || status == TransactionStatus.SETTLED;
    }

    @Override
    public String toString() {
        return "Transaction[" + id + " " + status + " " + amount() + " -> " + payee + "]";
    }
}
