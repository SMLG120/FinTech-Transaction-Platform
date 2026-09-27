package com.fintech.platform.fraud.service;

import com.fintech.platform.fraud.persistence.OutboxEventEntity;
import com.fintech.platform.fraud.persistence.OutboxRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes this service's outbox rows to Kafka.
 *
 * <p>Same pattern, same reasoning and same deliberate asymmetry as transaction-service's relay: the row is
 * marked published <em>after</em> the send is acknowledged. The other order gives at-most-once delivery,
 * where a crash between marking and sending leaves a decision that was recorded and an event nobody was
 * ever told about. Publishing first can duplicate, and this service's consumer deduplicates on
 * {@code eventId} inside the same transaction that writes the decision, so a duplicate costs one wasted
 * insert. A missing event costs the decision itself.
 *
 * <p><b>Sends the stored payload string, not a POJO.</b> The bytes written with the decision are the bytes
 * sent. Re-serialising at publish time could produce different JSON if a schema changed in between, and
 * the event in the topic would then not be the event that was recorded.
 *
 * <p><b>No transaction around the batch</b>, for the same reason as the other relay: holding a database
 * transaction open across a network round trip per event is how a relay becomes the slowest component in
 * the platform.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;

    private final OutboxMarker marker;

    private final KafkaTemplate<String, String> kafka;

    /** Stops two passes overlapping. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Events published since start, for the health indicator. */
    private final AtomicInteger published = new AtomicInteger();

    public OutboxRelay(OutboxRepository outbox, OutboxMarker marker, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.marker = marker;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-millis:500}")
    public void relayPending() {
        if (!running.compareAndSet(false, true)) {
            // Skipped rather than queued. A queued backlog would grow behind every skipped tick, and the
            // next pass would find more rows than a batch holds and never catch up.
            return;
        }
        try {
            relayBatch();
        } catch (RuntimeException e) {
            // A scheduled method that throws stops being scheduled. Catching here leaves the rows pending
            // and lets the next tick retry, which is how a broker outage heals itself rather than
            // becoming a permanent silence.
            log.warn("fraud outbox relay pass failed; pending events will be retried", e);
        } finally {
            running.set(false);
        }
    }

    private int relayBatch() {
        List<OutboxEventEntity> pending = outbox.findPending(PageRequest.of(0, BATCH_SIZE));
        int sent = 0;
        for (OutboxEventEntity event : pending) {
            if (publish(event)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.debug("published {} fraud outbox events", sent);
        }
        return sent;
    }

    /**
     * Publishes one event, marking it on success.
     *
     * <p>Blocking on the send rather than using the async callback: the row must be marked before this
     * pass moves on, or the next pass would pick up the same row while its send is still in flight and
     * publish it twice for no reason.
     */
    private boolean publish(OutboxEventEntity event) {
        try {
            kafka.send(event.topic(), event.eventKey(), event.payload()).get();
            marker.markPublished(event.id());
            published.incrementAndGet();
            return true;
        } catch (InterruptedException e) {
            // Restore the flag: swallowing an interrupt leaves a worker thread that has been told to stop
            // working, and the next shutdown hangs waiting for it.
            Thread.currentThread().interrupt();
            marker.markFailed(event.id(), "interrupted while publishing");
            return false;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            marker.markFailed(event.id(), reason);
            log.warn(
                    "could not publish fraud outbox event {} to {}; it stays pending and will be retried",
                    event.id(),
                    event.topic());
            return false;
        }
    }

    /** How many events are still waiting. */
    public long pendingCount() {
        return marker.pendingCount();
    }

    /** How many this relay has published since start. */
    public int publishedCount() {
        return published.get();
    }
}
