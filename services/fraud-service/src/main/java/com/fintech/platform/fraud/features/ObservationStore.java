package com.fintech.platform.fraud.features;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.persistence.FraudObservationEntity;
import com.fintech.platform.fraud.persistence.FraudObservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the engine's memory of what it has seen.
 *
 * <p><b>Writes are upserts and reads are point lookups, and the order is deliberate.</b> The feature
 * collector reads first, so that a payment is scored against the state as it was <em>before</em> this
 * payment, and records afterwards. A collector that recorded first would score every payment as
 * brand-new: the device would already be in the table by the time the new-device rule asked.
 *
 * <p><b>Writes are not in the scoring transaction.</b> {@link #record} runs in its own transaction
 * ({@code REQUIRES_NEW}), because a decision and its observations have genuinely different lifetimes: the
 * decision is a fact about a payment and must commit, while an observation is a cache of the recent past
 * that can be rebuilt from the payments themselves. Rolling a decision back because an observation upsert
 * hit a constraint would be losing a fact in order to save a cache entry.
 *
 * <p>That also means an observation can be recorded for a payment whose decision rolled back, which is
 * harmless — the next payment from that device sees a sighting that no decision ever scored, and every
 * rule that reads an observation treats it as "seen before", which is true.
 */
@Component
public class ObservationStore {

    private static final Logger log = LoggerFactory.getLogger(ObservationStore.class);

    private final FraudObservationRepository observations;

    private final FraudProperties properties;

    private final Clock clock;

    public ObservationStore(FraudObservationRepository observations, FraudProperties properties, Clock clock) {
        this.observations = observations;
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether observations are being kept at all. Checked before every write, not only at startup. */
    public boolean isEnabled() {
        return properties.getObservations().isEnabled();
    }

    public Optional<FraudObservationEntity> find(
            String scope, String kind, String subjectDigest, String observationKey) {
        return observations.findByScopeAndKindAndSubjectDigestAndObservationKey(
                scope, kind, subjectDigest, observationKey);
    }

    /**
     * How many other customers have used this device.
     *
     * <p>Counted over the card-device scope, so a customer with two cards on one device counts once. The
     * answer is 0 for a device nobody has seen, which is the honest reading of "no rows" and not a
     * failure to look.
     */
    public long otherCustomersOnDevice(String deviceReference, String excludingSubjectDigest) {
        if (deviceReference == null) {
            return 0;
        }
        return observations.distinctCustomerCountForDevice(
                FraudObservationEntity.SCOPE_CARD_DEVICE, "DEVICE", deviceReference, excludingSubjectDigest);
    }

    /**
     * When this customer last switched to a different network.
     *
     * <p>Empty when the payment's network has never changed, which is the normal case. The lookup excludes
     * the current network on purpose: the customer's current network is always the most recently seen one,
     * so including it would return "now" for every payment and the rapid-change rule would fire always.
     */
    public Optional<Instant> lastNetworkChange(String ownerSubjectDigest, String currentNetworkReference) {
        if (currentNetworkReference == null) {
            return Optional.empty();
        }
        return observations
                .findOtherNetworks(
                        FraudObservationEntity.SCOPE_CUSTOMER_NETWORK,
                        "NETWORK",
                        ownerSubjectDigest,
                        currentNetworkReference,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(FraudObservationEntity::firstSeenAt);
    }

    /**
     * Records a sighting.
     *
     * <p>Separate transaction, and failures are logged rather than propagated: an observation that cannot
     * be written makes the next payment look new, which costs one extra false positive, whereas an
     * exception here would cost the decision.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String scope, String kind, String subjectDigest, String observationKey) {
        if (!isEnabled() || subjectDigest == null || observationKey == null) {
            return;
        }
        try {
            observations.recordSighting(scope, kind, subjectDigest, observationKey, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Could not record {} observation for a subject digest: {}", kind, e.toString());
        }
    }

    /**
     * Deletes rows past their retention, in bounded batches.
     *
     * <p>Bounded because an unbounded delete on a table that is being written to holds locks long enough to
     * stall the scoring path, and a retention job that stops the fraud engine is worse than one that runs
     * slowly.
     */
    @Transactional
    public int prune(String scope, int batchSize) {
        Duration retention = retentionFor(scope);
        if (retention == null) {
            return 0;
        }
        var expired = observations.findExpired(scope, clock.instant().minus(retention), PageRequest.of(0, batchSize));
        expired.forEach(observations::delete);
        return expired.size();
    }

    /** The retention for a scope, or null when that scope is not prunable. */
    private Duration retentionFor(String scope) {
        FraudProperties.Observations config = properties.getObservations();
        return switch (scope) {
            case FraudObservationEntity.SCOPE_CUSTOMER_DEVICE, FraudObservationEntity.SCOPE_CARD_DEVICE ->
                Duration.ofDays(config.getDeviceRetentionDays());
            case FraudObservationEntity.SCOPE_CUSTOMER_NETWORK -> Duration.ofDays(config.getNetworkRetentionDays());
            case FraudObservationEntity.SCOPE_CUSTOMER_MERCHANT -> Duration.ofDays(config.getMerchantRetentionDays());
            case FraudObservationEntity.SCOPE_CARD -> Duration.ofDays(config.getCardRetentionDays());
            default -> null;
        };
    }
}
