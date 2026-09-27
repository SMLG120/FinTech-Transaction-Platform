package com.fintech.platform.common.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies the internal identity headers described in ADR-0004.
 *
 * <p>The gateway strips any inbound copy of these headers, then writes its own signed set. A service
 * trusts the set only if the signature verifies. That is the whole point of the arrangement, and it
 * only holds if both halves are implemented: a service that trusts the headers without verifying
 * them is trusting its own network, and a gateway that forwards unstripped headers lets a client
 * choose its own identity no matter how carefully the signature is checked.
 *
 * <p>A shared symmetric key is the interim measure. It means the gateway and every service hold the
 * same secret, so any one of them can mint an identity for any caller, and it is the reason ADR-0004
 * records mTLS as a Phase 15 requirement rather than an optional improvement. It is appropriate for
 * a single-cluster development platform and is not appropriate for a multi-tenant deployment.
 */
public final class InternalIdentityCodec {

    public static final String HEADER_SUBJECT = "X-Internal-Identity-Subject";
    public static final String HEADER_USERNAME = "X-Internal-Identity-Username";
    public static final String HEADER_ROLES = "X-Internal-Identity-Roles";
    public static final String HEADER_CORRELATION_ID = "X-Internal-Identity-Correlation-Id";
    public static final String HEADER_ISSUED_AT = "X-Internal-Identity-Issued-At";
    public static final String HEADER_SIGNATURE = "X-Internal-Identity-Signature";

    /** HMAC-SHA256 with a key shorter than its own digest is security theatre. */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * How long a signed identity stays acceptable. A service that accepted an arbitrarily old header
     * would honour a caller whose access was revoked ten minutes ago; bounding the age narrows that
     * window without pretending to eliminate it. Full replay protection needs a shared seen-set and
     * is not attempted here.
     */
    public static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(60);

    /** Tolerance for a gateway whose clock runs slightly ahead of the service's. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(10);

    private final byte[] key;
    private final Duration maxAge;
    private final Clock clock;

    public InternalIdentityCodec(byte[] key, Duration maxAge, Clock clock) {
        this.key = Objects.requireNonNull(key, "key must not be null").clone();
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "internal identity signing key must be at least " + MIN_KEY_BYTES + " bytes, was " + key.length);
        }
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge must not be null");
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Builds a codec from the hex-encoded key that {@code INTERNAL_IDENTITY_SIGNING_KEY} carries.
     * Hex rather than base64 because it survives being pasted through shells, files and YAML without
     * being silently mangled.
     */
    public static InternalIdentityCodec fromHexKey(String hexKey, Duration maxAge, Clock clock) {
        Objects.requireNonNull(hexKey, "hexKey must not be null");
        String trimmed = hexKey.trim();
        if (trimmed.length() % 2 != 0) {
            throw new IllegalArgumentException("signing key hex must have an even length");
        }
        byte[] decoded = new byte[trimmed.length() / 2];
        for (int i = 0; i < decoded.length; i++) {
            int high = Character.digit(trimmed.charAt(i * 2), 16);
            int low = Character.digit(trimmed.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("signing key hex contains a non-hex character");
            }
            decoded[i] = (byte) ((high << 4) + low);
        }
        return new InternalIdentityCodec(decoded, maxAge, clock);
    }

    /** Renders the headers a gateway sends downstream, signature included. */
    public Map<String, String> headersFor(InternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_SUBJECT, identity.subject());
        headers.put(HEADER_USERNAME, identity.username());
        headers.put(HEADER_ROLES, String.join(",", identity.roles()));
        headers.put(HEADER_CORRELATION_ID, identity.correlationId());
        headers.put(HEADER_ISSUED_AT, String.valueOf(identity.issuedAt().getEpochSecond()));
        headers.put(HEADER_SIGNATURE, sign(identity.canonicalForm()));
        return headers;
    }

    /**
     * Verifies a header set and returns the identity it carries.
     *
     * <p>Every failure is the same failure from the caller's point of view — a bad header set is a
     * bad header set — and the specific reason goes to the log rather than the response. Distinguishing
     * "bad signature" from "expired" in the response body would tell an attacker which part of the
     * forgery to fix.
     */
    public InternalIdentity verify(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers must not be null");
        try {
            String signature = required(headers, HEADER_SIGNATURE);
            String issuedAtValue = required(headers, HEADER_ISSUED_AT);

            long issuedAtSeconds = Long.parseLong(issuedAtValue.trim());
            InternalIdentity candidate = new InternalIdentity(
                    required(headers, HEADER_SUBJECT),
                    required(headers, HEADER_USERNAME),
                    splitRoles(optional(headers, HEADER_ROLES)),
                    required(headers, HEADER_CORRELATION_ID),
                    Instant.ofEpochSecond(issuedAtSeconds));

            if (!constantTimeEquals(sign(candidate.canonicalForm()), signature.trim())) {
                throw new InternalIdentityVerificationException("signature mismatch");
            }

            verifyFreshness(candidate.issuedAt());
            return candidate;
        } catch (InternalIdentityVerificationException e) {
            throw e;
        } catch (RuntimeException e) {
            // NumberFormatException, NullPointerException, IllegalArgumentException from validation.
            throw new InternalIdentityVerificationException("malformed internal identity headers", e);
        }
    }

    private void verifyFreshness(Instant issuedAt) {
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new InternalIdentityVerificationException("issued-at is in the future");
        }
        if (issuedAt.isBefore(now.minus(maxAge))) {
            throw new InternalIdentityVerificationException("internal identity is older than " + maxAge);
        }
    }

    private static String required(Map<String, String> headers, String name) {
        String value = headers.get(name);
        if (value == null || value.isBlank()) {
            throw new InternalIdentityVerificationException("missing header: " + name);
        }
        return value;
    }

    /**
     * The roles header is the one field allowed to be empty, because a caller who has authenticated but
     * holds no role is a normal state rather than a broken one — a new signup, a role revoked to zero.
     * Every other header is mandatory. The signature still covers the empty role list, so an empty
     * header cannot be used to erase roles from somebody else's identity and re-sign it.
     */
    private static String optional(Map<String, String> headers, String name) {
        String value = headers.get(name);
        return value == null ? "" : value;
    }

    private static List<String> splitRoles(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",", -1));
    }

    private String sign(String canonicalForm) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(canonicalForm.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE, so reaching this means a broken runtime.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    /**
     * Compares in time independent of where the first difference falls. {@code String.equals} on a
     * signature lets an attacker recover the correct prefix one byte at a time, and a header set is
     * attacker-controlled input long before anyone proves otherwise.
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
