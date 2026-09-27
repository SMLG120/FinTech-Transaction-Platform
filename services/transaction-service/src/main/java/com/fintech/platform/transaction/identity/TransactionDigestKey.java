package com.fintech.platform.transaction.identity;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key transaction-service uses to derive subject digests.
 *
 * <p>Its own key, separate from every other service's, which is what makes a subject in this database
 * unjoinable to a subject in any other. See {@link SubjectDigester}.
 *
 * <p>No default. A service that starts without its key must fail rather than run with a key someone
 * chose for convenience in a sample file, because a shared or hard-coded key is a key that is not
 * secret and every digest derived under it is reversible.
 *
 * <p>Held as a base64 {@code String} and decoded on demand, matching
 * {@code CardTokenisationProperties} and {@code CustomerPiiProperties}. Declaring this as
 * {@code byte[]} is the obvious thing to write and it does not work: Spring's relaxed binding will not
 * convert an environment variable to {@code byte[]}, and the deployment fails at startup with a
 * {@code ConversionFailedException} about a number — the key is hex-shaped, so the binder tries to read
 * it as one. The setting arrives from a variable, always as text, and the class takes the same shape
 * every other secret in the platform takes.
 */
@ConfigurationProperties(prefix = "platform.security.subject-digest")
public record TransactionDigestKey(String key) {

    /** Minimum key length in bytes, for HMAC-SHA256. Shorter keys are truncated by the algorithm, which is a way of saying the operator's key was too weak. */
    public static final int MIN_KEY_BYTES = 32;

    public byte[] decode() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "SUBJECT_DIGEST_KEY is not set; transaction-service will not start without it");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SUBJECT_DIGEST_KEY is not valid base64", e);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            // The length and the requirement, never the value: this ends up in a startup log.
            throw new IllegalStateException(
                    "SUBJECT_DIGEST_KEY must decode to at least " + MIN_KEY_BYTES + " bytes, got " + decoded.length);
        }
        return decoded;
    }
}
