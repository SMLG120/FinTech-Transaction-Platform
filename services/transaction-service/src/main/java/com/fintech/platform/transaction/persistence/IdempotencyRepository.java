package com.fintech.platform.transaction.persistence;

import com.fintech.platform.transaction.domain.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Idempotency key storage.
 *
 * <p>Looked up by {@code (owner, key)} and never by key alone, for the same reason the column is
 * scoped: the key is the customer's, and two customers using the same ordinary reference must not see
 * each other's responses.
 */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByOwnerSubjectDigestAndIdempotencyKey(
            String ownerSubjectDigest, String idempotencyKey);

    boolean existsByOwnerSubjectDigestAndIdempotencyKey(String ownerSubjectDigest, String idempotencyKey);

    /**
     * Claims a key, or reports that someone else already holds it.
     *
     * <p><b>{@code ON CONFLICT DO NOTHING} rather than letting the constraint raise.</b> A plain
     * {@code INSERT} from two requests with one key makes the loser take a
     * {@code DataIntegrityViolationException}, and on PostgreSQL that aborts the whole transaction — the
     * very transaction whose payment, ledger posting and stored response were about to commit together.
     * The catch that tries to recover then fails on the re-read, because PostgreSQL refuses every
     * statement until the block ends. So the loser does not get the 409 the mechanism exists to give
     * them; it gets a 500, and the transaction that would have carried the work is gone.
     *
     * <p>Measured on eight concurrent requests with one key: one {@code Proceed} and seven
     * {@code JpaSystemException}, rather than one and seven {@code InProgress}. The constraint still did
     * its job — only one payment ran — but the callers who lost the race were told the platform had
     * failed rather than that their request was already in hand, which is the one thing a retrying client
     * most needs to be told correctly.
     *
     * <p>Returning a row count instead of raising turns losing the race into ordinary control flow. One
     * means the claim is ours and the caller may proceed; zero means another committed transaction holds
     * the key, and the record can be read and classified. {@code DO NOTHING} also blocks until that
     * transaction resolves, so a zero means the winner committed rather than merely started — and if the
     * winner rolled back, this insert succeeds instead, which is the correct outcome: a failed payment
     * must not pin its key.
     *
     * <p>The same approach as {@code LedgerAccountRepository.insertIfAbsent}, for the same reason.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (id, owner_subject_digest, idempotency_key, request_fingerprint, created_at, updated_at)
            VALUES (:id, :ownerSubjectDigest, :idempotencyKey, :requestFingerprint, :createdAt, :updatedAt)
            ON CONFLICT ON CONSTRAINT idempotency_owner_key_unique DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("id") UUID id,
            @Param("ownerSubjectDigest") String ownerSubjectDigest,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);
}
