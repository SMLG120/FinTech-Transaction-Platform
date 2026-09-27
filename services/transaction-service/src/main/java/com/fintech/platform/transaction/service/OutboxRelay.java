package com.fintech.platform.transaction.service;

import com.fintech.platform.transaction.domain.OutboxEvent;
import com.fintech.platform.transaction.persistence.OutboxRepository;
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
 * Publishes outbox rows to Kafka.
 *
 * <p>The relay is the other half of the outbox pattern, and its only job is to make the "committed but
 * never published" state converge. It polls, publishes, and marks each row published.
 *
 * <p><b>At-least-once, not exactly-once, and the order of the two operations is what makes it
 * safe.</b> The row is marked published <em>after</em> the send is acknowledged, never before. The other
 * order gives at-most-once delivery: a crash between marking and sending leaves a payment that committed
 * and an event nobody was ever told about, which is the failure the outbox exists to eliminate. Publishing
 * first can duplicate an event, and a duplicate is survivable by a consumer that deduplicates on the
 * aggregate version. A missing event is not survivable at all, so the duplication is accepted on purpose.
 *
 * <p><b>Sends the stored payload string, not a POJO.</b> The bytes written to the outbox are the bytes
 * sent. Re-serialising the event at publish time could produce different JSON if a schema changed in
 * between, and the event in the topic would then not be the event that was recorded with the state change
 * that caused it.
 *
 * <p><b>No transaction around the batch.</b> The sends and the marks are independent by design; wrapping
 * them together would mean holding a database transaction open across a network round trip per event,
 * which is how a relay becomes the slowest thing in the platform.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;
    private final OutboxMarker marker;
    private final KafkaTemplate<String, String> kafka;

    /** Guards against two passes overlapping. */
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
            // Skip rather than queue. A queued backlog would grow behind every skipped tick, and the next
            // pass would have more rows than a batch holds and would never catch up.
            return;
        }
        try {
            relayBatch();
        } catch (RuntimeException e) {
            // A scheduled method that throws stops being scheduled. Catching here leaves the rows pending
            // and lets the next tick try again, which is how a transient broker outage heals itself
            // instead of becoming a permanent silence.
            log.warn("outbox relay pass failed; pending events will be retried", e);
        } finally {
            running.set(false);
        }
    }

    private int relayBatch() {
        List<OutboxEvent> pending = outbox.findPending(PageRequest.of(0, BATCH_SIZE));
        int sent = 0;
        for (OutboxEvent event : pending) {
            if (publish(event)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.debug("published {} outbox events", sent);
        }
        return sent;
    }

    /**
     * Publishes one event and marks it published on success.
     *
     * @return true if the event was published and marked
     */
    private boolean publish(OutboxEvent event) {
        try {
            // Blocking on the future rather than the async callback: the row must be marked before this
            // pass moves on, or the next pass would pick up the same row while its send is still in
            // flight and publish it twice for no reason.
            kafka.send(event.topic(), event.eventKey(), event.payload()).get();
            marker.markPublished(event.id());
            published.incrementAndGet();
            return true;
        } catch (InterruptedException e) {
            // Restore the flag. Swallowing an interrupt leaves a thread pool worker that has been told to
            // stop continuing to work, and the next shutdown hangs waiting for it.
            Thread.currentThread().interrupt();
            marker.markFailed(event.id(), "interrupted while publishing");
            return false;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            marker.markFailed(event.id(), reason);
            log.warn(
                    "could not publish outbox event {} to {}; it stays pending and will be retried",
                    event.id(),
                    event.topic());
            return false;
        }
    }

    /** How many events are still waiting. */
    public long pendingCount() {
        return marker.pendingCount();
    }

    /** How many events this relay has published since start. */
    public int publishedCount() {
        return published.get();
    }
}
