package com.fintech.platform.customer.pii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The only thing standing between a database dump and a list of real identities.
 *
 * <p>Four decisions define this class, and each is load-bearing for erasure rather than for
 * confidentiality:
 *
 * <ol>
 *   <li><b>Keys are derived per customer</b> with HKDF-SHA256, salted by the Keycloak subject. A
 *       master key used directly would put every customer behind one secret, and would make erasure
 *       impossible in the only sense that matters: the operator holding the master key could still read
 *       everything they had been asked to delete. Deriving per customer means nulling the subject
 *       removes the only input to derivation, so retained ciphertext is unreadable even to us.
 *   <li><b>HKDF is implemented here rather than taken from a library</b> because the derivation is now
 *       part of the storage format: changing it would make every existing row permanently unreadable
 *       while leaving the code obviously correct. It is pinned to RFC 5869 test vectors in
 *       {@code PiiCipherTest} so that a refactor cannot do that quietly.
 *   <li><b>The payload carries its key version</b> so a rotation can read the previous generation
 *       instead of turning a deploy into an outage.
 *   <li><b>Blind indexes are domain-separated from the subject digest</b>, so neither can be replayed
 *       into the other's lookup and a deleted identity cannot be rediscovered as a searchable value.
 * </ol>
 *
 * <p>The payload layout is {@code keyVersion(1) || iv(12) || ciphertext+tag}, base64url without padding.
 *
 * <p>A component rather than a {@code @Bean} in a configuration class, so it is picked up by the same
 * component scan as the rest of the service. {@link PiiProperties} needs no accompanying
 * {@code @EnableConfigurationProperties}: the application's {@code @ConfigurationPropertiesScan}
 * already covers it.
 */
@Component
public class PiiCipher {

    /**
     * Domain separation for the encryption key.
     *
     * <p>Versioned, so a future change to the construction gets a new value rather than silently
     * making old rows unreadable. The value is asserted in {@code PiiCipherTest}, so it cannot drift
     * without a test failing.
     */
    static final byte[] HKDF_INFO = "fintech.customer.pii.v1".getBytes(StandardCharsets.UTF_8);

    /** Separate from {@link #HKDF_INFO} so a subject digest can never be used as an email lookup. */
    private static final byte[] BLIND_INDEX_INFO = "fintech.customer.pii.bidx.v1".getBytes(StandardCharsets.UTF_8);

    private static final byte[] SUBJECT_DIGEST_INFO =
            "fintech.customer.pii.subject.v1".getBytes(StandardCharsets.UTF_8);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM's authentication tag, in bits. 128 is the standard, not a weakened choice. */
    private static final int TAG_BITS = 128;

    /**
     * GCM's standard nonce length, in bytes. A random 96-bit nonce is the size GCM is specified for and
     * the size that keeps the collision probability negligible for the volume this will ever see.
     */
    private static final int IV_BYTES = 12;

    private static final int KEY_BYTES = 32;

    private final SecretKeySpec masterKey;
    private final int keyVersion;
    private final SecureRandom random;

    /**
     * @param masterKey the platform key; must be 32 bytes for AES-256
     * @param keyVersion stamped into payloads and checked on read
     * @param random source of the per-value nonce
     */
    PiiCipher(SecretKeySpec masterKey, int keyVersion, SecureRandom random) {
        if (masterKey == null) {
            throw new IllegalArgumentException("masterKey must not be null");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion must be >= 1");
        }
        this.masterKey = masterKey;
        this.keyVersion = keyVersion;
        this.random = random;
    }

    /**
     * The Spring constructor.
     *
     * <p>Explicitly {@code @Autowired} because this class has two constructors and, without this,
     * Spring cannot tell which one it is meant to use.
     */
    @Autowired
    public PiiCipher(PiiProperties properties) {
        this(properties.resolvedKey(), properties.resolvedKeyVersion(), new SecureRandom());
    }

    /**
     * @param subject the customer's Keycloak subject, used as the derivation salt
     * @param plaintext the value, or null for an absent one
     * @return base64url payload, or null if {@code plaintext} was null
     * @throws IllegalStateException if the subject is blank, which means the record is erased
     */
    public String encrypt(String subject, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        requireSubject(subject);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] ciphertext = gcm(
                Cipher.ENCRYPT_MODE, derivedKey(subject, HKDF_INFO), iv, plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteBuffer.allocate(1 + iv.length + ciphertext.length)
                        .put((byte) keyVersion)
                        .put(iv)
                        .put(ciphertext)
                        .array());
    }

    /**
     * @return the plaintext
     * @throws IllegalStateException if the subject is blank, which means the record is erased and there
     *     is nothing left to derive a key from
     * @throws PiiDecryptionException if the payload is malformed, from another key version, or fails
     *     authentication
     */
    public String decrypt(String subject, String payload) {
        if (payload == null) {
            return null;
        }
        requireSubject(subject);

        byte[] raw = decode(payload);
        // 1 version byte + 12 nonce bytes + at least a 16-byte tag. Checked before any parsing so a
        // truncated value cannot be read as a valid one with a shifted offset.
        if (raw.length < 1 + IV_BYTES + (TAG_BITS / 8)) {
            throw new PiiDecryptionException(
                    "stored value is too short to be a valid payload: " + raw.length + " bytes");
        }
        int writtenVersion = raw[0] & 0xFF;
        if (writtenVersion != keyVersion) {
            // Named as a version problem rather than reported as tampering, because the two have
            // opposite urgency: a deploy that forgot to widen the key ring is a known, fixable
            // outage, and a caller who sees "authentication failed" will assume the database is corrupt.
            throw new PiiDecryptionException(
                    "stored value is key version " + writtenVersion + " but this service reads version " + keyVersion
                            + "; the key has been rotated and this service has not been");
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(raw, 1, iv, 0, IV_BYTES);
        byte[] ciphertext = new byte[raw.length - 1 - IV_BYTES];
        System.arraycopy(raw, 1 + IV_BYTES, ciphertext, 0, ciphertext.length);
        return new String(
                gcm(Cipher.DECRYPT_MODE, derivedKey(subject, HKDF_INFO), iv, ciphertext), StandardCharsets.UTF_8);
    }

    /**
     * A stable, non-reversible index of a normalised value, for equality lookups.
     *
     * <p>Stable because that is the entire point: a uniqueness constraint needs the same value to
     * produce the same index on every write. Keyed, so a database dump does not let an attacker
     * confirm an email address by hashing a list of candidates. It remains a low-entropy equality
     * oracle, which is why indexes are never logged, never returned, and never used for anything but
     * equality.
     *
     * @return 64 hex characters, or null for a blank value
     */
    public String blindIndex(String normalisedValue) {
        if (normalisedValue == null || normalisedValue.isBlank()) {
            return null;
        }
        return hmac(derivedKey(masterKey.getEncoded(), BLIND_INDEX_INFO), normalisedValue);
    }

    /**
     * A stable index of a subject that survives erasure.
     *
     * <p>This is the one field deliberately kept after a customer is deleted, because it is what lets
     * a repeated erasure request be answered "yes, already done" instead of "no such customer", which
     * is the difference between a customer being able to confirm a deletion and having to phone
     * support. It is derived with its own info value so it cannot be replayed into an email or phone
     * lookup, and it is not a key, so it reveals nothing that was not already the caller's own.
     *
     * @return 64 hex characters, or null
     */
    public String subjectDigest(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        return hmac(derivedKey(masterKey.getEncoded(), SUBJECT_DIGEST_INFO), subject);
    }

    /**
     * Derives a customer's key. Package-private and named for the test that pins it, not for callers.
     *
     * <p>Exists so {@code PiiCipherTest} can compare this against an independent RFC 5869
     * implementation. Deriving is the one operation where a plausible-looking change silently destroys
     * every stored value, so it is checked against a published answer rather than against itself.
     *
     * @param subject the derivation salt
     * @param info domain separation for this use of the derivation
     * @return 32 bytes
     */
    byte[] derivedKeyForTest(String subject, byte[] info) {
        return derivedKey(subject, info);
    }

    /** HKDF-SHA256, extract-then-expand, with the full counter loop rather than a single block. */
    private byte[] derivedKey(String subject, byte[] info) {
        byte[] salt = subject.getBytes(StandardCharsets.UTF_8);
        // Extract: PRK = HMAC(salt, IKM). The salt is the HMAC *key* and the master is the message;
        // transposing the two produces a different, entirely self-consistent derivation, which is
        // exactly the kind of bug that reads correctly and destroys data.
        byte[] pseudoRandomKey = hmacBytes(HMAC_ALGORITHM, salt, masterKey.getEncoded());

        // Expand: T(1) = HMAC(PRK, info || 0x01), T(n) = HMAC(PRK, T(n-1) || info || n). The loop is
        // written out rather than delegated because a 32-byte output happens to fit in one block
        // today, and a one-block version would silently truncate the day the key length changed.
        byte[] output = new byte[KEY_BYTES];
        byte[] block = new byte[0];
        int written = 0;
        for (int counter = 1; written < output.length; counter++) {
            try {
                Mac expand = mac(HMAC_ALGORITHM, pseudoRandomKey);
                expand.update(block);
                expand.update(info);
                expand.update((byte) counter);
                block = expand.doFinal();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
            }
            int take = Math.min(block.length, output.length - written);
            System.arraycopy(block, 0, output, written, take);
            written += take;
        }
        return output;
    }

    /**
     * Blind indexes and the subject digest are derived once per master key and then HMAC'd.
     *
     * <p>They are not derived per customer. A blind index has to be comparable across records to be
     * useful for a duplicate check, and these values are compared across customers by design. They are
     * not decryptable, so this is not a per-customer key in any sense that matters.
     *
     * @param salt the master key, so these are stable for the life of the installation
     * @param info the domain separation for this digest
     */
    private byte[] derivedKey(byte[] salt, byte[] info) {
        try {
            byte[] pseudoRandomKey = hmacBytes(HMAC_ALGORITHM, salt, masterKey.getEncoded());
            Mac expand = mac(HMAC_ALGORITHM, pseudoRandomKey);
            expand.update(info);
            expand.update((byte) 1);
            return expand.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (java.security.GeneralSecurityException e) {
            if (mode == Cipher.ENCRYPT_MODE) {
                throw new PiiDecryptionException("could not encrypt the value", e);
            }
            // GCM reports a failed tag check as a general failure, but the only way to get here on a
            // correctly formed payload is a tag that did not verify: the wrong derived key, a modified
            // row, or corruption. Saying "authentication" is what tells an operator which of those to
            // go looking for.
            throw new PiiDecryptionException(
                    "failed authentication: the stored value was not written with this key, or has been"
                            + " modified since",
                    e);
        }
    }

    private static String hmac(byte[] key, String message) {
        return HexFormat.of().formatHex(hmacBytes(HMAC_ALGORITHM, key, message.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmacBytes(String algorithm, byte[] key, byte[] message) {
        try {
            Mac mac = mac(algorithm, key);
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-" + algorithm.substring(4) + " is required but unavailable", e);
        }
    }

    private static Mac mac(String algorithm, byte[] key) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac;
    }

    private static byte[] decode(String payload) {
        try {
            return Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new PiiDecryptionException("stored value is not valid base64url", e);
        }
    }

    /**
     * A blank subject means the row is a tombstone.
     *
     * <p>Thrown rather than quietly encrypted under an empty key. Encrypting under a shared empty salt
     * would produce a value that is still readable, which would mean an erasure that appears to have
     * removed the data while leaving it recoverable.
     */
    private static void requireSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException(
                    "cannot read or write personal data without a subject: this record is erased and"
                            + " nothing remains to derive a key from");
        }
    }
}
