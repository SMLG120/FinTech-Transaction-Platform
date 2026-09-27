package com.fintech.platform.notification.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification's own configuration, under {@code app.notification}.
 *
 * <p>Nesting under {@code notification} rather than a bare {@code app.consumer-group} because the
 * {@code @KafkaListener} placeholders read {@code app.notification.consumer-group}. A listener reading
 * one name while the config declares another works only if the placeholder's default happens to
 * match, and then the setting is silently ignored.
 */
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties implements InitializingBean {

    private String consumerGroup = "notification-service";

    private final DeadLetter deadLetter = new DeadLetter();

    private final Delivery delivery = new Delivery();

    @Override
    public void afterPropertiesSet() {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalStateException("app.notification.consumer-group must be a group name");
        }
        deadLetter.validate();
        delivery.validate();
    }

    /**
     * What happens to an event this consumer cannot read.
     *
     * <p>This service's consumer is terminal: a poison {@code transaction-settled} event that is
     * dropped means a payment that moved and nobody was told, with a log line as the only evidence.
     * So the policy is the same one fraud-service and settlement-service carry — bounded retries, then
     * parked on {@code dead-letter-events} where somebody will find it — and it is validated for the
     * same reason: Spring Kafka's default on a poison record is to retry, log, commit and drop, which
     * reads as healthy while losing the message.
     */
    public static class DeadLetter {

        /** The topic unprocessable events are parked on. */
        private String topic = "dead-letter-events";

        /** Total attempts, including the first. Two is the floor: see {@link #validate()}. */
        private int maxAttempts = 3;

        private long initialIntervalMs = 1000;

        private double multiplier = 2.0;

        private long maxIntervalMs = 10_000;

        /**
         * Refuses a policy that would silently discard events.
         *
         * <p>The floor of 2 is the load-bearing part. A value of 1 parks a record without ever
         * retrying it, which turns a transient broker blip into a permanently unsent notification.
         */
        public void validate() {
            if (topic == null || topic.isBlank()) {
                throw new IllegalStateException("app.notification.dead-letter.topic must be a topic name");
            }
            if (maxAttempts < 2) {
                throw new IllegalStateException("app.notification.dead-letter.max-attempts must be at least 2, got "
                        + maxAttempts + "; a value of 1 would park a record without ever retrying it, which turns "
                        + "a transient failure into a permanently unsent notification");
            }
            if (initialIntervalMs <= 0 || multiplier < 1.0 || maxIntervalMs < initialIntervalMs) {
                throw new IllegalStateException("app.notification.dead-letter back-off is unusable: initial="
                        + initialIntervalMs + "ms multiplier=" + multiplier + " max=" + maxIntervalMs
                        + "ms; expected a positive initial interval, a multiplier of at least 1, and a max at "
                        + "least as large as the initial interval");
            }
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialIntervalMs() {
            return initialIntervalMs;
        }

        public void setInitialIntervalMs(long initialIntervalMs) {
            this.initialIntervalMs = initialIntervalMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxIntervalMs() {
            return maxIntervalMs;
        }

        public void setMaxIntervalMs(long maxIntervalMs) {
            this.maxIntervalMs = maxIntervalMs;
        }
    }

    /**
     * How a failed delivery is retried.
     *
     * <p>Separate from the dead-letter policy above, because the two retry different things. The
     * dead-letter policy retries <em>reading</em> an event; this one retries <em>sending</em> a
     * notification whose send failed. A send failure must not park the event — the event was fine,
     * the downstream channel was not — so the notification row stays {@code FAILED} with a next
     * attempt, and the scheduler picks it up.
     */
    public static class Delivery {

        /** Attempts before a notification is left FAILED for a human to retry. */
        private int maxAttempts = 5;

        private long initialBackoffMs = 30_000;

        private double backoffMultiplier = 2.0;

        private long maxBackoffMs = 600_000;

        public void validate() {
            if (maxAttempts < 1) {
                throw new IllegalStateException(
                        "app.notification.delivery.max-attempts must be at least 1, got " + maxAttempts);
            }
            if (initialBackoffMs <= 0 || backoffMultiplier < 1.0 || maxBackoffMs < initialBackoffMs) {
                throw new IllegalStateException("app.notification.delivery back-off is unusable: initial="
                        + initialBackoffMs + "ms multiplier=" + backoffMultiplier + " max=" + maxBackoffMs
                        + "ms; expected a positive initial interval, a multiplier of at least 1, and a max at "
                        + "least as large as the initial interval");
            }
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public DeadLetter getDeadLetter() {
        return deadLetter;
    }

    public Delivery getDelivery() {
        return delivery;
    }
}
