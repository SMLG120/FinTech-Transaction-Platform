package com.fintech.platform.fraud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskAssessment;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.persistence.FraudAlertEntity;
import com.fintech.platform.fraud.persistence.FraudAlertEventEntity;
import com.fintech.platform.fraud.persistence.FraudAlertEventRepository;
import com.fintech.platform.fraud.persistence.FraudAlertRepository;
import com.fintech.platform.fraud.persistence.RiskDecisionEntity;
import com.fintech.platform.fraud.persistence.RiskDecisionRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an assessment into rows: the decision, the alert if there is one, and the outbox event.
 *
 * <p><b>All three in one transaction.</b> A decision with no alert is a finding nobody will see, an alert
 * with no decision is a queue entry with nothing behind it, and a published event with no decision is a
 * message describing a fact that does not exist. They commit together or not at all.
 *
 * <p><b>Re-scoring updates the same row.</b> See {@code RiskDecisionEntity} for why the original score is
 * overwritten rather than kept as a second row. The alert is a separate question: a re-score that lands
 * in the same band updates the existing alert's score in place, and a re-score that crosses the alert
 * threshold raises a new one. Silently raising a second alert for a payment an analyst is already working
 * would be a way to double the queue, and silently not raising one would lose a finding that only appeared
 * on the second look.
 *
 * <p><b>An alert id is the decision's id.</b> One alert per decision, and the key is shared rather than
 * separate so that a second alert is impossible rather than merely discouraged.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final RiskDecisionRepository decisions;

    private final FraudAlertRepository alerts;

    private final FraudAlertEventRepository alertEvents;

    private final OutboxWriter outbox;

    private final FraudProperties properties;

    private final ObjectMapper json;

    private final Clock clock;

    public DecisionService(
            RiskDecisionRepository decisions,
            FraudAlertRepository alerts,
            FraudAlertEventRepository alertEvents,
            OutboxWriter outbox,
            FraudProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.decisions = decisions;
        this.alerts = alerts;
        this.alertEvents = alertEvents;
        this.outbox = outbox;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Persists a first assessment, with its alert and its announcement.
     *
     * <p><b>Insert-only, and the duplicate case is handled here rather than left to the caller.</b> The
     * {@code transaction_id} primary key makes {@code save()} on a detached entity a {@code merge}, so a
     * second call for a payment that already has a decision would silently overwrite the row: a new
     * amount, new reasons, a new score, and {@code attempt} reset to 1 by the fresh entity, with no trace
     * that an earlier assessment had existed. It would also raise a second alert and publish a second
     * {@code fraud-analysis-completed}, both of which a downstream step-down consumer would act on twice.
     *
     * <p>The {@code processed_events} claim should already have caught this — but only for a redelivery,
     * which repeats the same {@code eventId}. A producer that genuinely published {@code
     * transaction-created} twice sends two distinct event ids, the claim lets both through, and the
     * second one reaches here. A duplicate announcement is not a request to look again: {@link #rescore}
     * is what that looks like, and it is a separate topic a human or a job has to send.
     *
     * <p>Returning the stored decision rather than throwing is deliberate. Throwing would roll back the
     * {@code processed_events} claim in the same transaction, so Kafka would redeliver the duplicate
     * forever, retry to the dead-letter topic, and turn a producer's duplicate into an operations problem.
     *
     * @return the stored decision, which is the existing one if this payment was already assessed
     */
    @Transactional
    public RiskDecisionEntity record(RiskAssessment assessment) {
        var existing = decisions.findByTransactionId(assessment.transactionId());
        if (existing.isPresent()) {
            log.info(
                    "Payment {} already has a decision (attempt {}); ignoring a duplicate "
                            + "transaction-created for it. Use a fraud-analysis-requested event to re-score.",
                    assessment.transactionId(),
                    existing.get().attempt());
            return existing.get();
        }
        RiskDecisionEntity decision = RiskDecisionEntity.firstAssessment(
                assessment, reasonsJson(assessment), factsJson(assessment), clock.instant());
        decisions.save(decision);
        if (assessment.warrantsAlert()) {
            raiseAlert(assessment, decision);
        }
        publishCompletion(assessment, 1);
        log.info(
                "Scored payment {} as {} ({}) with {} reason(s)",
                assessment.transactionId(),
                assessment.decision(),
                assessment.band(),
                assessment.reasons().size());
        return decision;
    }

    /**
     * Replaces a decision with a newer assessment of the same payment.
     *
     * <p>The requester and the reason travel with the assessment, because a re-score that leaves no
     * record of who asked for it and why is the same as an override with no trail — and the alert
     * timeline is the one place that record can live. See {@link #recordRescoreOnAlert}.
     *
     * @return the updated decision
     * @throws IllegalArgumentException if the payment has no decision yet, which is a re-score request for
     *     something the engine has never seen
     */
    @Transactional
    public RiskDecisionEntity rescore(RiskAssessment assessment, RescoreRequest request) {
        RiskDecisionEntity decision = decisions
                .findByTransactionId(assessment.transactionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "payment " + assessment.transactionId() + " has no decision to re-score"));
        decision.rescore(assessment, reasonsJson(assessment), factsJson(assessment), clock.instant());
        if (assessment.warrantsAlert()) {
            raiseAlert(assessment, decision);
        }
        recordRescoreOnAlert(assessment, request);
        publishCompletion(assessment, decision.attempt());
        log.info(
                "Re-scored payment {} as {} ({})",
                assessment.transactionId(),
                assessment.decision(),
                assessment.band());
        return decision;
    }

    /**
     * Who asked for a re-score, and why.
     *
     * <p>A record with nullable fields rather than a validated one, because a re-score is not always a
     * person's decision: a scheduled job re-running a payment, or a support tool, sends the same event
     * without a subject to attribute it to. Requiring both would mean those callers invent a fake actor
     * to get through validation, and a fake actor in an audit column is worse than an honest null.
     */
    public record RescoreRequest(String requestedByDigest, String reason) {

        public static final RescoreRequest UNATTRIBUTED = new RescoreRequest(null, null);

        /** A one-line description for the timeline, falling back to something honest when unanswered. */
        public String describe() {
            if (requestedByDigest == null && (reason == null || reason.isBlank())) {
                return "re-scored automatically; no requester recorded";
            }
            String who = requestedByDigest == null || requestedByDigest.isBlank()
                    ? "an unattributed caller"
                    : "subject " + requestedByDigest;
            String why = reason == null || reason.isBlank() ? "no reason given" : reason;
            return "Re-scored at the request of " + who + ": " + why;
        }
    }

    /**
     * Appends the re-score to the alert's history.
     *
     * <p>This is what {@code RESCORED} exists for, and until this method it was an enum constant and a
     * CHECK constraint that nothing could ever produce — the timeline recorded that a payment was raised,
     * claimed, resolved or dismissed, and silently omitted the one transition an investigator most needs:
     * that the score they are looking at is not the score the engine originally produced.
     *
     * <p><b>It records, it does not resolve.</b> A re-score that comes back clean leaves the alert open,
     * because the alert is a human's queue and the person holding it may be two minutes from a conclusion.
     * A service that closed the alert on a re-score would take work away from whoever is doing it, on the
     * strength of a number produced without any of the context they have. Deciding that is a policy call
     * for the fraud team, and until it is made the alert stays open and the timeline carries the new score
     * so the analyst can close it themselves.
     *
     * <p>Attached to whichever alert the payment has. {@link #raiseAlert} returns early when one already
     * exists, so a re-score that crosses the alert threshold lands on the existing alert's timeline rather
     * than a second alert's.
     */
    private void recordRescoreOnAlert(RiskAssessment assessment, RescoreRequest request) {
        var alert = alerts.findByTransactionId(assessment.transactionId());
        if (alert.isEmpty()) {
            // A payment that scored low both times. There is no alert and none should be invented for a
            // re-score nobody is looking at.
            return;
        }
        alertEvents.save(FraudAlertEventEntity.of(
                alert.get().id(),
                FraudAlertEventEntity.AlertEventAction.RESCORED,
                request.requestedByDigest(),
                request.describe() + " Now " + assessment.decision() + " at " + assessment.scoreValue() + ".",
                clock.instant()));
    }

    /**
     * Announces the decision on {@code fraud-analysis-completed}.
     *
     * <p>Amounts cross the topic as decimal strings for the same reason they cross HTTP as strings: a
     * consumer that parses JSON numbers gets a double, and a score attached to a rounded amount is a score
     * of a payment that did not happen. The reasons go along so a downstream service can make a decision
     * about the payment without calling back for the explanation.
     */
    private void publishCompletion(RiskAssessment assessment, long attempt) {
        FraudEvents.FraudAnalysisCompleted payload = FraudEvents.FraudAnalysisCompleted.of(assessment, attempt);
        outbox.record(
                "RiskDecision",
                assessment.transactionId(),
                attempt,
                KafkaTopics.FRAUD_ANALYSIS_COMPLETED,
                "fraud-analysis-completed",
                payload,
                clock.instant());
    }

    /**
     * Raises the alert, or leaves the existing one alone if this payment already has one.
     *
     * <p>Idempotent by payment id, which is what makes a redelivered event harmless at this layer as well
     * as at the event-deduplication layer. Two independent defences against the same double-alert is
     * deliberate: the deduplication table is the mechanism and this is the invariant.
     */
    private void raiseAlert(RiskAssessment assessment, RiskDecisionEntity decision) {
        if (alerts.findByTransactionId(assessment.transactionId()).isPresent()) {
            return;
        }
        FraudAlertEntity alert = FraudAlertEntity.raisedFor(
                assessment.transactionId(), assessment, reasonsJson(assessment), clock.instant());
        alerts.save(alert);
        alertEvents.save(FraudAlertEventEntity.of(
                alert.id(),
                FraudAlertEventEntity.AlertEventAction.RAISED,
                null,
                assessment.summary(),
                clock.instant()));
    }

    /**
     * An analyst's override, and the audited trail it leaves.
     *
     * <p>Capped by configuration. An analyst who can set 100 by hand can manufacture a CRITICAL, and a
     * queue that can be pushed up by hand is a queue whose ordering means nothing; the cap is where the
     * override stops being a correction and starts being a decision, and anything above it needs someone
     * else's signature — a workflow this phase does not have, which ADR-0008 names.
     */
    @Transactional
    public RiskDecisionEntity manualAdjust(UUID transactionId, int score, String analystDigest, String reason) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("a manual score must be between 0 and 100, got " + score);
        }
        if (score > properties.getAlerts().getMaxManualScore()) {
            throw new ManualScoreAboveCapException(score, properties.getAlerts().getMaxManualScore());
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a manual adjustment needs a reason; it is the audit trail");
        }
        RiskDecisionEntity decision = decisions
                .findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("payment " + transactionId + " has no decision"));
        decision.manualAdjustment(score, RiskBand.of(score), analystDigest, reason, clock.instant());
        return decision;
    }

    /** The JSON stored in {@code reasons}, and returned verbatim by the API. */
    private String reasonsJson(RiskAssessment assessment) {
        return write(assessment.reasons());
    }

    /** The JSON stored in {@code facts}, and returned verbatim by the API. */
    private String factsJson(RiskAssessment assessment) {
        return write(assessment.facts());
    }

    /**
     * Serializes, and refuses to store a decision whose explanation cannot be written.
     *
     * <p>Throwing here rather than storing a placeholder: a decision row whose reasons column says
     * {@code "[serialization failed]"} is a row an analyst will read as a decision with no reasons, and
     * the failure that produced it is gone. Failing the transaction means the payment is retried and the
     * real problem surfaces in a log.
     */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize a risk decision document", e);
        }
    }

    // ------------------------------------------------------------------------ reads

    /** One payment's decision, or empty. */
    @Transactional(readOnly = true)
    public java.util.Optional<RiskDecisionEntity> findDecision(UUID transactionId) {
        return decisions.findByTransactionId(transactionId);
    }

    /** Decisions, filtered. Every filter is optional and an absent one filters nothing. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RiskDecisionEntity> search(
            RiskBand band,
            FraudDecision decision,
            String ownerSubjectDigest,
            String merchantReference,
            java.time.Instant from,
            java.time.Instant to,
            org.springframework.data.domain.Pageable pageable) {
        return decisions.search(band, decision, ownerSubjectDigest, merchantReference, from, to, pageable);
    }

    // ------------------------------------------------------------------------ re-score request

    /**
     * Asks for a payment to be scored again, by publishing a request.
     *
     * <p>An event rather than a direct call, because a re-score reads the observation table and re-runs
     * every rule, and the analyst who asked should not be waiting on that inside an HTTP request. The
     * request carries the analyst's digest and the reason, so the alert timeline records that a re-score
     * was asked for and by whom — an override with no trail is the thing this whole design is avoiding.
     *
     * <p>The caller must already have checked that a decision exists. A request for a payment this service
     * has never scored would otherwise be a message that provably cannot succeed, sitting in a topic
     * until it was dead-lettered.
     */
    @Transactional
    public void requestRescore(UUID transactionId, String requestedByDigest, String reason) {
        FraudEvents.RescoreRequestPayload payload = new FraudEvents.RescoreRequestPayload(
                transactionId.toString(),
                requestedByDigest,
                reason,
                clock.instant().toString());
        outbox.record(
                "RiskDecision",
                transactionId,
                0,
                KafkaTopics.FRAUD_ANALYSIS_REQUESTED,
                "fraud-analysis-requested",
                payload,
                clock.instant());
        log.info("Queued a re-score of payment {} at the request of an analyst", transactionId);
    }

    /** Thrown when an analyst's score is above the configured cap. Mapped to 422 by the error handler. */
    public static class ManualScoreAboveCapException extends RuntimeException {

        private final int requested;

        private final int cap;

        public ManualScoreAboveCapException(int requested, int cap) {
            super("a manual score of " + requested + " is above the cap of " + cap
                    + "; above the cap a decision needs a second approver, which this phase does not have");
            this.requested = requested;
            this.cap = cap;
        }

        public int requested() {
            return requested;
        }

        public int cap() {
            return cap;
        }
    }
}
