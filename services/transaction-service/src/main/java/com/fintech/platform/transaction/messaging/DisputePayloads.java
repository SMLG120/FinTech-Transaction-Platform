package com.fintech.platform.transaction.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The dispute event bodies this service reacts to.
 *
 * <p>Records with every field defaulted, for the same rolling-deploy reason as the other services'
 * payloads: a dispute-service that adds a field must not break a deployed transaction-service, and
 * a refund that stops because of an unknown field is money kept for no reason.
 */
public final class DisputePayloads {

    private DisputePayloads() {}

    /**
     * A {@code dispute-status-changed} payload, as published by dispute-service.
     *
     * <p>Only the fields a refund needs: which case, which payment, and what was decided. The reason
     * and the case file stay in dispute-service; the ledger needs the decision, not the argument.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusChangedPayload(String disputeId, String transactionId, String reason, String outcome) {

        /** True only for the outcome that moves money. A rejection is read and ignored. */
        public boolean isRefund() {
            return "REFUNDED".equals(outcome);
        }

        public UUID disputeUuid() {
            if (disputeId == null || disputeId.isBlank()) {
                throw new IllegalArgumentException("dispute event carries no disputeId");
            }
            try {
                return UUID.fromString(disputeId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("disputeId is not a UUID: " + disputeId, e);
            }
        }

        public UUID transactionUuid() {
            if (transactionId == null || transactionId.isBlank()) {
                throw new IllegalArgumentException("dispute event carries no transactionId");
            }
            try {
                return UUID.fromString(transactionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("transactionId is not a UUID: " + transactionId, e);
            }
        }

        public void requireDecided() {
            if (outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("dispute event carries no outcome");
            }
            if (!"REFUNDED".equals(outcome) && !"REJECTED".equals(outcome)) {
                throw new IllegalArgumentException("dispute outcome '" + outcome + "' is not one this service acts on");
            }
        }
    }
}
