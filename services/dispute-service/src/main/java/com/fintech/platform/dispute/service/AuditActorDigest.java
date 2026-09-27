package com.fintech.platform.dispute.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The actor this service names in audit events.
 *
 * <p>A bare SHA-256 of the verified subject, hex-encoded. Stable per subject, opaque, and —
 * deliberately — a different function from the HMAC purpose digests transaction-service and
 * fraud-service share. The trail must name who acted without holding a join key into their stores:
 * a shared key would make every audit row correlatable with the fraud database, and holding that
 * key would put this service in the blast radius ADR-0008 confines to two services. A keyless hash
 * cannot be joined with anything, which is exactly the property the trail wants.
 *
 * <p>Staff and customers are digested alike. The settlement precedent of storing a raw staff subject
 * is not followed here, because these digests travel to another service's database rather than
 * staying in a staff-facing view — and a raw subject in the audit trail is a customer list the
 * first time a customer-initiated action is recorded.
 */
public final class AuditActorDigest {

    private AuditActorDigest() {}

    /**
     * Digests a verified subject for audit travel.
     *
     * @param subject the gateway-verified subject, never blank
     * @return the lowercase hex SHA-256 of the subject
     */
    public static String of(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is absent the platform has worse problems than
            // an undigestable actor, and failing loudly is the only honest response.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
