package com.fintech.platform.fraud.identity;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key fraud-service uses to digest the subjects it records.
 *
 * <p><b>Staff, and only staff.</b> This key digests analyst identities onto alert timelines and audit
 * events. A customer's subject is never seen by this service — it arrives already digested in the
 * {@code transaction.created} event and is stored as received — so there is nothing here for this key to
 * be used on.
 *
 * <p><b>It does not isolate customer digests, and no comment in this repository should claim that it
 * does.</b> The {@code ownerSubjectDigest} on a {@code risk_decisions} row is transaction-service's
 * digest, not this service's, because this service has no way to produce a different one and re-deriving
 * one would require a second key covering customers, which would be strictly worse. The consequence is
 * worth stating plainly rather than leaving to be discovered: <em>someone holding both databases can
 * join a customer's payments across the two services.</em> That is an accepted trade — the correlation
 * has to happen somewhere, the alternative is raw subjects here, and it is a documented property of the
 * design rather than an accident. It is why this database's access control and its retention matter, and
 * it is why ADR-0008 records it.
 *
 * <p>What this key <em>is</em> for is the direction that matters: because the fraud key is separate and
 * this service never holds transaction-service's, a compromise of this database yields analyst digests
 * that are useless for attacking anything else, and cannot be turned back into subjects by anyone. The
 * reverse would not be true if the keys were shared, which is the platform's per-service rule from
 * ADR-0002 and the reason for a second key at all.
 *
 * <p>No default. A service that starts without its key must fail rather than run under a key someone
 * copied into a sample file, because a shared key is not a secret and every digest derived under it is
 * reversible by whoever has the list of subjects.
 *
 * <p>Held as a base64 {@code String} and decoded on demand, for the reason every secret in this platform
 * is: {@code byte[]} does not bind from an environment variable, and the deployment fails with a
 * conversion error about a number, because the key is hex-shaped and the binder tries to read it as one.
 */
@ConfigurationProperties(prefix = "platform.security.subject-digest")
public record FraudDigestKey(String key) {

    /** HMAC-SHA256's minimum sensible key length. A shorter key is truncated, which is a way of saying it was too weak. */
    public static final int MIN_KEY_BYTES = 32;

    public byte[] decode() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("SUBJECT_DIGEST_KEY is not set; fraud-service will not start without it");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SUBJECT_DIGEST_KEY is not valid base64", e);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            // The requirement and the length, never the value: this message can end up in a startup log.
            throw new IllegalStateException(
                    "SUBJECT_DIGEST_KEY must decode to at least " + MIN_KEY_BYTES + " bytes, got " + decoded.length);
        }
        return decoded;
    }
}
