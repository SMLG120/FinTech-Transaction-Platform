package com.fintech.platform.card.tokenisation;

import com.fintech.platform.card.domain.CardToken;
import com.fintech.platform.card.domain.Pan;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Turns a card number into the token that is stored in its place, and derives the cardholder handle
 * used for ownership checks.
 *
 * <p>This is the component that makes "the platform never stores a PAN" a property of the system
 * rather than a promise in a document. A {@link Pan} is minted, passed here once, and dropped; what
 * {@code Card} holds is the {@link CardToken} this returns. There is no reverse operation and no
 * lookup that can recover a number, so a disclosure of the {@code cards} table yields tokens, which
 * are useless to an attacker without this key.
 *
 * <p><strong>Why HMAC rather than a hash.</strong> An unsalted SHA-256 of a card number is trivially
 * reversible: sixteen decimal digits is 10^16 candidates, exhaustible on a laptop, and card numbers
 * come from a far smaller effective space than that because the leading digits are a known BIN and
 * the trailing check digit is one of ten. Keying with HMAC is what removes that attack. It also gives
 * the second property tokenisation needs, determinism: the same number always yields the same token
 * within this platform, so an authorisation can be matched back to a stored card.
 *
 * <p><strong>Why the two derivations do not share a message.</strong> {@link #ownerDigest} hashes a
 * subject and {@link #tokenise} hashes a card number, both under the same key. A purpose prefix makes
 * those two value spaces disjoint, so a subject digest can never be read as a card token or vice
 * versa. The separator is a NUL byte, which is unambiguous here because the only two inputs are a
 * digit string and a Keycloak subject, and {@code InternalIdentity} rejects control characters in the
 * subject at construction, so a subject can never contain the byte that delimits the two parts.
 */
@Component
public class CardTokenizer {

    /** HMAC-SHA-256 output, and so the width of every token and digest produced here. */
    public static final int KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    /** Domain separators. Changing either is a key rotation, because it changes every derived value. */
    private static final String PURPOSE_TOKEN = "fintech.card.token.v1";

    private static final String PURPOSE_OWNER = "fintech.card.owner.v1";

    private static final byte SEPARATOR = 0x00;

    private final SecretKeySpec key;

    public CardTokenizer(CardTokenisationProperties properties) {
        this.key = new SecretKeySpec(properties.decodeKey(), ALGORITHM);
    }

    /**
     * The token that stands in for a card number everywhere the platform stores or transmits one.
     *
     * <p>The argument is deliberately typed {@link Pan} rather than {@code String} so that the only
     * way to reach this method is with a number that was minted in memory, and so that the compiler
     * prevents a future caller passing some other string.
     */
    public CardToken tokenise(Pan pan) {
        Objects.requireNonNull(pan, "pan must not be null");
        return new CardToken(hex(hmac(PURPOSE_TOKEN, pan.digits())));
    }

    /**
     * A stable, non-reversible handle for a cardholder's opaque subject id.
     *
     * <p>card-service needs to answer "is this caller the cardholder", and it cannot ask
     * customer-service on every card read without a synchronous dependency on the profile service for
     * a question it already has an answer to. It also must not keep the raw subject, because that
     * would be a second, unencrypted copy of an identity mapping whose whole design goal in
     * customer-service was to avoid exactly that. A keyed digest gives it both: the comparison is a
     * single indexed equality, and the stored value is not the identity.
     *
     * <p>The digest is scoped to card-service's own key, so it cannot be correlated with
     * customer-service's subject digest to join the two databases. Joining them is exactly what
     * ADR-0002's per-service roles exist to prevent, and deriving the same value in two services
     * would quietly defeat that without either database ever being read directly.
     */
    public String ownerDigest(String subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return hex(hmac(PURPOSE_OWNER, subject));
    }

    private byte[] hmac(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update(SEPARATOR);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // Unreachable with a fixed algorithm and a validated key. Failing loudly beats a silent
            // fallback to a weaker construction, which is the one response that would be truly bad here.
            throw new IllegalStateException("card tokenisation is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
