package com.fintech.platform.transaction.service;

import com.fintech.platform.transaction.domain.IdempotencyRecord;
import com.fintech.platform.transaction.error.TransactionErrorCodes;
import com.fintech.platform.transaction.persistence.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a money-moving request is a first attempt, a replay, or a misuse of someone else's
 * key.
 *
 * <p><b>Database-backed, deliberately.</b> The authority is the unique constraint on
 * {@code (owner, key)}, and a cache could not be the authority even if one were added in front of
 * this: its contents do not survive it, and an idempotency record that evaporates turns a client's
 * retry into a second payment — which is the one outcome the whole mechanism exists to prevent. A
 * cache that can lose the thing it is caching is not a cache, it is a hope. No such fast path is
 * implemented; the constraint is consulted directly.
 *
 * <p><b>The key is claimed atomically, and that is not incidental.</b> The obvious implementation —
 * insert, then catch the unique-constraint exception when someone else won the race — does not work on
 * PostgreSQL. A constraint violation aborts the whole transaction, so the re-read that is supposed to
 * turn the race into a clean {@code IDEMPOTENT_REQUEST_IN_PROGRESS} fails with "current transaction is
 * aborted". Eight concurrent requests sharing one key then produce one success and seven 500s: the
 * duplicate-payment guarantee holds, and seven clients are told the platform is broken when the truth
 * is that their request is already in hand. The claim is therefore a single {@code INSERT ... ON
 * CONFLICT DO NOTHING} whose row count is the answer. See ADR-0007.
 *
 * <p><b>The key is the caller's, and required.</b> Generating one for a caller who omitted it would mean
 * a client that retries after a timeout without a key gets charged twice, and the API would have caused
 * that rather than the client. So the header is mandatory on money-moving requests and its absence is a
 * 400.
 *
 * <p><b>Concurrency is handled by the constraint, not by a check.</b> Two requests with one key arrive,
 * both find nothing, both try to claim it, and the constraint lets exactly one through. The loser sees
 * the winner's row and is told the request is in flight. A {@code SELECT} followed by an
 * {@code INSERT} without a constraint would let both through, and the second would overwrite the first's
 * stored response — after both payments had committed.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository records;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    /**
     * What the caller should do with this request.
     */
    public sealed interface Decision {

        /** The key is new; carry on and record the outcome. */
        record Proceed(IdempotencyRecord record) implements Decision {}

        /**
         * The same key, the same request, already completed.
         *
         * @param status the recorded status, replayed verbatim
         * @param body the recorded body, replayed verbatim rather than recomputed
         */
        record Replay(int status, String body) implements Decision {}

        /** The same key, a different request. A client bug, and a loud 409. */
        record Conflict() implements Decision {}

        /** The same key, still running. 409 with Retry-After. */
        record InProgress() implements Decision {}
    }

    /**
     * Classifies a request against its key.
     *
     * <p>Called before the operation runs, and it claims the key in the same transaction as the
     * operation — which is what makes the claim and the payment atomic. If the payment rolls back, so
     * does the claim, and the key is free for a genuine retry. Claiming in a separate transaction first
     * would leave keys permanently claimed by requests that never completed.
     *
     * @param requestFingerprint a digest of the method, path and canonical body
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision decide(String ownerSubjectDigest, String idempotencyKey, String requestFingerprint) {
        String key = requireKey(idempotencyKey);
        Optional<IdempotencyRecord> existing =
                records.findByOwnerSubjectDigestAndIdempotencyKey(ownerSubjectDigest, key);

        if (existing.isPresent()) {
            return classify(existing.get(), requestFingerprint);
        }

        // The claim is a row count, not an exception. Two requests with one key both find nothing here and
        // both try to claim; the constraint lets exactly one through, and the other is told the request is
        // in flight. Letting the constraint raise instead would abort the loser's transaction on
        // PostgreSQL, taking with it the payment, the ledger posting and the stored response that were
        // about to commit together — so the loser would get a 500 rather than the 409 this decision exists
        // to return. The read above is only a fast path; the count below is the authority.
        UUID claimId = UUID.randomUUID();
        Instant now = clock.instant();
        int claimed = records.claim(claimId, ownerSubjectDigest, key, requestFingerprint, now, now);

        if (claimed == 1) {
            return new Decision.Proceed(records.findById(claimId)
                    .orElseThrow(() -> new IllegalStateException(
                            "the idempotency claim was inserted as " + claimId + " but cannot be read back")));
        }

        // Lost the race. The winner has committed, so its row is there to be read and classified: a
        // different fingerprint is a 409, an unfinished one is a 409 with Retry-After, and a finished one is
        // a replay. The fallback covers a winner that committed and was removed in between, which nothing
        // currently does but which would be a silent 500 if it happened.
        log.debug("idempotency key claimed concurrently for owner {}", ownerSubjectDigest);
        return records.findByOwnerSubjectDigestAndIdempotencyKey(ownerSubjectDigest, key)
                .map(record -> classify(record, requestFingerprint))
                .orElseGet(() -> new Decision.InProgress());
    }

    private Decision classify(IdempotencyRecord record, String requestFingerprint) {
        if (record.isForDifferentRequest(requestFingerprint)) {
            return new Decision.Conflict();
        }
        if (!record.isCompleted()) {
            return new Decision.InProgress();
        }
        return new Decision.Replay(record.responseStatus(), record.responseBody());
    }

    /**
     * Records the outcome, making the key replayable.
     *
     * <p>Called after the operation succeeded and before the transaction commits, so the stored response
     * and the payment it describes commit together. Committing the payment and losing the record would
     * mean the next retry of the same key ran the payment again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(IdempotencyRecord record, int status, String body) {
        record.complete(status, body, clock.instant());
        records.save(record);
    }

    /**
     * A digest of the request's identity, for detecting a key reused with different content.
     *
     * <p>A digest rather than the body itself: this column is compared on every replay and storing the
     * body would put a second copy of the request — which contains an amount and a payee — in a table
     * whose whole job is to be small. A hash comparison is sufficient, because the question is "is this
     * the same request" and not "what did it say".
     *
     * <p>The caller supplies an already-canonical string, so that two requests differing only in field
     * order or in insignificant whitespace are correctly recognised as the same. Two clients that
     * serialise the same JSON differently would otherwise be told they had reused a key when they had
     * not, and would be refused a payment for a difference neither of them can see.
     */
    public static String fingerprint(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM by the specification. Reaching this means a broken JRE,
            // and failing loudly beats silently falling back to a weaker digest for a duplicate-payment
            // guard.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Rejects a missing, blank or oversized key before it reaches the database. */
    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw TransactionErrorCodes.IDEMPOTENCY_KEY_REQUIRED.exception();
        }
        String trimmed = key.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be at most 128 characters, got " + trimmed.length());
        }
        return trimmed;
    }
}
