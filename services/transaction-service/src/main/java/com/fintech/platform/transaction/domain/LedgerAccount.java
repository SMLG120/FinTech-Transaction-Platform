package com.fintech.platform.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * One account in the double-entry ledger, and the materialised balance of everything posted to it.
 *
 * <p><b>The balance column is a cache, not a fact.</b> The journal is the record; this number is the
 * sum of it, maintained in the same transaction so that "may this customer spend fifty pounds?" is one
 * indexed read instead of an aggregate over a table that grows without bound. It is kept honest by
 * being written from the same {@link LedgerPosting} that writes the lines, and by the conservation
 * test that sums this column across every account.
 *
 * <p><b>Not thread-safe, and the database is what makes concurrent use safe.</b> A posting reads the
 * balance, applies a change and writes it back. Two postings doing that to one account concurrently
 * would both read the same starting balance and both authorise, so the read is a
 * {@code SELECT ... FOR UPDATE} taken by the service. The {@link #version} column is not redundant
 * with that lock: the lock protects a multi-row posting inside one transaction, and the version
 * catches a lost update from a code path that reads and writes outside that discipline.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    private UUID id;

    /**
     * The customer whose money this is, as a keyed digest, or a fixed platform code for a platform
     * account.
     *
     * <p>Never the raw subject, for the same reason card-service stores a digest rather than a
     * subject: an account row is a thing an auditor reads, and the subject is personal data. The
     * platform accounts use a code in the same column rather than a null, because a unique constraint
     * over a nullable column does not constrain anything in Postgres — the rows would not collide and
     * the constraint would be decorative.
     */
    @Column(name = "owner_ref", nullable = false, length = 64)
    private String ownerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private LedgerAccountType type;

    /**
     * ISO 4217 code, stored as text.
     *
     * <p>Not a {@link Currency} column: JPA has no portable mapping for it, and the alternative an
     * {@code AttributeConverter} introduces is a second place where the domain's currency
     * representation can drift from the database's.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /**
     * The signed sum of every posting to this account, in minor units.
     *
     * <p>Signed because platform accounts are expected to be negative — a source of funds is a credit
     * balance. Customer accounts are held at or above zero by {@link #apply}, and the rule is per
     * account {@link LedgerAccountType#type} rather than global.
     */
    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LedgerAccount() {}

    private LedgerAccount(UUID id, String ownerRef, LedgerAccountType type, Currency currency) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerRef = requireText(ownerRef, "ownerRef");
        this.type = Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        this.currencyCode = currency.getCurrencyCode();
        this.balanceMinor = 0;
    }

    public static LedgerAccount customer(
            UUID id, String ownerSubjectDigest, LedgerAccountType type, Currency currency) {
        if (!type.isCustomerAccount()) {
            throw new IllegalArgumentException("not a customer account type: " + type);
        }
        return new LedgerAccount(id, ownerSubjectDigest, type, currency);
    }

    public static LedgerAccount platform(UUID id, LedgerAccountType type, Currency currency) {
        if (type.isCustomerAccount()) {
            throw new IllegalArgumentException("not a platform account type: " + type);
        }
        return new LedgerAccount(id, PlatformAccount.OWNER_REF, type, currency);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String ownerRef() {
        return ownerRef;
    }

    public LedgerAccountType type() {
        return type;
    }

    public Currency currency() {
        return Currency.getInstance(currencyCode);
    }

    public long balanceMinor() {
        return balanceMinor;
    }

    public Money balance() {
        return Money.minor(balanceMinor, currency());
    }

    public long version() {
        return version;
    }

    /**
     * Applies one journal line to this account and returns the balance it left behind.
     *
     * <p>Refuses a posting that would take a customer account below zero. This is the overdraft rule,
     * and it is enforced here rather than by a check in the service because the service is where a
     * future posting type gets added, whereas every line reaches this method.
     *
     * @throws IllegalArgumentException if the currency differs from the account's, or if the result
     *     would be negative on an account that may not go negative
     */
    public long apply(PostingDirection direction, Money amount) {
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Money accountBalance = balance();
        if (!amount.currency().equals(currency())) {
            throw new IllegalArgumentException(
                    "cannot post " + amount.currency().getCurrencyCode() + " to a "
                            + currency().getCurrencyCode() + " account; this platform does not convert currency");
        }
        long updated = direction.applyTo(balanceMinor, amount.minorUnits());
        if (type.mustStayNonNegative() && updated < 0) {
            throw new InsufficientFundsException(type, amount, accountBalance);
        }
        this.balanceMinor = updated;
        return updated;
    }

    /** Spendable money, for a customer account. Zero rather than null on a platform account. */
    public Money available() {
        return type == LedgerAccountType.CUSTOMER_AVAILABLE ? balance() : Money.zero(currency());
    }

    /** Held money, for a customer account. Zero rather than null on a platform account. */
    public Money held() {
        return type == LedgerAccountType.CUSTOMER_RESERVED ? balance() : Money.zero(currency());
    }

    @Override
    public String toString() {
        return type + "(" + currencyCode + ") " + balance();
    }
}
