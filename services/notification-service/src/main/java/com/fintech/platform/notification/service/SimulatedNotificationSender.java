package com.fintech.platform.notification.service;

import com.fintech.platform.notification.persistence.NotificationEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only sender this phase ships: it logs the message and records the delivery.
 *
 * <p>No real provider is ever called — no email relay, no SMS gateway, no push service. That is not a
 * stub waiting for credentials; it is the design for this phase. What this phase has to prove is that
 * the platform turns facts into messages exactly once, retries a failed send without resending a
 * success, and keeps a delivery log a support agent can read. Whether bytes reach a phone is a
 * provider integration, and a provider integration tested against a fake would prove nothing about the
 * provider either.
 */
@Component
public class SimulatedNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedNotificationSender.class);

    private final Counter sent;

    public SimulatedNotificationSender(MeterRegistry meters) {
        this.sent = Counter.builder("notification.deliveries.sent")
                .description("Notifications the simulated sender delivered, by channel and kind")
                .register(meters);
    }

    @Override
    public void send(NotificationEntity notification) {
        // The log line IS the delivery in this phase, which is why it carries the rendered subject
        // rather than a pointer to it. An operator tailing the log sees what the customer would see.
        log.info(
                "Sending {} {} to digest {}: {}",
                notification.getChannel(),
                notification.getKind(),
                notification.getRecipientDigest() == null ? "n/a" : "held",
                notification.getSubject());
        sent.increment();
    }
}
