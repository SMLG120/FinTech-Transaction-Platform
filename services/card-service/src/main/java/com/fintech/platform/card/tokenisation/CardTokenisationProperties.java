package com.fintech.platform.card.tokenisation;

import java.util.Base64;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key card-service tokenises card numbers with.
 *
 * <p>{@code platform.card.tokenisation.key}, supplied as {@code CARD_TOKENISATION_KEY} in base64.
 *
 * <p>Under {@code platform.card} rather than a {@code fintech.card} prefix so it sits beside the
 * identity-signing settings that {@code platform-common-web} already owns, and so a reader looking for
 * the platform's security configuration finds all of it in one branch of {@code application.yml}. A
 * prefix that disagrees with the file it binds from fails silently — the bean is created, the field
 * stays null, and the first sign of trouble is an NPE from inside a constructor rather than a
 * diagnostic naming a property.
 *
 * <p>Separate from customer-service's PII master key, and that separation is the point rather than
 * tidiness. The two keys protect different things for different reasons: one guards personal data the
 * platform must be able to <em>read back</em> to run identity checks, the other derives a value the
 * platform must never be able to <em>invert</em>. A single key serving both would mean a compromise of
 * the PII vault also compromised every card, and would make the two rotation cadences — which are
 * genuinely different — into one decision.
 *
 * <p>There is deliberately no default value. A tokenisation key with a fallback would make every
 * token in the database reproducible by anyone who has read the source, which turns "a database
 * disclosure does not become a card breach" into "a database disclosure plus this repository becomes
 * a card breach". Failing at startup is the correct outcome for a missing key, so the property is
 * {@code null}-rejecting rather than defaulting.
 */
@ConfigurationProperties(prefix = "platform.card.tokenisation")
public class CardTokenisationProperties {

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    /**
     * The decoded key bytes.
     *
     * <p>Validated in an accessor rather than in the constructor so the check runs when the value is
     * first needed, which keeps a malformed key from being reported as an opaque binding failure. The
     * message names the environment variable because that is what an operator has to fix, and it names
     * neither the value nor its length beyond the requirement.
     */
    public byte[] decodeKey() {
        Objects.requireNonNull(key, "CARD_TOKENISATION_KEY is not set; card-service will not start without it");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CARD_TOKENISATION_KEY is not valid base64", e);
        }
        if (decoded.length != CardTokenizer.KEY_BYTES) {
            throw new IllegalStateException("CARD_TOKENISATION_KEY must decode to " + CardTokenizer.KEY_BYTES
                    + " bytes, got " + decoded.length);
        }
        return decoded;
    }
}
