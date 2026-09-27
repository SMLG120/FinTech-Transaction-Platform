package com.fintech.platform.transaction.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transaction's consumer configuration, under {@code app.transaction}.
 *
 * <p>This service produced events for five phases before consuming any, so this is its first
 * consumer group. Nesting under {@code transaction} rather than a bare {@code app.consumer-group}
 * because the {@code @KafkaListener} placeholders read {@code app.transaction.consumer-group} — a
 * listener reading one name while the config declares another works only if the placeholder's
 * default happens to match, and then the setting is silently ignored.
 */
@ConfigurationProperties(prefix = "app.transaction")
public class TransactionConsumerProperties implements InitializingBean {

    private String consumerGroup = "transaction-service";

    private final DeadLetter deadLetter = new DeadLetter();

    @Override
    public void afterPropertiesSet() {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalStateException("app.transaction.consumer-group must be a group name");
        }
        deadLetter.validate();
    }

    /**
     * What happens to a resolution this consumer cannot read.
     *
     * <p>An unreadable dispute resolution is money whose fate is unknown: the case says refunded
     * and this service cannot tell which payment it means. So the policy is the same bounded
     * retries, then parked on {@code dead-letter-events} where somebody will find it — and it is
     * validated for the same reason as everywhere else: Spring Kafka's default on a poison record
     * is to retry, log, commit and drop, which reads as healthy while losing the refund.
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
         * retrying it, which turns a transient broker blip into a permanently unrefunded dispute.
         */
        public void validate() {
            if (topic == null || topic.isBlank()) {
                throw new IllegalStateException("app.transaction.dead-letter.topic must be a topic name");
            }
            if (maxAttempts < 2) {
                throw new IllegalStateException("app.transaction.dead-letter.max-attempts must be at least 2, got "
                        + maxAttempts + "; a value of 1 would park a record without ever retrying it, which turns "
                        + "a transient failure into a permanently unrefunded dispute");
            }
            if (initialIntervalMs <= 0 || multiplier < 1.0 || maxIntervalMs < initialIntervalMs) {
                throw new IllegalStateException("app.transaction.dead-letter back-off is unusable: initial="
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
