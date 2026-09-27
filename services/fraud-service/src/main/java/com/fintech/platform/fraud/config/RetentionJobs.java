package com.fintech.platform.fraud.config;

import com.fintech.platform.fraud.features.ObservationStore;
import com.fintech.platform.fraud.persistence.FraudObservationEntity;
import com.fintech.platform.fraud.persistence.ProcessedEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduled jobs: pruning observations and forgetting processed events.
 *
 * <p><b>Both are retention, and both are bounded.</b> Each deletes at most {@code batch-size} rows per
 * pass. An unbounded delete on a table that is being written to holds locks long enough to stall the
 * scoring path, and a retention job that stops the fraud engine is strictly worse than one that runs
 * slowly. The jobs are idempotent and stateless, so a missed run costs disk and nothing else.
 *
 * <p>Scheduled rather than run once at startup, because a startup-time prune on a table that has been
 * accumulating for a month is a long transaction during a rolling deploy, which is precisely when a
 * deployment should be doing the least possible.
 *
 * <p>Gated on {@code app.background-jobs-enabled} so this can be turned off independently of the relay.
 * Both are scheduled through one {@code @EnableScheduling}, so on {@link FraudSchedulingConfiguration} for
 * why the two properties are not redundant.
 */
@Component
@ConditionalOnProperty(name = "app.background-jobs-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJobs {

    private static final Logger log = LoggerFactory.getLogger(RetentionJobs.class);

    /** Rows deleted per pass. Sized so a pass is milliseconds rather than a pause. */
    private static final int BATCH = 500;

    /** How long an event-deduplication record is kept. Comfortably longer than any redelivery window. */
    private static final int PROCESSED_EVENT_RETENTION_DAYS = 7;

    private final ObservationStore store;

    private final ProcessedEventRepository processedEvents;

    private final Clock clock;

    public RetentionJobs(ObservationStore store, ProcessedEventRepository processedEvents, Clock clock) {
        this.store = store;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /**
     * Deletes observations past their per-scope retention.
     *
     * <p>One pass per scope, because the retentions differ and a scope with a short one must not wait
     * behind a scope with a long one. The scopes are named rather than derived from a table, so adding a
     * scope is a deliberate act that includes deciding when its rows die.
     */
    @Scheduled(cron = "${app.fraud.retention-observation-cron:0 17 4 * * *}")
    @Transactional
    public void pruneObservations() {
        int total = 0;
        for (String scope : new String[] {
            FraudObservationEntity.SCOPE_CUSTOMER_NETWORK,
            FraudObservationEntity.SCOPE_CUSTOMER_DEVICE,
            FraudObservationEntity.SCOPE_CARD_DEVICE,
            FraudObservationEntity.SCOPE_CARD,
            FraudObservationEntity.SCOPE_CUSTOMER_MERCHANT
        }) {
            int deleted = store.prune(scope, BATCH);
            if (deleted > 0) {
                log.info("Pruned {} expired {} observation(s)", deleted, scope);
            }
            total += deleted;
        }
        if (total > 0) {
            log.info("Pruned {} expired fraud observation(s) in total", total);
        }
    }

    /**
     * Deletes processed-event claims older than the retention window.
     *
     * <p>Truly past any redelivery horizon, and the window is deliberately generous. A deduplication record
     * that is deleted too early turns a redelivery into a second score, so this errs towards keeping rows:
     * a week of event ids is a small table, and the cost of the alternative is a duplicate alert.
     */
    @Scheduled(cron = "${app.fraud.retention-processed-cron:0 43 4 * * *}")
    @Transactional
    public void pruneProcessedEvents() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(PROCESSED_EVENT_RETENTION_DAYS));
        int deleted = processedEvents.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} processed-event claim(s) older than {} days", deleted, PROCESSED_EVENT_RETENTION_DAYS);
        }
    }
}
