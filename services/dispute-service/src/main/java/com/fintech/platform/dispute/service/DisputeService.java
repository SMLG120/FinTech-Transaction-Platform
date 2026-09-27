package com.fintech.platform.dispute.service;

import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.dispute.domain.DisputeReason;
import com.fintech.platform.dispute.domain.DisputeStatus;
import com.fintech.platform.dispute.error.DisputeErrors;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceRepository;
import com.fintech.platform.dispute.persistence.DisputeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens chargeback cases, gathers their evidence, and decides them.
 *
 * <p><b>Only the customer opens, and only their own settled payment.</b> Opening verifies the
 * payment through transaction-service under the caller's own forwarded identity: a 200 means it
 * exists, is settled, and is theirs. An agent cannot open a case on a payment they cannot see —
 * the customer opens, the agent decides — which keeps every case's ownership airtight without a
 * staff override in transaction-service's ownership rule.
 *
 * <p><b>Every state change is announced in the same transaction.</b> Open writes {@code
 * dispute-created}, resolve writes {@code dispute-status-changed}, and every action writes an
 * {@code audit-events} record for the Phase 9 trail — all through the outbox, so a case can never
 * exist without its announcement and a refund can never be decided without the event that moves
 * the money. The refund itself is executed by transaction-service consuming the resolution, not by
 * this service calling back into it: this service holds no owner digest and mints no identity, so
 * it cannot reverse a payment itself without becoming exactly the confused deputy the forwarded
 * identity is meant to prevent.
 *
 * <p><b>Saves use the managed copy.</b> The dispute id is assigned in the constructor, so {@code
 * save()} merges and returns the managed instance while the argument stays detached. The return
 * value is the row from here on — Phase 8 proved what ignoring it costs.
 */
@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputes;

    private final DisputeEvidenceRepository evidence;

    private final TransactionLookup payments;

    private final OutboxWriter outbox;

    private final Clock clock;

    private final Counter opened;

    private final Counter resolved;

    private final Counter duplicateOpens;

    public DisputeService(
            DisputeRepository disputes,
            DisputeEvidenceRepository evidence,
            TransactionLookup payments,
            OutboxWriter outbox,
            Clock clock,
            MeterRegistry meters) {
        this.disputes = disputes;
        this.evidence = evidence;
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
        this.opened = Counter.builder("dispute.cases.opened")
                .description("Disputes opened, after duplicate refusal")
                .register(meters);
        this.resolved = Counter.builder("dispute.cases.resolved")
                .description("Disputes resolved, by either outcome")
                .register(meters);
        this.duplicateOpens = Counter.builder("dispute.cases.duplicate-opens")
                .description("Open attempts refused because the payment already has an open case")
                .register(meters);
    }

    /** A dispute event payload, as transaction-service will read it. */
    public record DisputePayload(String disputeId, String transactionId, String reason, String outcome) {}

    /** An audit-events payload, as audit-service will read it. */
    public record AuditPayload(
            String action,
            String resourceId,
            String resourceType,
            String transactionId,
            String actorDigest,
            String result,
            Map<String, String> metadata) {}

    /**
     * Opens a case on the caller's own settled payment.
     *
     * @return the open case
     */
    @Transactional
    public DisputeEntity open(UUID transactionId, DisputeReason reason, String description, InternalIdentity caller) {
        TransactionLookup.SettledPayment payment = payments.requireSettledOwnedBy(transactionId, caller);
        if (disputes.findByTransactionIdAndStatus(transactionId, DisputeStatus.OPEN)
                .isPresent()) {
            duplicateOpens.increment();
            throw DisputeErrors.ALREADY_OPEN.exception("Payment " + transactionId + " already has an open dispute");
        }
        Instant now = clock.instant();
        DisputeEntity dispute;
        try {
            // The managed copy — see the class note. The argument stays detached after a merge.
            dispute = disputes.save(DisputeEntity.open(transactionId, reason, description, caller.subject(), now));
        } catch (DataIntegrityViolationException e) {
            // Lost the race with another open on the same payment: the partial unique index refused
            // the second row. The pre-check above is the fast path; this is the honest one, and both
            // answer 409 rather than 500 because a concurrent case is a conflict, not a bug.
            duplicateOpens.increment();
            throw DisputeErrors.ALREADY_OPEN.exception("Payment " + transactionId + " already has an open dispute");
        }
        outbox.record(
                "Dispute",
                dispute.getId(),
                dispute.getStatus().ordinal(),
                KafkaTopics.DISPUTE_CREATED,
                "dispute.created",
                new DisputePayload(
                        dispute.getId().toString(), payment.transactionId().toString(), reason.name(), null),
                now);
        audit(dispute, "dispute-opened", caller, Map.of("reason", reason.name()), now);
        opened.increment();
        log.info("Opened dispute {} on payment {}", dispute.getId(), transactionId);
        return dispute;
    }

    /**
     * Appends a statement to an open case file.
     *
     * @return the stored evidence
     */
    @Transactional
    public DisputeEvidenceEntity addEvidence(UUID disputeId, String body, InternalIdentity caller) {
        DisputeEntity dispute = require(disputeId);
        if (dispute.getStatus() != DisputeStatus.OPEN) {
            throw DisputeErrors.NOT_OPEN.exception("Dispute " + disputeId + " is already " + dispute.getStatus());
        }
        Instant now = clock.instant();
        DisputeEvidenceEntity stored =
                evidence.save(DisputeEvidenceEntity.submit(disputeId, caller.subject(), body, now));
        dispute.noted(now);
        audit(dispute, "dispute-evidence-added", caller, Map.of(), now);
        log.info("Evidence added to dispute {}", disputeId);
        return stored;
    }

    /**
     * Decides a case. A refund moves money downstream; a rejection ends the case with words.
     *
     * @return the resolved case
     */
    @Transactional
    public DisputeEntity resolve(UUID disputeId, boolean refund, String resolution, InternalIdentity caller) {
        DisputeEntity dispute = require(disputeId);
        if (dispute.getStatus() != DisputeStatus.OPEN) {
            throw DisputeErrors.NOT_OPEN.exception("Dispute " + disputeId + " is already " + dispute.getStatus());
        }
        Instant now = clock.instant();
        dispute.resolve(refund, caller.subject(), resolution, now);
        outbox.record(
                "Dispute",
                dispute.getId(),
                dispute.getStatus().ordinal(),
                KafkaTopics.DISPUTE_STATUS_CHANGED,
                refund ? "dispute.resolved.refunded" : "dispute.resolved.rejected",
                new DisputePayload(
                        dispute.getId().toString(),
                        dispute.getTransactionId().toString(),
                        dispute.getReason().name(),
                        refund ? "REFUNDED" : "REJECTED"),
                now);
        audit(
                dispute,
                "dispute-resolved",
                caller,
                Map.of(
                        "outcome",
                        refund ? "REFUNDED" : "REJECTED",
                        "reason",
                        dispute.getReason().name()),
                now);
        resolved.increment();
        log.info("Resolved dispute {} as {}", disputeId, refund ? "REFUNDED" : "REJECTED");
        return dispute;
    }

    /** One case file, in the order it was spoken. */
    @Transactional(readOnly = true)
    public List<DisputeEvidenceEntity> fileOf(UUID disputeId) {
        require(disputeId);
        return evidence.findByDisputeIdOrderBySubmittedAtAsc(disputeId);
    }

    private DisputeEntity require(UUID disputeId) {
        return disputes.findById(disputeId)
                .orElseThrow(() -> DisputeErrors.NOT_FOUND.exception("No dispute with id " + disputeId));
    }

    private void audit(
            DisputeEntity dispute, String action, InternalIdentity caller, Map<String, String> metadata, Instant now) {
        outbox.record(
                "Dispute",
                dispute.getId(),
                dispute.getStatus().ordinal(),
                KafkaTopics.AUDIT_EVENTS,
                action,
                new AuditPayload(
                        action,
                        dispute.getId().toString(),
                        "dispute",
                        dispute.getTransactionId().toString(),
                        AuditActorDigest.of(caller.subject()),
                        "SUCCESS",
                        metadata),
                now);
    }
}
