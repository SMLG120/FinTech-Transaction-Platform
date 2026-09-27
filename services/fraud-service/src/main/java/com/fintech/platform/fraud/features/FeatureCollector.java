package com.fintech.platform.fraud.features;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.FeatureSnapshot;
import com.fintech.platform.fraud.domain.PaymentFacts;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.VelocitySample;
import com.fintech.platform.fraud.persistence.FraudObservationEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Assembles the feature a payment is scored against.
 *
 * <p>Reads first, scores, records afterwards. That order is the whole design, and getting it wrong has a
 * specific failure: a collector that records before scoring makes every payment look like a first
 * sighting, so the new-device, new-merchant and recent-activation rules fire on every payment on the
 * platform and an engine that alerts on everything alerts on nothing. The three phases are separate methods
 * for the same reason — they are separate transactions, and the boundary has to be visible.
 *
 * <p><b>Every lookup is independent and every failure is contained.</b> A payment is scored with whatever
 * could be established, and the snapshot records the rest as {@code null} — which the rules read as
 * "unknown" and decline to fire on. The alternative is that a slow query on the observations table stops
 * the platform's fraud scoring entirely, and stops it silently, because a consumer that throws looks
 * exactly like a consumer that is not running.
 */
@Component
public class FeatureCollector {

    private static final Logger log = LoggerFactory.getLogger(FeatureCollector.class);

    private final ObservationStore store;

    private final VelocityCounter velocity;

    private final FraudProperties properties;

    private final Clock clock;

    public FeatureCollector(ObservationStore store, VelocityCounter velocity, FraudProperties properties, Clock clock) {
        this.store = store;
        this.velocity = velocity;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Phase one: what the engine can establish, and the velocity counter is incremented.
     *
     * <p>Velocity is the one counter that is written here rather than in phase three, and it has to be:
     * the count has to include the payment being scored, and the score is computed between the two phases.
     */
    public RiskFeature collect(PaymentFacts facts) {
        Instant now = clock.instant();
        VelocitySample sample = velocity.recordAndCount(facts.ownerSubjectDigest(), facts.transactionId());
        return RiskFeature.of(facts, snapshot(facts, sample, now));
    }

    /**
     * Re-collects a payment's features without changing any counter.
     *
     * <p>For a re-score. Two things must not happen when a human looks at a suspicious payment a second
     * time: the customer's velocity count must not go up, and no observation may be recorded. The first
     * would mean investigating a customer makes them look more suspicious, compounding every time an
     * analyst looks; the second would write the "first sighting" facts a re-score is being asked to
     * re-examine, so the second look would always agree with the first.
     *
     * <p><b>What a re-score can and cannot re-evaluate, stated plainly.</b> It re-evaluates the rules
     * that depend on elapsed time and on other customers: velocity, shared device, recent card
     * activation, rapid network change. It cannot newly raise the newness rules — R003 and R007 — because
     * the observation that made them fire was written by the first scoring, and re-running the engine now
     * finds the device and the merchant already there. That is why the original reasons stay on the
     * decision: the first assessment's explanation is part of the record, and a re-score that only reads
     * the current state would erase how the payment first looked. Making newness re-evaluable needs
     * observation history rather than a current-state table, which ADR-0008 lists as future work.
     */
    public RiskFeature collectWithoutRecording(PaymentFacts facts) {
        Instant now = clock.instant();
        VelocitySample sample = velocity.peek(facts.ownerSubjectDigest());
        return RiskFeature.of(facts, snapshot(facts, sample, now));
    }

    /**
     * Phase three: remember what this payment showed us.
     *
     * <p>After the decision, always, and never conditional on its outcome. An observation is a fact about
     * the payment having happened, and a declined payment happened just as much as an approved one —
     * excluding declines would make a fraudster's own retries look like a first-time customer.
     */
    public void recordObservations(PaymentFacts facts) {
        if (!store.isEnabled()) {
            return;
        }
        if (facts.hasCard()) {
            store.record(FraudObservationEntity.SCOPE_CARD, "CARD", facts.cardReference(), facts.cardReference());
        }
        if (facts.hasDevice()) {
            store.record(
                    FraudObservationEntity.SCOPE_CUSTOMER_DEVICE,
                    "DEVICE",
                    facts.ownerSubjectDigest(),
                    facts.deviceReference());
            if (facts.hasCard()) {
                store.record(
                        FraudObservationEntity.SCOPE_CARD_DEVICE,
                        "DEVICE",
                        facts.cardReference(),
                        facts.deviceReference());
            }
        }
        if (facts.hasMerchantIdentity()) {
            store.record(
                    FraudObservationEntity.SCOPE_CUSTOMER_MERCHANT,
                    "MERCHANT",
                    facts.ownerSubjectDigest(),
                    facts.merchantIdentity());
        }
        if (facts.hasNetwork()) {
            store.record(
                    FraudObservationEntity.SCOPE_CUSTOMER_NETWORK,
                    "NETWORK",
                    facts.ownerSubjectDigest(),
                    facts.networkReference());
        }
    }

    private FeatureSnapshot snapshot(PaymentFacts facts, VelocitySample sample, Instant now) {
        Boolean deviceSeenOnCard = facts.hasDevice() && facts.hasCard()
                ? isSeen(
                        FraudObservationEntity.SCOPE_CARD_DEVICE,
                        "DEVICE",
                        facts.cardReference(),
                        facts.deviceReference())
                : null;
        Boolean deviceSeenByCustomer = facts.hasDevice()
                ? isSeen(
                        FraudObservationEntity.SCOPE_CUSTOMER_DEVICE,
                        "DEVICE",
                        facts.ownerSubjectDigest(),
                        facts.deviceReference())
                : null;
        Boolean merchantSeen = facts.hasMerchantIdentity()
                ? isSeen(
                        FraudObservationEntity.SCOPE_CUSTOMER_MERCHANT,
                        "MERCHANT",
                        facts.ownerSubjectDigest(),
                        facts.merchantIdentity())
                : null;
        long otherCustomers = facts.hasDevice()
                ? store.otherCustomersOnDevice(facts.deviceReference(), facts.ownerSubjectDigest())
                : 0;
        Instant cardFirstSeen = facts.hasCard()
                ? store.find(FraudObservationEntity.SCOPE_CARD, "CARD", facts.cardReference(), facts.cardReference())
                        .map(FraudObservationEntity::firstSeenAt)
                        .orElse(null)
                : null;
        Optional<Instant> networkChange = facts.hasNetwork()
                ? store.lastNetworkChange(facts.ownerSubjectDigest(), facts.networkReference())
                : Optional.empty();

        return new FeatureSnapshot(
                sample,
                deviceSeenOnCard,
                deviceSeenByCustomer,
                merchantSeen,
                Math.toIntExact(Math.min(otherCustomers, Integer.MAX_VALUE)),
                cardFirstSeen,
                null,
                networkChange.orElse(null),
                now);
    }

    /**
     * Whether an observation exists, or {@code null} if it could not be read.
     *
     * <p>Three states, and the third is the point. {@code true} and {@code false} are answers; {@code null}
     * is the absence of one, and a rule that treats it as {@code false} turns a database hiccup into
     * "this device is new", which is the exact signal the new-device rule exists to raise.
     */
    private Boolean isSeen(String scope, String kind, String subjectDigest, String observationKey) {
        try {
            return store.find(scope, kind, subjectDigest, observationKey).isPresent();
        } catch (RuntimeException e) {
            log.warn("Could not read {} observation; treating as unknown: {}", kind, e.toString());
            return null;
        }
    }

    /** The window the velocity counter used, for a decision's facts when the sample is degraded. */
    public int velocityWindowSeconds() {
        return properties.getVelocity().getWindowSeconds();
    }
}
