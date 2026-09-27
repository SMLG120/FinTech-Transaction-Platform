package com.fintech.platform.fraud.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Derives this service's own subject digest, for the staff members it records.
 *
 * <p><b>Staff only, and there is deliberately no method for a customer.</b> A customer's Keycloak subject
 * never reaches this service; it arrives in the {@code transaction.created} event already digested by
 * transaction-service and is stored as received. An earlier version of this class offered
 * {@code digestOf(String)} for owners as well, and it was never called — a customer subject is not
 * available here to call it with. It has been removed rather than left in place, because a method named
 * for owners sitting next to a per-service key reads as a boundary that is enforced here, and it is not:
 * the {@code ownerSubjectDigest} column holds transaction-service's digest and is joinable against the
 * payments database by anyone holding both. See {@link FraudDigestKey} for what that means and
 * ADR-0008 for why it is accepted.
 *
 * <p><b>HMAC, not a hash.</b> A Keycloak subject is a UUID, and a UUID space is small enough that an
 * unsalted digest of a known list of usernames is a lookup rather than a decryption. Keying it removes
 * the question, and the cost is one secret that this service must not log, must not share, and must not
 * default.
 *
 * <p><b>Purpose-prefixed</b>, as in every other service on the platform, so a subject digest here can
 * never collide with another value derived under the same key — and so that a future purpose added to
 * this service is forced to choose its own prefix rather than reusing one by accident.
 *
 * <p><b>Not used for anything that arrives on a wire.</b> Card, device and network references are digests
 * produced by transaction-service, and this service deliberately has no key that could reproduce or verify
 * them. It correlates on them; it cannot interpret them. See {@code PaymentFacts}.
 */
@Component
public class SubjectDigester {

    public static final int KEY_BYTES = FraudDigestKey.MIN_KEY_BYTES;

    private static final String ALGORITHM = "HmacSHA256";

    /** NUL, which no subject can contain: InternalIdentity rejects control characters at construction. */
    private static final byte SEPARATOR = 0;

    /**
     * Disjoint from every other purpose under this key, and under every other service's.
     *
     * <p>Named for what it covers rather than for the service, because a prefix that said
     * {@code fraud-} would invite exactly the confusion the other doc comment is about: a reader seeing
     * {@code fraud-} in a column would reasonably assume a fraud-service-only value, and the customer
     * digests in this database are not one.
     */
    private static final String PURPOSE_ANALYST = "fraud-analyst";

    private final byte[] key;

    public SubjectDigester(FraudDigestKey config) {
        Objects.requireNonNull(config, "config must not be null");
        this.key = config.decode();
    }

    /**
     * The digest of a staff member's subject, for an alert timeline and an audit event.
     *
     * <p>Purpose-prefixed separately from anything else under this key, and the reason is worth being
     * concrete: a fraud analyst who also holds a customer account would otherwise have one digest for
     * both, and an alert they claimed would look like it belonged to that customer. Nothing joins those
     * columns today, and this is the reason it never can.
     */
    public String analystDigestOf(String subject) {
        return digest(PURPOSE_ANALYST, subject);
    }

    private String digest(String purpose, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cannot digest a null or blank subject");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            // The purpose is hashed first, separated by a byte no subject can contain. Concatenating
            // strings instead would let purpose "ab" and subject "c" collide with purpose "a" and subject
            // "bc", which is the whole reason the separator is a byte that cannot appear in either.
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update(SEPARATOR);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE, so this is unreachable. Throwing rather than returning
            // a constant means a JVM that somehow lacks it fails at startup instead of storing one digest
            // for every subject on the platform.
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }
}
