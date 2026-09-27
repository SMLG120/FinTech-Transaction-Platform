package com.fintech.platform.customer.pii;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform's PII encryption key and the generation it belongs to.
 *
 * <p>Validated at binding time rather than at first use. A service that starts without a key and
 * discovers the problem when it writes a customer's date of birth is a slow, invisible failure; a
 * service that refuses to start is a short, loud one. That is the whole reason this is a record with a
 * compact constructor that throws.
 *
 * <p>No default for {@code master-key}, unlike nearly every other setting in this service. The empty
 * default in {@code application.yml} is not a fallback value, it is what lets Spring attempt the bind
 * and produce a diagnostic naming the property and the environment variable; the constructor then
 * refuses.
 *
 * <p>The key is a 32-byte AES-256 key, supplied base64-encoded because that is the only way to get
 * arbitrary bytes through an environment variable, a Compose file and a Kubernetes secret without
 * anyone having to think about shell quoting.
 *
 * @param masterKey base64-encoded 32-byte AES-256 key
 * @param keyVersion which generation of the key wrote this ciphertext; null means 1
 */
@ConfigurationProperties(prefix = "platform.customer.pii")
public record PiiProperties(String masterKey, Integer keyVersion) {

    /**
     * The key length AES-256 requires, in bytes.
     *
     * <p>Checked rather than assumed, because a truncated key produces ciphertext that decrypts to
     * nothing and an over-long one is silently ignored by some providers, and both look identical from
     * the outside until the data is unrecoverable.
     */
    private static final int KEY_BYTES = 32;

    public PiiProperties {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    "platform.customer.pii.master-key is required; set the CUSTOMER_PII_MASTER_KEY"
                            + " environment variable to a base64-encoded 32-byte key (see scripts/bootstrap.sh)");
        }
        if (keyVersion != null && keyVersion < 1) {
            // Checked here rather than defaulted. A version of 0 would be a perfectly readable integer
            // that stamps every ciphertext with a generation nothing will ever be able to read back.
            throw new IllegalStateException("platform.customer.pii.key-version must be >= 1, got: " + keyVersion);
        }
        // Decoded here, not lazily in resolvedKey(). A malformed key has to stop the service at
        // startup, and the only moment guaranteed to run for a configuration object nobody has asked
        // for anything yet is its own construction.
        decodeOrFail(masterKey);
    }

    private static byte[] decodeOrFail(String value) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            // The decoder's own message names neither the property nor the expected length, so an
            // operator would be left guessing which of several things was wrong.
            throw new IllegalStateException(
                    "platform.customer.pii.master-key must be valid base64; set CUSTOMER_PII_MASTER_KEY", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException("platform.customer.pii.master-key must decode to exactly " + KEY_BYTES
                    + " bytes for AES-256, got " + key.length + " bytes");
        }
        return key;
    }

    /**
     * @return the decoded AES key
     * @throws IllegalStateException if the value is not base64 or not {@value #KEY_BYTES} bytes
     */
    public SecretKeySpec resolvedKey() {
        return new SecretKeySpec(decodeOrFail(masterKey), "AES");
    }

    /** @return the key version, defaulting to 1 when unset */
    public int resolvedKeyVersion() {
        return keyVersion == null ? 1 : keyVersion;
    }
}
