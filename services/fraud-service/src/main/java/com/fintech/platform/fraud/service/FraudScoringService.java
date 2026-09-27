package com.fintech.platform.fraud.service;

import com.fintech.platform.fraud.domain.PaymentFacts;
import com.fintech.platform.fraud.domain.RiskAssessment;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.features.FeatureCollector;
import com.fintech.platform.fraud.persistence.ProcessedEventRepository;
import com.fintech.platform.fraud.persistence.RiskDecisionEntity;
import com.fintech.platform.fraud.persistence.RiskDecisionRepository;
import com.fintech.platform.fraud.scoring.RiskEngine;
import com.fintech.platform.fraud.service.FraudEvents.RescoreRequestPayload;
import com.fintech.platform.fraud.service.FraudEvents.TransactionCreatedPayload;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scores a payment, and owns the transaction that makes scoring exactly-once.
 *
 * <p><b>One transaction covers the claim and the decision.</b> That is the whole reason this class
 * exists. Two orderings are wrong and they are wrong in opposite directions: mark the event processed and
 * then fail to score it loses the payment's fraud assessment permanently, because Kafka will not redeliver
 * an event whose listener returned; score it and then mark it processed double-processes every redelivery,
 * which is a duplicate score, a duplicate alert and a customer told twice. The only ordering that is safe
 * is both in one transaction, so this method is {@code @Transactional} and the work it does joins it.
 *
 * <p><b>The cost of that is one Redis round trip inside a database transaction.</b> A velocity counter is
 * a cache and would normally be updated outside the transaction, and the tension is real: holding a
 * connection for the length of a network call to another host is not free. It is worth it here because
 * the alternative loses decisions, and because the round trip is a {@code EVALSHA} to a local Redis —
 * sub-millisecond, with a timeout. If the Redis call were ever moved to another host, the right answer
 * would be to make the claim two-phase (claimed, then confirmed), not to let the transaction end early.
 *
 * <p><b>A re-score does not increment the velocity counter.</b> It reads the count and records no
 * observations. A re-score is a second look at one payment by a human or a scheduled job, and counting it
 * as another payment in the customer's minute would inflate the counter that the velocity rule is
 * thresholded on — the act of investigating a suspicious customer would make them look more suspicious,
 * and the effect would compound every time an analyst looked.
 */
@Service
public class FraudScoringService {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringService.class);

    private final ProcessedEventRepository processedEvents;

    private final RiskDecisionRepository decisions;

    private final FeatureCollector features;

    private final RiskEngine engine;

    private final DecisionService decisionService;

    private final Clock clock;

    public FraudScoringService(
            ProcessedEventRepository processedEvents,
            RiskDecisionRepository decisions,
            FeatureCollector features,
            RiskEngine engine,
            DecisionService decisionService,
            Clock clock) {
        this.processedEvents = processedEvents;
        this.decisions = decisions;
        this.features = features;
        this.engine = engine;
        this.decisionService = decisionService;
        this.clock = clock;
    }

    /**
     * Claims an event and applies it, atomically.
     *
     * @return true if this delivery was the one that did the work; false if it was a redelivery
     */
    @Transactional
    public boolean claimAndApply(UUID eventId, String topic, Runnable apply) {
        int claimed = processedEvents.claim(eventId, topic, clock.instant());
        if (claimed == 0) {
            return false;
        }
        apply.run();
        return true;
    }

    /**
     * Scores a payment described by a {@code transaction-created} event.
     *
     * <p>Called inside {@link #claimAndApply}'s transaction, which is why it is not annotated: an
     * annotation here with the default propagation would join the caller's transaction anyway, and the
     * point is that the caller has one.
     */
    public void scoreTransactionCreated(TransactionCreatedPayload payload) {
        PaymentFacts facts = payload.toFacts();
        RiskFeature feature = features.collect(facts);
        RiskAssessment assessment = engine.evaluate(feature);
        decisionService.record(assessment);
        // After the decision, and in its own transaction, so an observation that cannot be written costs a
        // cache entry rather than the decision. See ObservationStore.
        features.recordObservations(facts);
    }

    /**
     * Re-scores a payment on request.
     *
     * @throws IllegalArgumentException if the payment has no decision, which is a re-score request for
     *     something this service has never scored — a bug in whoever sent it, and worth refusing rather
     *     than creating a decision with no features
     */
    public void rescoreRequested(RescoreRequestPayload request) {
        UUID transactionId = UUID.fromString(request.transactionId());
        RiskDecisionEntity existing = decisions
                .findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "cannot re-score " + transactionId + ": no decision exists for it"));
        PaymentFacts facts = existing.toFacts();
        RiskFeature feature = features.collectWithoutRecording(facts);
        RiskAssessment assessment = engine.evaluate(feature);
        // The requester and reason are carried through rather than logged here and forgotten: they are the
        // only record of who asked for the second look, and the alert timeline is where that belongs.
        var rescoreRequest = new DecisionService.RescoreRequest(request.requestedBy(), request.reason());
        log.info(
                "Re-scoring {} on request from {}: {} -> {}",
                transactionId,
                request.requestedBy(),
                existing.decision(),
                assessment.decision());
        decisionService.rescore(assessment, rescoreRequest);
    }
}
