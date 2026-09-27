package com.fintech.platform.customer.service;

import com.fintech.platform.customer.domain.CustomerIdentity;
import com.fintech.platform.customer.kyc.KycStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A customer profile for a caller who is allowed to see it.
 *
 * <p>Exists so that nothing outside the service layer ever holds a {@code Customer} entity with
 * ciphertext in it. An entity is mutable, JPA-managed and carries keys; a response body is none of
 * those things. Every field here is already decrypted, which is the reason this type lives on the way
 * out and the entity does not: there is exactly one place where plaintext PII is assembled, and it is
 * this record's constructor.
 *
 * <p>Masking is applied here rather than in the web layer, because ownership is the thing being
 * decided and only the service knows the owning subject. Doing it in the controller would mean
 * either exposing the subject on this record or inventing a way to guess it, and both are worse than
 * the problem they solve.
 */
public record CustomerProfileView(
        UUID id,
        String fullName,
        LocalDate dateOfBirth,
        String email,
        String phone,
        CustomerIdentity.PostalAddress address,
        KycStatus kycStatus,
        Instant createdAt,
        Instant updatedAt,
        boolean erased,
        /**
         * Whether the personal fields above are masked.
         *
         * <p>Set by the service, which is the only layer that knows the owning subject. A controller
         * cannot decide this, and a static cache in a controller to remember who owned what would be
         * both a leak and a race.
         */
        boolean masked) {

    /** The shape a compliance or support caller receives, carrying the audit evidence for a decision. */
    public record KycCheckView(
            UUID id,
            KycStatus outcome,
            String providerReference,
            List<String> failureReasons,
            List<CheckResultView> checks,
            Instant submittedAt,
            Instant decidedAt) {

        /**
         * @param checkName which check ran
         * @param passed whether it passed
         * @param reason caller-safe explanation, null when it passed
         */
        public record CheckResultView(String checkName, boolean passed, String reason) {}
    }
}
