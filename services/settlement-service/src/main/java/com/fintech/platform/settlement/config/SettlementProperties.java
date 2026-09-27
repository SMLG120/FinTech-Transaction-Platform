package com.fintech.platform.settlement.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settlement's own configuration, under {@code app.settlement}.
 *
 * <p>Almost nothing, and that is the shape of a service whose job is arithmetic. There are no thresholds to
 * tune, no rules to weight and no decision bands: a cycle either balances against a declared figure or it
 * does not, and there is no value of "nearly". What does need configuring is the consumer group and what to
 * do with an event this service cannot read.
 *
 * <p>Nesting under {@code settlement} rather than under a bare {@code app.consumer-group} because the
 * {@code @KafkaListener} placeholders read {@code app.settlement.consumer-group}. A listener reading
 * {@code app.consumer-group} while the config says {@code app.settlement.consumer-group} works only if the
 * placeholder's default happens to match, and then the setting is silently ignored.
 */
@ConfigurationProperties(prefix = "app.settlement")
public class SettlementProperties implements InitializingBean {

    private final DeadLetter deadLetter = new DeadLetter();

    private String consumerGroup = "settlement-service";

    /**
     * What happens to an event this consumer cannot read.
     *
     * <p>This service has a consumer, so it needs a dead-letter policy for the same reason fraud-service
     * does, and the reason is not symmetry for its own sake. Without one, Spring Kafka's default retries a
     * poison record a few times, logs it, commits the offset and drops it — so a malformed
     * {@code transaction-settled} event means money that moved in the ledger and never appears on a
     * statement, with a log line as the only evidence. The cycle will simply be short, the short total will
     * not match any bank figure, and the resulting reconciliation break will name a missing amount without
     * being able to say which payment is missing. That is a genuinely expensive failure, and the topic
     * catalogue provisions {@code dead-letter-events} and the docs describe its contents.
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
         * <p>The floor of 2 is the load-bearing part. A value of 1 parks a record without ever retrying it,
         * which sounds like tidiness and is in fact a way of turning a transient broker blip into a
         * permanently missing statement line. One retry is enough to ride out the failure this actually
         * needs to survive.
         */
        public void validate() {
            if (topic == null || topic.isBlank()) {
                throw new IllegalStateException("app.settlement.dead-letter.topic must be a topic name");
            }
            if (maxAttempts < 2) {
                throw new IllegalStateException("app.settlement.dead-letter.max-attempts must be at least 2, got "
                        + maxAttempts + "; a value of 1 would park a record without ever retrying it, which turns "
                        + "a transient failure into a permanently missing statement line");
            }
            if (initialIntervalMs <= 0 || multiplier < 1.0 || maxIntervalMs < initialIntervalMs) {
                throw new IllegalStateException("app.settlement.dead-letter back-off is unusable: initial="
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

    public DeadLetter getDeadLetter() {
        return deadLetter;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    /**
     * Checks the whole tree at startup.
     *
     * <p>{@link InitializingBean} rather than a {@code @PostConstruct} on a separate configuration class,
     * so validation cannot be wired up in one place and forgotten in another: this runs whenever the
     * properties bean is created, which is whenever the service starts. A bad policy then stops the service
     * starting, rather than being discovered when the first poison event arrives — by which point the
     * interesting failure has already happened and the policy was supposed to prevent it.
     */
    @Override
    public void afterPropertiesSet() {
        deadLetter.validate();
    }
}
