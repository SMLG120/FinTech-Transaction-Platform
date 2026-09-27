package com.fintech.platform.transaction.persistence;

import com.fintech.platform.transaction.domain.LedgerAccount;
import com.fintech.platform.transaction.domain.LedgerAccountType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Ledger account storage, including the only way to read an account for writing.
 *
 * <p><b>The lock is the whole point of this interface.</b> {@link #lockForUpdate} is the one method
 * that may be used before applying a posting, and it takes {@code PESSIMISTIC_WRITE}, which in Postgres
 * is {@code SELECT ... FOR UPDATE}. That row lock is what serialises concurrent payments against the
 * same account: two authorisations for the same customer both read the available balance, and without
 * the lock both see funds that only one of them can have. The second would then write a balance derived
 * from a stale read, and the customer's balance would be wrong by exactly one payment — the classic
 * double spend, and one that no amount of validation after the write would catch.
 *
 * <p>Every other read method is unlocked and is for display. There is deliberately no
 * {@code findByOwnerRefAndType} that a caller might reach for when writing a posting: the one
 * difference between them is the lock, and having two lookups for the same row invites the wrong one
 * being used where it matters.
 */
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    /**
     * Reads the accounts a posting will touch, locked, in a deterministic order.
     *
     * <p>Two things are deliberate here.
     *
     * <p><b>Ordered by id.</b> A posting touches two or more accounts and locks all of them, and two
     * transactions that touch the same accounts must take them in the same order or they deadlock: T1
     * locks available then reserved while T2 locks reserved then available, and each waits for the
     * other. The database would resolve it by killing one, which is correct but means a payment fails
     * for no reason a customer could understand. Ordering by id is arbitrary, which is the point — it is
     * arbitrary but <em>identical</em> in every transaction, so the cycle cannot form. (Ordering by
     * account type would also work and reads better, but only because the enum order is fixed; id is
     * used here so that the ordering cannot silently change if the enum is reordered.)
     *
     * <p><b>Locked in one statement.</b> A lock per row in a loop is correct too and deadlocks less
     * often, but it makes the locked set depend on iteration order of a query that has no ORDER BY,
     * which is precisely the mistake above. One statement, one order, no ambiguity.
     *
     * <p>Empty when an account does not exist. The service treats that as "no such account" rather than
     * creating one, because a silently created zero balance is indistinguishable from a real one and
     * turns "this customer was never funded" into a payment that fails for the wrong reason.
     *
     * @param ownerRef the customer digest, or {@code PlatformAccount.OWNER_REF}
     * @param types the account types the posting touches
     * @param currencyCode ISO 4217 code
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM LedgerAccount a
             WHERE a.ownerRef = :ownerRef
               AND a.type IN :types
               AND a.currencyCode = :currencyCode
             ORDER BY a.id
            """)
    List<LedgerAccount> lockForUpdate(
            @Param("ownerRef") String ownerRef,
            @Param("types") Collection<LedgerAccountType> types,
            @Param("currencyCode") String currencyCode);

    /**
     * Reads accounts for display, without a lock.
     *
     * <p>Unlocked because a balance shown to a customer does not need to be current to the
     * microsecond, and because taking a row lock to render a screen would make a read contend with every
     * payment against that account. A caller using this to decide whether a payment will succeed is
     * making a check that a concurrent payment can invalidate between the read and the write; the lock
     * has to be held across both, which is {@link #lockForUpdate}'s job.
     */
    @Query("""
            SELECT a FROM LedgerAccount a
             WHERE a.ownerRef = :ownerRef
               AND a.currencyCode = :currencyCode
             ORDER BY a.type
            """)
    List<LedgerAccount> findAllForOwner(@Param("ownerRef") String ownerRef, @Param("currencyCode") String currencyCode);

    Optional<LedgerAccount> findByOwnerRefAndTypeAndCurrencyCode(
            String ownerRef, LedgerAccountType type, String currencyCode);

    /**
     * Inserts an account unless the unique constraint already holds one, in one statement.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an existence check followed by a save, and
     * emphatically rather than a save whose constraint violation is caught. Catching the violation does
     * not work: once Postgres has raised it, the surrounding transaction is already doomed, and the
     * commit that follows throws {@code UnexpectedRollbackException} regardless of what the catch block
     * did. Absorbing a unique violation is not something a transaction can do halfway.
     *
     * <p>Doing it in the database also removes the window a check-then-insert leaves open. The check and
     * the insert are one atomic statement here, so there is no instant between "I looked and it was not
     * there" and "I inserted" for a second transaction to slip through. The losing transaction waits on
     * the unique index, wakes up having inserted nothing, and carries on to the posting — which locks the
     * account the winner created and sees the committed balance.
     *
     * <p>Native, and takes scalars rather than the entity, because an entity argument would be persisted
     * through the persistence context and then bypassed by the statement, leaving two disagreeing views
     * of the same row inside one transaction.
     *
     * @return 1 if this call created the account, 0 if it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO ledger_accounts (id, owner_ref, type, currency_code, balance_minor, version)
            VALUES (:id, :ownerRef, :type, :currencyCode, 0, 0)
            ON CONFLICT ON CONSTRAINT ledger_accounts_owner_type_currency_unique DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("ownerRef") String ownerRef,
            @Param("type") String type,
            @Param("currencyCode") String currencyCode);
}
