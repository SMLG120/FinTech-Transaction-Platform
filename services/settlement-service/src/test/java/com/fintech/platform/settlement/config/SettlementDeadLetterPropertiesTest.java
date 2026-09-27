package com.fintech.platform.settlement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.event.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The dead-letter settings refuse to start a service that would quietly lose a statement line.
 *
 * <p>Settlement's version of this failure is more expensive than a dropped event in a service that keeps
 * its own state. A malformed {@code transaction-settled} that is discarded rather than parked means money
 * that moved in the ledger and never appears on a statement; the period is short by exactly that amount,
 * the reconciliation break names a missing total, and nothing in the system can say <em>which</em> payment
 * is missing. The break is still true and still useless.
 *
 * <p>These are also the checks that make the property binding itself trustworthy. An unsupported key under
 * a {@code @ConfigurationProperties} class is ignored rather than rejected, so a policy assembled from
 * misspelled keys is a policy that validates nothing and parks nothing while the deployment reports healthy.
 * That is not hypothetical in this repository: fraud-service carried its retry policy under
 * {@code spring.kafka.listener}, where every key bound to nothing.
 */
class SettlementDeadLetterPropertiesTest {

    @Nested
    @DisplayName("rejects a policy that cannot do what it claims")
    class Rejects {

        @Test
        @DisplayName("a single attempt, which would park a record without ever retrying it")
        void oneAttemptIsNoRetryAtAll() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setMaxAttempts(1);

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-attempts must be at least 2");
        }

        @Test
        @DisplayName("a blank topic name, which would park records nowhere")
        void aBlankTopic() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setTopic("  ");

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be a topic name");
        }

        @Test
        @DisplayName("a back-off whose maximum is below its initial interval, which never backs off at all")
        void anInvertedBackOff() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setInitialIntervalMs(5_000);
            deadLetter.setMaxIntervalMs(1_000);

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("back-off is unusable");
        }

        @Test
        @DisplayName("a multiplier below one, which would shrink the delay towards zero")
        void aShrinkingBackOff() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setMultiplier(0.5);

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("back-off is unusable");
        }

        @Test
        @DisplayName("a zero initial interval, which turns the retry into a hot loop against a broken broker")
        void aHotRetryLoop() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setInitialIntervalMs(0);

            assertThatThrownBy(deadLetter::validate).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("accepts a policy that can")
    class Accepts {

        @Test
        @DisplayName("exactly two attempts, the smallest value that still retries")
        void twoAttemptsIsTheFloorNotARefusal() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setMaxAttempts(2);

            assertThatCode(deadLetter::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a max interval equal to the initial interval, which is a fixed back-off")
        void aFixedBackOff() {
            SettlementProperties.DeadLetter deadLetter = valid();
            deadLetter.setMaxIntervalMs(deadLetter.getInitialIntervalMs());

            assertThatCode(deadLetter::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the shipped defaults, which are the ones a deployment actually runs")
        void theDefaults() {
            SettlementProperties.DeadLetter deadLetter = new SettlementProperties.DeadLetter();

            assertThatCode(deadLetter::validate).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the default topic is the one the platform's catalogue provisions")
    void theDefaultTopicIsTheProvisionedTopic() {
        // Asserted against the constant rather than a string literal. A literal here would keep passing
        // after somebody renames the topic in KafkaTopics, and the failure would arrive at the first
        // poison event as a publish to a topic that does not exist -- while this service's dead-letter
        // bean, the recoverer and the catalogue all disagree about where "nowhere to park" is.
        assertThat(new SettlementProperties.DeadLetter().getTopic())
                .as("the dead-letter topic is the platform's, not a per-service one")
                .isEqualTo(KafkaTopics.DEAD_LETTER_EVENTS);
    }

    /**
     * A policy that passes, for tests to break one field at a time.
     *
     * @return valid dead-letter settings
     */
    private SettlementProperties.DeadLetter valid() {
        SettlementProperties.DeadLetter deadLetter = new SettlementProperties.DeadLetter();
        deadLetter.setTopic("dead-letter-events");
        deadLetter.setMaxAttempts(3);
        deadLetter.setInitialIntervalMs(1_000);
        deadLetter.setMultiplier(2.0);
        deadLetter.setMaxIntervalMs(10_000);
        return deadLetter;
    }
}
