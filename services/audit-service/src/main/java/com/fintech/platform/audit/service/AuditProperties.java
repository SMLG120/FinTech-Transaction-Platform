package com.fintech.platform.audit.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit's own configuration, under {@code app.audit}.
 *
 * <p>Nesting under {@code audit} rather than a bare {@code app.consumer-group} because the {@code
 * @KafkaListener} placeholders read {@code app.audit.consumer-group}. A listener reading one name
 * while the config declares another works only if the placeholder's default happens to match, and
 * then the setting is silently ignored.
 */
@ConfigurationProperties(prefix = "app.audit")
public class AuditProperties implements InitializingBean {

    private String consumerGroup = "audit-service";

    private final DeadLetter deadLetter = new DeadLetter();

    @Override
    public void afterPropertiesSet() {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalStateException("app.audit.consumer-group must be a group name");
        }
        deadLetter.validate();
    }

    /**
     * What happens to an event this recorder cannot read.
     *
     * <p>This service's consumer is terminal in the strongest sense: a dropped {@code audit-events}
     * record is a staff action the trail has a hole for, with a log line as the only evidence. So
     * the policy is the same one every other consuming service carries — bounded retries, then
     * parked on {@code dead-letter-events} where somebody will find it — and it is validated for
     * the same reason: Spring Kafka's default on a poison record is to retry, log, commit and drop,
     * which reads as healthy while losing the record.
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
         * retrying it, which turns a transient broker blip into a permanent hole in the trail.
         */
        public void validate() {
            if (topic == null || topic.isBlank()) {
                throw new IllegalStateException("app.audit.dead-letter.topic must be a topic name");
            }
            if (maxAttempts < 2) {
                throw new IllegalStateException("app.audit.dead-letter.max-attempts must be at least 2, got "
                        + maxAttempts + "; a value of 1 would park a record without ever retrying it, which turns "
                        + "a transient failure into a permanently unrecorded action");
            }
            if (initialIntervalMs <= 0 || multiplier < 1.0 || maxIntervalMs < initialIntervalMs) {
                throw new IllegalStateException("app.audit.dead-letter back-off is unusable: initial="
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

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public DeadLetter getDeadLetter() {
        return deadLetter;
    }
}
