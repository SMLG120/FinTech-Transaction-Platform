package com.fintech.platform.fraud.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the payment itself said, as it arrived on the event.
 *
 * <p>Everything here is either an amount, a time, a name the payer supplied, or a <b>keyed digest</b>.
 * There is no card number, no card token, no device identifier and no IP address in this type, and that
 * is a property of the type rather than of the code that fills it in: the producing service hashes the
 * three identifiers before they are ever published, so the fraud engine holds values it can compare but
 * cannot reverse. A rule cannot leak a card number because there is nothing here to leak.
 *
 * <p>Every optional field is {@code null} when the payment did not supply it, and {@code null} is not
 * the same as empty. A merchant's server-to-server payment has no device and possibly no client network;
 * a payment with a device fingerprint of {@code ""} is recorded as having no device, because
 * {@code FraudContext} normalises it and a shared empty fingerprint would make every payment look like
 * it came from a device the platform had seen before.
 */
public record PaymentFacts(
        UUID transactionId,
        String ownerSubjectDigest,
        Money amount,
        String payeeName,
        String merchantReference,
        String channel,
        String cardReference,
        String deviceReference,
        String networkReference,
        Instant occurredAt) {

    public PaymentFacts {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(ownerSubjectDigest, "ownerSubjectDigest must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        payeeName = blankToNull(payeeName);
        merchantReference = blankToNull(merchantReference);
        channel = blankToNull(channel);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean hasDevice() {
        return deviceReference != null;
    }

    public boolean hasCard() {
        return cardReference != null;
    }

    public boolean hasNetwork() {
        return networkReference != null;
    }

    /**
     * The merchant identity this payment's history is keyed on.
     *
     * <p>The payee's own reference when it supplied one, otherwise its display name. The two are not
     * equivalent in trust — a reference is assigned by a merchant and cannot be chosen freely, whereas a
     * name is text anyone can type — so the fact that one of them was used travels with the observation
     * and lands in the reason line. See {@link #merchantIdentitySource()}.
     */
    public String merchantIdentity() {
        if (merchantReference != null) {
            return merchantReference;
        }
        return payeeName;
    }

    /** Whether a merchant identity was supplied at all, which is what the new-merchant rule needs. */
    public boolean hasMerchantIdentity() {
        return merchantIdentity() != null;
    }

    /** Which of the two the identity came from, recorded in the evidence so a weak source is visible. */
    public String merchantIdentitySource() {
        return merchantReference != null ? "payeeReference" : "payeeName";
    }
}
