package com.fintech.platform.transaction.persistence;

import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.domain.TransactionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Payment storage.
 *
 * <p>As with cards, every method that can be answered from a row is scoped to the payer rather than
 * filtering in memory after a load. {@link #findByIdAndOwnerSubjectDigest} is the ownership check: a
 * payment that is not the caller's is never loaded, so no bug in a view or a serialiser can turn a
 * forbidden payment into a readable one.
 */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdAndOwnerSubjectDigest(UUID id, String ownerSubjectDigest);

    /**
     * The payment's version as the database currently holds it.
     *
     * <p>Exists because the version on the entity instance cannot be trusted. {@code Transaction.create}
     * assigns the id, Spring Data's newness detection then reads a primitive {@code @Version} as "not
     * new", and {@code save()} takes the merge path — so the object the service is still mutating is
     * detached from the instance actually being written, and a flush can leave the row at version 1 while
     * that object still reads 0.
     *
     * <p>That is only a problem for something that <em>publishes</em> the version, and that is precisely
     * what an outbox event does. A consumer deduplicating on it would treat a fresh authorisation as a
     * redelivery of the creation and drop it, with nothing thrown anywhere. So the number that goes into
     * the event is read back from the row, which is by definition the version the row has.
     */
    @Query("SELECT t.version FROM Transaction t WHERE t.id = :id")
    long readVersionById(@Param("id") UUID id);

    List<Transaction> findByOwnerSubjectDigestOrderByCreatedAtDesc(String ownerSubjectDigest, Pageable pageable);

    @Query("""
            SELECT t FROM Transaction t
             WHERE t.ownerSubjectDigest = :ownerSubjectDigest
             ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<Transaction> listForOwner(@Param("ownerSubjectDigest") String ownerSubjectDigest, Pageable pageable);

    /**
     * The customer's payments created on a given UTC day, for the daily spending limit.
     *
     * <p>Computed from {@code created_at} in UTC rather than in a local time zone on purpose. A limit
     * that resets at a different instant depending on where the request was routed is one a customer
     * cannot predict, and the boundary would move with daylight saving if the zone did.
     *
     * <p>Only live payments are counted. A decline moved no money, and a reversal undid what it counted,
     * so including either would make the total differ from the money actually spent — which is the only
     * definition of a spending limit a customer would accept.
     */
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.ownerSubjectDigest = :ownerSubjectDigest
               AND t.createdAt >= :dayStart
               AND t.createdAt < :dayEnd
               AND t.status IN :statuses
            """)
    List<Transaction> findLiveForOwnerOnDay(
            @Param("ownerSubjectDigest") String ownerSubjectDigest,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd,
            @Param("statuses") List<TransactionStatus> statuses);

    /** A payment looked up by the card token, for reconciliation against a network. */
    @Query("SELECT t FROM Transaction t WHERE t.cardToken = :cardToken ORDER BY t.createdAt DESC")
    List<Transaction> findByCardToken(@Param("cardToken") String cardToken, Pageable pageable);
}
