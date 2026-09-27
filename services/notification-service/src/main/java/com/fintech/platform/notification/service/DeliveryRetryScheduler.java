package com.fintech.platform.notification.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries failed deliveries whose next attempt is due.
 *
 * <p>The scheduler is the second half of the delivery design: the consumer records a FAILED
 * notification with a next attempt rather than parking the event, and this component picks those up.
 * A Kafka redelivery would re-run the whole consume path for a message that was already recorded;
 * the scheduler re-sends only the send, which is the part that failed.
 *
 * <p><b>No transaction around the batch</b>: each notification is retried in its own transaction
 * inside {@link NotificationService#retryDue}, so one poison row cannot hold the batch open.
 */
@Component
public class DeliveryRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryScheduler.class);

    private final NotificationService notifications;

    /** Stops two passes overlapping. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DeliveryRetryScheduler(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${app.notification.retry-poll-millis:30000}")
    public void retryDue() {
        if (!running.compareAndSet(false, true)) {
            // Skipped rather than queued. A queued backlog would grow behind every slow pass, and a
            // retry that runs twice at once would double-send the same message.
            return;
        }
        try {
            int sent = notifications.retryDue();
            if (sent > 0) {
                log.info("retried {} due notification deliveries", sent);
            }
        } finally {
            running.set(false);
        }
    }
}
