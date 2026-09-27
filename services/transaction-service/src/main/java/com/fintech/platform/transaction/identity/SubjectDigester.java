package com.fintech.platform.transaction.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Turns a Keycloak subject into the keyed digest this service stores against a payment and a ledger
 * account.
 *
 * <p><b>This service derives its own digest, under its own key, and must never be given another
 * service's.</b> That is ADR-0002's whole point. customer-service and card-service each hold a subject
 * digest of their own, and the two are computed under different keys so that joining the databases
 * cannot correlate a subject across services. Handing this class card-service's digest would defeat that
 * without either database ever being read directly, and the breach would be invisible: both columns
 * would look like plausible digests, the join would work, and the isolation that was designed would be
 * gone.
 *
 * <p>So a customer is a different digest in every service, and this one is the only digest
 * transaction-service understands. An account found by the digest from elsewhere is not found at all,
 * which fails safe.
 *
 * <p><b>HMAC, not a bare hash.</b> Subjects are opaque identifiers rather than low-entropy secrets, but
 * a Keycloak subject is a UUID and a UUID space is small enough that an unsalted hash of a known
 * username list is reversible. Keying it costs one secret and removes the question.
 *
 * <p><b>Purpose-prefixed</b> as in card-service, so a subject digest here can never collide with another
 * value this service derives under the same key.
 */
@Component
public class SubjectDigester {

    /** HMAC-SHA-256 output width, and so the width of every digest produced here. */
    public static final int KEY_BYTES = TransactionDigestKey.MIN_KEY_BYTES;

    private static final String ALGORITHM = "HmacSHA256";

    /** NUL, which no subject can contain: InternalIdentity rejects control characters at construction. */
    private static final byte SEPARATOR = 0;

    /** Disjoint from any other purpose under the same key. */
    private static final String PURPOSE_OWNER = "transaction-owner";

    /** Disjoint from any other purpose under the same key. See {@link #cardReference}. */
    private static final String PURPOSE_CARD = "fraud-card-reference";

    /** Disjoint from any other purpose under the same key. See {@link #deviceReference}. */
    private static final String PURPOSE_DEVICE = "fraud-device-reference";

    /** Disjoint from any other purpose under the same key. See {@link #networkReference}. */
    private static final String PURPOSE_NETWORK = "fraud-network-reference";

    private final byte[] key;

    public SubjectDigester(TransactionDigestKey config) {
        Objects.requireNonNull(config, "config must not be null");
        // Decoded here rather than in the properties record, matching CardTokenizer: the length and
        // base64 checks live with the other key validations and report SUBJECT_DIGEST_KEY by name, which
        // is the thing an operator has to fix. Failing on first use rather than at binding also keeps a
        // malformed key from surfacing as an opaque conversion error that mentions a number.
        byte[] supplied = config.decode();
        if (supplied.length < KEY_BYTES) {
            throw new IllegalStateException(
                    "the subject digest key must be at least " + KEY_BYTES + " bytes, got " + supplied.length);
        }
        this.key = supplied.clone();
    }

    /**
     * The digest of a subject, as stored in {@code transactions.owner_subject_digest} and
     * {@code ledger_accounts.owner_ref}.
     *
     * <p>64 lowercase hex characters, which is what the columns are sized for.
     */
    public String digestOf(String subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return HexFormat.of().formatHex(hmac(PURPOSE_OWNER, subject));
    }

    /**
     * A stable pseudonym for a card token, for the fraud engine to correlate on.
     *
     * <p><b>A digest rather than the token, even though the token is already an HMAC.</b> The token is
     * card-service's key over the card number, and it is the value that authorises a payment. Putting it
     * on a Kafka topic would put a payment credential in a log store with a seven-day retention, readable
     * by every consumer group on the platform, for no benefit: the fraud engine only ever asks "is this
     * the same card as last time", and equality of digests answers that.
     *
     * <p>Derived here rather than by the fraud engine, because a digest is only useful if both sides
     * derive it the same way and the fraud service is not given this service's key. Its own purpose
     * prefix means this value can never collide with {@link #digestOf}, so a customer digest is never
     * mistaken for a card reference.
     */
    public String cardReference(String cardToken) {
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        return HexFormat.of().formatHex(hmac(PURPOSE_CARD, cardToken));
    }

    /**
     * A stable pseudonym for a client-supplied device fingerprint.
     *
     * <p>Device fingerprints are personal data in every regulation that has an opinion about them, and
     * they are attacker-chosen: a client can send a fresh one per request. Neither fact is a reason to
     * store the value. What the fraud rules need is the ability to say "the same device has been seen
     * before" and "this device has been seen on other customers", and both are answered by comparing
     * digests.
     *
     * <p>A client that sends a different fingerprint on every request therefore defeats the
     * new-device and shared-device rules entirely. That is accepted for now and called out in ADR-0008:
     * a fingerprint is only a signal if the client keeps it stable, and the rules degrade to
     * "everything looks new" rather than to a false accusation.
     */
    public String deviceReference(String deviceFingerprint) {
        Objects.requireNonNull(deviceFingerprint, "deviceFingerprint must not be null");
        return HexFormat.of().formatHex(hmac(PURPOSE_DEVICE, deviceFingerprint));
    }

    /**
     * A stable pseudonym for the network a payment arrived from.
     *
     * <p>The input is already a masked network, not an address — see {@code SourceNetwork} — and this
     * takes it one step further so that no IP-derived value reaches the topic, the fraud database or a
     * metric label. The "impossible travel" rule compares references; the analyst sees that the network
     * changed and when, and cannot read which network it was.
     */
    public String networkReference(String sourceNetwork) {
        Objects.requireNonNull(sourceNetwork, "sourceNetwork must not be null");
        return HexFormat.of().formatHex(hmac(PURPOSE_NETWORK, sourceNetwork));
    }

    private byte[] hmac(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update(SEPARATOR);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every JVM by the specification, so this means a broken runtime.
            // Failing loudly beats degrading to a plain hash, which would look like it worked while
            // making every subject in the platform trivially reversible.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
