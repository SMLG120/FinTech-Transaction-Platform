package com.fintech.platform.common.identity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The caller's identity as the gateway established it, on its way to a downstream service.
 *
 * <p>Per ADR-0004 the gateway verifies the token once and passes the result on, so that nine
 * services do not each implement JWT verification. That decision moves the trust boundary rather than
 * removing it: these values are only as trustworthy as the signature over them and the network the
 * call arrived on, which is why {@link InternalIdentityCodec} signs the canonical form and why
 * mutual TLS is a hard requirement before any service is reachable from outside the cluster.
 *
 * <p>Instances are immutable and self-validating. Validation is not defensive decoration: the
 * canonical form is a delimiter-joined string that gets signed, so a value containing the delimiter
 * would let a caller shift fields across the boundary and have the result signed as if the gateway
 * had written it. Rejecting control characters closes that off at the point of construction instead
 * of leaving it to every consumer of these fields.
 *
 * @param subject the immutable, opaque identifier for the caller; the Keycloak user id
 * @param username a human-readable label for logs; never an authorisation input
 * @param roles the roles the gateway authorised, already normalised
 * @param correlationId ties this caller's request to the gateway's log lines
 * @param issuedAt when the gateway signed this identity, used to bound replay
 */
public record InternalIdentity(
        String subject, String username, List<String> roles, String correlationId, Instant issuedAt) {

    /** Roles are upper-case identifiers. Anything else came from somewhere unexpected. */
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final int MAX_FIELD_LENGTH = 512;
    private static final int MAX_ROLES = 64;

    /** The delimiter of the canonical form. Forbidden inside every field, which is what makes it unambiguous. */
    public static final char FIELD_SEPARATOR = '\n';

    public InternalIdentity {
        subject = requireSafeText(subject, "subject");
        username = requireSafeText(username, "username");
        correlationId = requireSafeText(correlationId, "correlationId");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        roles = normaliseRoles(roles);
    }

    /**
     * Rejects anything that could make the canonical form ambiguous, and silently rewriting the value
     * instead of throwing is not an option either: a username carrying a newline is a bug or an
     * attack, and both deserve to stop the request rather than be quietly normalised into something
     * that logs differently from what was authenticated.
     */
    private static String requireSafeText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_FIELD_LENGTH + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == FIELD_SEPARATOR || c == '\r' || Character.isISOControl(c)) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }

    private static List<String> normaliseRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        // Sorted and de-duplicated so the canonical form of one identity is unique. Without this,
        // two byte-identical callers could produce different signatures purely by claim ordering.
        LinkedHashSet<String> normalised = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || !ROLE.matcher(role).matches()) {
                throw new IllegalArgumentException("role is not a valid identifier: " + role);
            }
            normalised.add(role);
        }
        if (normalised.size() > MAX_ROLES) {
            throw new IllegalArgumentException("more than " + MAX_ROLES + " roles");
        }
        List<String> sorted = new ArrayList<>(normalised);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * The exact string the signature covers.
     *
     * <p>Deliberately not JSON: a canonical form that both sides construct with the same few lines of
     * code has no parser, no field ordering, no escaping rules, and no way for a future library
     * upgrade to change what gets signed underneath us. Roles are comma-joined, which is safe because
     * the role pattern excludes commas.
     */
    public String canonicalForm() {
        return String.join(
                String.valueOf(FIELD_SEPARATOR),
                subject,
                username,
                String.join(",", roles),
                correlationId,
                String.valueOf(issuedAt.getEpochSecond()));
    }
}
