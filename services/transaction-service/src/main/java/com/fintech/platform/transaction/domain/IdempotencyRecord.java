package com.fintech.platform.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The record of one money-moving request, keyed by a key the caller chose.
 *
 * <p><b>Scoped to the payer, not global.</b> Two customers who both chose {@code order-1234} must not
 * collide, and a globally unique key would turn two unrelated merchants' ordinary references into one
 * another's 409. The key means "one of this customer's requests used this key", which is what a client
 * actually intends when it picks one.
 *
 * <p><b>Stores the response, not a pointer to it.</b> A replay is a read of this table. If the key
 * recorded only the resulting payment's id, a replay would have to reconstruct the response from a
 * second read of a row that has since been reversed — and the caller would be told their second
 * request failed, when in fact it succeeded an hour ago and has since been refunded. Storing the bytes
 * is cheap and makes the answer stable.
 *
 * <p><b>In-flight is a state, not an error.</b> {@link #responseBody()} is null until the first attempt
 * commits, and a repeat that finds it null is told the original is still running. Two concurrent
 * requests with one key are a client that sent the same intent twice; the second must not proceed and
 * must not block, because its purpose is to find out what the first concluded.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "owner_subject_digest", nullable = false, length = 64)
    private String ownerSubjectDigest;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /**
     * A digest of the request this key was first used with.
     *
     * <p>The thing that distinguishes "the same request, sent again" from "a different request wearing
     * an old key". The second is the dangerous one: treating it as a replay would return the first
     * one's response for a payment that was never made, and treating it as new would charge twice under
     * a key the caller believes is protecting them. It gets a 409.
     */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    /**
     * When the operation completed, or null while it is still in flight.
     *
     * <p>Null after a failure too, not only while running. A key pinned by a request that crashed would
     * turn one transient error into a payment the customer could never make, which is a worse outcome
     * than the duplicate the mechanism exists to prevent.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecord() {}

    private IdempotencyRecord(
            UUID id, String ownerSubjectDigest, String idempotencyKey, String requestFingerprint, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerSubjectDigest = requireText(ownerSubjectDigest, "ownerSubjectDigest");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public static IdempotencyRecord inFlight(
            UUID id, String ownerSubjectDigest, String idempotencyKey, String requestFingerprint, Instant now) {
        return new IdempotencyRecord(id, ownerSubjectDigest, idempotencyKey, requestFingerprint, now);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException(field + " must be at most 128 characters, got " + trimmed.length());
        }
        return trimmed;
    }

    /** Whether the operation behind this key has finished. */
    public boolean isCompleted() {
        return completedAt != null;
    }

    /** Whether this key was first used with a different request than the one now presented. */
    public boolean isForDifferentRequest(String candidateFingerprint) {
        return !this.requestFingerprint.equals(candidateFingerprint);
    }

    /**
     * Records the outcome, making the key replayable.
     *
     * <p>Writes all three fields or none, which the table's
     * {@code idempotency_completed_iff_response} CHECK enforces. Storing a status without a body would
     * make a replay answer {@code 201} with an empty payload, and a client would have no way to know
     * whether its payment succeeded.
     */
    public void complete(int status, String body, Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(body, "body must not be null");
        if (isCompleted()) {
            throw new IllegalStateException("idempotency key " + idempotencyKey + " is already completed");
        }
        this.responseStatus = status;
        this.responseBody = body;
        this.completedAt = at;
        this.updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public String ownerSubjectDigest() {
        return ownerSubjectDigest;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public int responseStatus() {
        if (responseStatus == null) {
            throw new IllegalStateException("idempotency key " + idempotencyKey + " has no recorded response");
        }
        return responseStatus;
    }

    public String responseBody() {
        if (responseBody == null) {
            throw new IllegalStateException("idempotency key " + idempotencyKey + " has no recorded response");
        }
        return responseBody;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        // The key itself is a caller-chosen string that may be an order reference, so it is not logged.
        return "IdempotencyRecord[" + ownerSubjectDigest + " key-hash completed=" + isCompleted() + "]";
    }
}
