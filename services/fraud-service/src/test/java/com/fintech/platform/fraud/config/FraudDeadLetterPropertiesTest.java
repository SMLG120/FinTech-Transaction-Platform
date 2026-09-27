package com.fintech.platform.fraud.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.fraud.FraudFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The dead-letter settings refuse to start a consumer that would quietly drop what it cannot read.
 *
 * <p>These are the values that were once inert YAML under {@code spring.kafka.listener}, where a
 * misspelled or unsupported key is accepted and ignored rather than rejected. A retry policy that
 * validates nothing is a retry policy that does not exist, so the checks here are the difference between
 * a misconfigured deployment failing at startup and one that looks healthy while parking nothing.
 */
class FraudDeadLetterPropertiesTest {

    @Nested
    @DisplayName("rejects a policy that cannot do what it claims")
    class Rejects {

        @Test
        @DisplayName("a single attempt, which would park a record without ever retrying it")
        void oneAttemptIsNoRetryAtAll() {
            FraudProperties.DeadLetter deadLetter = valid();
            deadLetter.setMaxAttempts(1);

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-attempts must be at least 2");
        }

        @Test
        @DisplayName("a blank topic name, which would park records nowhere")
        void aBlankTopic() {
            FraudProperties.DeadLetter deadLetter = valid();
            deadLetter.setTopic("  ");

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be a topic name");
        }

        @Test
        @DisplayName("a back-off whose maximum is below its initial interval, which never backs off at all")
        void anInvertedBackOff() {
            FraudProperties.DeadLetter deadLetter = valid();
            deadLetter.setInitialIntervalMs(5_000);
            deadLetter.setMaxIntervalMs(1_000);

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("back-off is unusable");
        }

        @Test
        @DisplayName("a multiplier below one, which would shrink the delay towards zero")
        void aShrinkingBackOff() {
            FraudProperties.DeadLetter deadLetter = valid();
            deadLetter.setMultiplier(0.5);

            assertThatThrownBy(deadLetter::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("back-off is unusable");
        }

        @Test
        @DisplayName("a zero initial interval, which turns the retry into a hot loop against a broken dependency")
        void aHotRetryLoop() {
            FraudProperties.DeadLetter deadLetter = valid();
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
            FraudProperties.DeadLetter deadLetter = valid();
            deadLetter.setMaxAttempts(2);

            assertThatCode(deadLetter::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a max interval equal to the initial interval, which is a fixed back-off")
        void aFixedBackOff() {
            FraudProperties.DeadLetter deadLetter = valid();
            deadLetter.setMaxIntervalMs(deadLetter.getInitialIntervalMs());

            assertThatCode(deadLetter::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the shipped defaults, which are the ones a deployment actually runs")
        void theDefaults() {
            assertThatCode(() -> FraudFixtures.properties().getDeadLetter().validate())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the default topic is the one the platform's catalogue provisions")
        void theDefaultTopicIsTheProvisionedTopic() {
            // Against the constant rather than a literal. A literal here would keep passing after
            // somebody renames the topic in KafkaTopics and leaves this service parking events
            // somewhere TopicCatalogueTest never created, which fails at the first poison event rather
            // than at startup. The bean under test is the one the recoverer is configured from.
            assertThat(FraudFixtures.properties().getDeadLetter().getTopic()).isEqualTo(KafkaTopics.DEAD_LETTER_EVENTS);
        }
    }

    /**
     * A policy that passes, for tests to break one field at a time.
     *
     * @return valid dead-letter settings
     */
    private FraudProperties.DeadLetter valid() {
        FraudProperties.DeadLetter deadLetter = new FraudProperties.DeadLetter();
        deadLetter.setTopic("dead-letter-events");
        deadLetter.setMaxAttempts(3);
        deadLetter.setInitialIntervalMs(1_000);
        deadLetter.setMultiplier(2.0);
        deadLetter.setMaxIntervalMs(10_000);
        return deadLetter;
    }
}
