package com.fintech.platform.transaction.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The facts about a payment attempt that this service does not persist but a fraud rule needs.
 *
 * <p><b>Every identifier in here is a keyed digest, and that is the point of the type.</b> A device
 * fingerprint, a source network and a card token are all personal data or card data, and the obvious
 * implementation — forward the values to the fraud engine and let it store them — writes a
 * device-to-customer mapping into a second database that nothing needs to be able to read. The digests
 * are computed here, under the key this service already holds for subject digests, and each with its own
 * purpose prefix, so the fraud engine can answer every question it has ("has this device been seen on
 * this card?", "has this network been seen for this customer?") without ever holding the value it is
 * correlating on. See {@code SubjectDigester} for the derivation and ADR-0008 for the reasoning.
 *
 * <p>It carries no IP address, no card number and no raw device identifier, and the type enforces that
 * by construction: a value that is not 64 lowercase hex characters is rejected at construction rather
 * than forwarded. That is a cheap way to make "did someone add the raw IP to this object" a compile-time
 * answer instead of a review question.
 *
 * <p>Fields are individually optional. A payment made by a merchant's server integration has no device
 * and possibly no client network, and inventing values for them would produce a risk score computed from
 * facts that were never observed. {@code null} means "not observed", and the fraud engine skips the rules
 * that depend on it and says so in the decision's facts.
 */
public record FraudContext(
        String cardReference, PaymentChannel channel, String deviceReference, String networkReference) {

    /**
     * What a keyed digest looks like: 64 lowercase hex characters, which is HMAC-SHA-256 and which is
     * also the width of every column these are stored in.
     */
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * Normalises a context supplied by a caller of this service.
     *
     * <p>Blank becomes {@code null} rather than an empty string, so a request that sends
     * {@code deviceFingerprint: ""} is recorded as "no device observed" instead of as a device whose
     * fingerprint is the empty string — which every client would then share, and every rule that looks
     * for a new device would then treat as known after the first payment.
     */
    public FraudContext {
        cardReference = digest(cardReference, "cardReference");
        channel = channel;
        deviceReference = digest(deviceReference, "deviceReference");
        networkReference = digest(networkReference, "networkReference");
    }

    /** A context with nothing observed in it, for a caller that has no additional facts. */
    public static FraudContext empty() {
        return new FraudContext(null, null, null, null);
    }

    private static String digest(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!DIGEST.matcher(trimmed).matches()) {
            // Refused rather than truncated or coerced. This object exists to make it structurally
            // impossible to put a raw device id or an IP address on a Kafka topic, and a lenient parse
            // here would let one back in through the back door.
            throw new IllegalArgumentException(field + " must be a 64-character lowercase hex digest");
        }
        return trimmed;
    }

    /** Whether anything at all was observed, so the fraud engine can tell "no context" from "empty context". */
    public boolean isEmpty() {
        return cardReference == null && channel == null && deviceReference == null && networkReference == null;
    }

    /**
     * The context as it appears in the {@code transaction.created} event.
     *
     * <p>Keys are emitted only when the value exists. An absent key and a null value are the same thing
     * to a consumer reading JSON, and omitting them keeps the event for an ordinary card-not-present
     * payment down to the fields a reader actually uses.
     */
    public Map<String, Object> toEventBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (cardReference != null) {
            body.put("cardReference", cardReference);
        }
        if (channel != null) {
            body.put("channel", channel.name());
        }
        if (deviceReference != null) {
            body.put("deviceReference", deviceReference);
        }
        if (networkReference != null) {
            body.put("networkReference", networkReference);
        }
        return body;
    }

    @Override
    public String toString() {
        // The values are already digests, so printing them leaks nothing — but there is no reason to
        // print 256 hex characters into a log line, and a stable short form is more readable.
        return "FraudContext[channel="
                + (channel == null ? "unknown" : channel.name().toLowerCase(Locale.ROOT))
                + ", device=" + (deviceReference == null ? "none" : "seen")
                + ", network=" + (networkReference == null ? "none" : "seen")
                + ", card=" + (cardReference == null ? "none" : "seen")
                + "]";
    }
}
