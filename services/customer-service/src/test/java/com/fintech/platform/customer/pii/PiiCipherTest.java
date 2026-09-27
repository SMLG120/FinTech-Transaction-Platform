package com.fintech.platform.customer.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The PII cipher is the only thing standing between a database dump and a list of real identities, so
 * these tests are about the properties that hold rather than the code paths that run: a wrong key
 * cannot read, a changed byte cannot survive, and a deterministic key derivation cannot drift.
 */
class PiiCipherTest {

    private static final String SUBJECT = "1f0a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";
    private static final byte[] MASTER = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static byte[] hex(String s) {
        return java.util.HexFormat.of().parseHex(s);
    }

    private static String toHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static PiiCipher cipher() {
        return new PiiCipher(new SecretKeySpec(MASTER, "AES"), 1, new SecureRandom());
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("a value survives encryption and decryption")
        void roundTrips() {
            PiiCipher cipher = cipher();

            String encrypted = cipher.encrypt(SUBJECT, "Alice Chen");

            assertThat(encrypted).isNotEqualTo("Alice Chen").doesNotContain("Alice");
            assertThat(cipher.decrypt(SUBJECT, encrypted)).isEqualTo("Alice Chen");
        }

        @Test
        @DisplayName("null passes through in both directions")
        void nullPassesThrough() {
            PiiCipher cipher = cipher();

            assertThat(cipher.encrypt(SUBJECT, null)).isNull();
            assertThat(cipher.decrypt(SUBJECT, null)).isNull();
        }

        @Test
        @DisplayName("unicode survives the round trip byte for byte")
        void roundTripsUnicode() {
            PiiCipher cipher = cipher();
            String name = "Zoë Ångström 中文 🎯";

            assertThat(cipher.decrypt(SUBJECT, cipher.encrypt(SUBJECT, name))).isEqualTo(name);
        }

        @Test
        @DisplayName("an empty string is encrypted rather than treated as absent")
        void encryptsEmptyString() {
            PiiCipher cipher = cipher();

            String encrypted = cipher.encrypt(SUBJECT, "");

            assertThat(encrypted).isNotNull().isNotEmpty();
            assertThat(cipher.decrypt(SUBJECT, encrypted)).isEmpty();
        }
    }

    @Nested
    @DisplayName("authenticated encryption")
    class Authenticated {

        @Test
        @DisplayName("a flipped ciphertext bit fails authentication")
        void detectsTamperedCiphertext() {
            PiiCipher cipher = cipher();
            byte[] raw = Base64.getUrlDecoder().decode(cipher.encrypt(SUBJECT, "Alice Chen"));
            raw[raw.length - 1] ^= 0x01;

            assertThatThrownBy(() -> cipher.decrypt(
                            SUBJECT, Base64.getUrlEncoder().withoutPadding().encodeToString(raw)))
                    .isInstanceOf(PiiDecryptionException.class)
                    .hasMessageContaining("authentication");
        }

        @Test
        @DisplayName("a flipped IV bit fails authentication")
        void detectsTamperedIv() {
            PiiCipher cipher = cipher();
            byte[] raw = Base64.getUrlDecoder().decode(cipher.encrypt(SUBJECT, "Alice Chen"));
            raw[1] ^= 0x01;

            assertThatThrownBy(() -> cipher.decrypt(
                            SUBJECT, Base64.getUrlEncoder().withoutPadding().encodeToString(raw)))
                    .isInstanceOf(PiiDecryptionException.class);
        }

        @Test
        @DisplayName("a different subject cannot read another customer's value")
        void oneCustomerCannotReadAnothers() {
            PiiCipher cipher = cipher();
            String encrypted = cipher.encrypt(SUBJECT, "Alice Chen");

            assertThatThrownBy(() -> cipher.decrypt("a-different-subject", encrypted))
                    .isInstanceOf(PiiDecryptionException.class)
                    .hasMessageContaining("authentication");
        }

        @Test
        @DisplayName("a different master key cannot read the value")
        void wrongMasterKeyCannotRead() {
            String encrypted = cipher().encrypt(SUBJECT, "Alice Chen");
            PiiCipher other = new PiiCipher(
                    new SecretKeySpec("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8), "AES"),
                    1,
                    new SecureRandom());

            assertThatThrownBy(() -> other.decrypt(SUBJECT, encrypted)).isInstanceOf(PiiDecryptionException.class);
        }

        @Test
        @DisplayName("truncated input is rejected without attempting a decryption")
        void rejectsTruncatedInput() {
            assertThatThrownBy(() -> cipher().decrypt(SUBJECT, "AAAA"))
                    .isInstanceOf(PiiDecryptionException.class)
                    .hasMessageContaining("too short");
        }

        @Test
        @DisplayName("input that is not base64 is rejected")
        void rejectsNonBase64() {
            assertThatThrownBy(() -> cipher().decrypt(SUBJECT, "not valid base64!!"))
                    .isInstanceOf(PiiDecryptionException.class)
                    .hasMessageContaining("base64url");
        }

        @Test
        @DisplayName("a value from another key version is reported as a version problem, not tampering")
        void reportsKeyVersionMismatchDistinctly() {
            PiiCipher v1 = new PiiCipher(new SecretKeySpec(MASTER, "AES"), 1, new SecureRandom());
            PiiCipher v2 = new PiiCipher(new SecretKeySpec(MASTER, "AES"), 2, new SecureRandom());
            String encrypted = v1.encrypt(SUBJECT, "Alice Chen");

            assertThatThrownBy(() -> v2.decrypt(SUBJECT, encrypted))
                    .isInstanceOf(PiiDecryptionException.class)
                    .hasMessageContaining("key version 1")
                    .hasMessageContaining("reads version 2");
        }
    }

    @Nested
    @DisplayName("nonce safety")
    class NonceSafety {

        @Test
        @DisplayName("the same value encrypted twice produces different ciphertext")
        void randomisedPerValue() {
            PiiCipher cipher = cipher();

            String first = cipher.encrypt(SUBJECT, "alice@example.com");
            String second = cipher.encrypt(SUBJECT, "alice@example.com");

            // If these matched, the same IV had been reused under the same key, and GCM leaks the XOR
            // of the two plaintexts. Randomised output is the property that makes reuse impossible.
            assertThat(first).isNotEqualTo(second);
            assertThat(cipher.decrypt(SUBJECT, first)).isEqualTo(cipher.decrypt(SUBJECT, second));
        }

        @Test
        @DisplayName("two customers with the same value do not share ciphertext")
        void randomisedAcrossCustomers() {
            PiiCipher cipher = cipher();

            assertThat(cipher.encrypt("subject-a", "Alice Chen"))
                    .isNotEqualTo(cipher.encrypt("subject-b", "Alice Chen"));
        }
    }

    @Nested
    @DisplayName("key derivation")
    class KeyDerivation {

        @Test
        @DisplayName("the same subject and master always derive the same key")
        void deterministic() {
            PiiCipher first = cipher();
            PiiCipher second = cipher();
            String value = "Alice Chen";

            // The derivation is reproducible, which is what lets a record written before a restart
            // still be readable after it. A random per-record key would need storing and would then
            // be part of the backup that erasure was supposed to make useless.
            assertThat(second.decrypt(SUBJECT, first.encrypt(SUBJECT, value))).isEqualTo(value);
        }

        @Test
        @DisplayName("key derivation matches the RFC 5869 reference vector")
        void matchesRfc5869Vector() throws Exception {
            // Pinned so a refactor of the hand-rolled HKDF cannot change every stored value in the
            // platform while leaving the test suite green. The reference is implemented here rather
            // than by calling PiiCipher's own derivation, because a test that reuses the code under
            // test proves only that the code is self-consistent.
            assertThat(toHex(hkdf(RFC_IKM, RFC_SALT, RFC_INFO, RFC_OKM.length() / 2)))
                    .isEqualTo(RFC_OKM);

            // The reference above proves the test's implementation matches the RFC. This proves the
            // production path does too, which is the part that actually protects stored PII.
            PiiCipher cipher = new PiiCipher(new SecretKeySpec(RFC_IKM, "AES"), 1, new SecureRandom());
            assertThat(toHex(cipher.derivedKeyForTest(RFC_SALT_AS_SUBJECT, RFC_INFO)))
                    .isEqualTo(RFC_OKM.substring(0, 64));
        }

        @Test
        @DisplayName("a value encrypted by an independent implementation is readable by the cipher")
        void interoperatesWithAnIndependentImplementation() {
            // The vector above pins the construction to a published answer. This pins what the
            // construction is actually for: ciphertext written by unrelated code implementing the same
            // derivation is readable here. If the derivation drifted, every record written before the
            // drift would be permanently unreadable and nothing else would fail.
            byte[] derivedKey = hkdfUnchecked(MASTER, SUBJECT.getBytes(StandardCharsets.UTF_8), PRODUCTION_INFO, 32);
            String ciphertext = referenceEncrypt(derivedKey, "Alice Chen", new byte[12]);

            PiiCipher cipher = new PiiCipher(new SecretKeySpec(MASTER, "AES"), 1, new SecureRandom());
            assertThat(cipher.decrypt(SUBJECT, ciphertext)).isEqualTo("Alice Chen");
        }

        @Test
        @DisplayName("the master key itself does not decrypt a stored value")
        void masterKeyIsNotTheEncryptionKey() {
            // A design that encrypted with the master key directly would put every customer's PII
            // behind one secret and would make per-customer erasure impossible, since erasing a
            // customer could not remove just their key.
            PiiCipher cipher = new PiiCipher(new SecretKeySpec(MASTER, "AES"), 1, new SecureRandom());
            String encryptedWithMaster = referenceEncrypt(MASTER, "Alice Chen", new byte[12]);

            assertThatThrownBy(() -> cipher.decrypt(SUBJECT, encryptedWithMaster))
                    .isInstanceOf(PiiDecryptionException.class);
        }

        @Test
        @DisplayName("a different subject derives a different key")
        void derivationIsSubjectBound() throws Exception {
            // The salt is the subject, so two customers never share a key even under one master. This
            // is what keeps a single compromised key from exposing every customer at once.
            byte[] forAlice = hkdf(MASTER, SUBJECT.getBytes(StandardCharsets.UTF_8), PRODUCTION_INFO, 32);
            byte[] forBob = hkdf(MASTER, "bob-subject".getBytes(StandardCharsets.UTF_8), PRODUCTION_INFO, 32);

            assertThat(toHex(forAlice)).isNotEqualTo(toHex(forBob));
        }

        private static final byte[] RFC_IKM = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        private static final byte[] RFC_SALT = hex("000102030405060708090a0b0c");
        private static final byte[] RFC_INFO = hex("f0f1f2f3f4f5f6f7f8f9");
        private static final String RFC_OKM =
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
        private static final String RFC_SALT_AS_SUBJECT = new String(RFC_SALT, StandardCharsets.UTF_8);
        private static final byte[] PRODUCTION_INFO = "fintech.customer.pii.v1".getBytes(StandardCharsets.UTF_8);

        /** RFC 5869 extract-then-expand with the counter loop, written independently of the cipher. */
        private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
            javax.crypto.Mac extract = javax.crypto.Mac.getInstance("HmacSHA256");
            extract.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = extract.doFinal(ikm);

            byte[] output = new byte[length];
            byte[] block = new byte[0];
            int written = 0;
            for (int counter = 1; written < length; counter++) {
                javax.crypto.Mac expand = javax.crypto.Mac.getInstance("HmacSHA256");
                expand.init(new SecretKeySpec(prk, "HmacSHA256"));
                expand.update(block);
                expand.update(info);
                expand.update((byte) counter);
                block = expand.doFinal();
                int take = Math.min(block.length, length - written);
                System.arraycopy(block, 0, output, written, take);
                written += take;
            }
            return output;
        }

        private static byte[] hkdfUnchecked(byte[] ikm, byte[] salt, byte[] info, int length) {
            try {
                return hkdf(ikm, salt, info, length);
            } catch (Exception e) {
                throw new AssertionError("HKDF failed", e);
            }
        }

        private static String referenceEncrypt(byte[] key, String plaintext, byte[] iv) {
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        javax.crypto.Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1 + iv.length + ciphertext.length);
                buffer.put((byte) 1).put(iv).put(ciphertext);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
            } catch (Exception e) {
                throw new AssertionError("reference encryption failed", e);
            }
        }
    }

    @Nested
    @DisplayName("digests")
    class Digests {

        @Test
        @DisplayName("a blind index is stable, so it can back a uniqueness constraint")
        void blindIndexIsStable() {
            PiiCipher cipher = cipher();

            assertThat(cipher.blindIndex("alice@example.com"))
                    .isEqualTo(cipher.blindIndex("alice@example.com"))
                    .hasSize(64);
        }

        @Test
        @DisplayName("a blind index does not reveal the value it indexes")
        void blindIndexHidesTheValue() {
            assertThat(cipher().blindIndex("alice@example.com")).doesNotContain("alice");
        }

        @Test
        @DisplayName("different values produce different indexes")
        void blindIndexSeparates() {
            PiiCipher cipher = cipher();

            assertThat(cipher.blindIndex("alice@example.com")).isNotEqualTo(cipher.blindIndex("bob@example.com"));
        }

        @Test
        @DisplayName("a blank value has no index rather than a shared one")
        void blankHasNoIndex() {
            PiiCipher cipher = cipher();

            assertThat(cipher.blindIndex(null)).isNull();
            assertThat(cipher.blindIndex("   ")).isNull();
            assertThat(cipher.subjectDigest(null)).isNull();
        }

        @Test
        @DisplayName("the subject digest is domain-separated from the blind index")
        void subjectDigestIsSeparated() {
            PiiCipher cipher = cipher();

            // Without separation, a subject digest could be replayed into an email lookup or the other
            // way round, so an erased identity would become a searchable PII value.
            assertThat(cipher.subjectDigest("alice@example.com")).isNotEqualTo(cipher.blindIndex("alice@example.com"));
        }
    }

    @Nested
    @DisplayName("erasure")
    class Erasure {

        @Test
        @DisplayName("a value cannot be read once the subject is gone")
        void shreddedValueIsUnreadable() {
            // The property the whole erasure design rests on: nulling the subject column removes the
            // only input to key derivation, so retained ciphertext is unreadable even by the operator
            // holding the master key.
            PiiCipher cipher = cipher();
            String encrypted = cipher.encrypt(SUBJECT, "Alice Chen");

            assertThatThrownBy(() -> cipher.decrypt(null, encrypted))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("erased");
        }

        @Test
        @DisplayName("an erased record cannot be encrypted again under a shared empty key")
        void refusesToEncryptWithoutSubject() {
            assertThatThrownBy(() -> cipher().encrypt("  ", "Alice Chen"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("erased");
        }
    }

    @Nested
    @DisplayName("key configuration")
    class KeyConfiguration {

        @Test
        @DisplayName("a key that is not 32 bytes is rejected at startup")
        void rejectsShortKey() {
            assertThatThrownBy(() -> new PiiProperties(Base64.getEncoder().encodeToString(new byte[16]), 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("a key that is not base64 is rejected at startup")
        void rejectsNonBase64Key() {
            assertThatThrownBy(() -> new PiiProperties("not base64!!", 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64");
        }

        @Test
        @DisplayName("a missing key is rejected, so the service cannot start and store PII in the clear")
        void rejectsMissingKey() {
            assertThatThrownBy(() -> new PiiProperties(null, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("required");
            assertThatThrownBy(() -> new PiiProperties("  ", 1)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a key version below one is rejected")
        void rejectsZeroKeyVersion() {
            assertThatThrownBy(() -> new PiiProperties(Base64.getEncoder().encodeToString(MASTER), 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("key-version");
        }
    }
}
