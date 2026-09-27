package com.fintech.platform.transaction.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The retry-then-park policy for this service's first consumer.
 *
 * <p>Five phases of producing without consuming meant no error handler was needed; dispute
 * resolutions change that. Without it, Spring Kafka's default on a poison record is to retry on its
 * own schedule, log, commit the offset and drop the event — a resolution nobody can read vanishing
 * while the case says refunded. The policy retries a bounded number of times with back-off, then
 * republishes to {@code dead-letter-events} where somebody will find it.
 */
@Configuration
public class KafkaErrorHandlingConfiguration {

    /**
     * The error handler every listener in this service shares.
     *
     * <p>Declared as the concrete {@link DefaultErrorHandler} rather than the {@code
     * CommonErrorHandler} interface so the auto-configured listener factory finds and applies it.
     *
     * @param kafkaTemplate used to publish to the dead-letter topic
     * @param properties the retry and topic settings, already validated at startup
     * @return the handler the listener containers use
     */
    @Bean
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    DefaultErrorHandler transactionKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate, TransactionConsumerProperties properties) {
        TransactionConsumerProperties.DeadLetter settings = properties.getDeadLetter();

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(settings.getTopic(), record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(settings.getInitialIntervalMs(), settings.getMultiplier());
        backOff.setMaxInterval(settings.getMaxIntervalMs());
        backOff.setMaxAttempts(settings.getMaxAttempts() - 1);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Without this the container seeks back to the failed record after recovery and re-delivers
        // it on the next poll, so the event bounces between the two topics indefinitely. The
        // recoverer has already written a durable copy, which is what makes committing the source
        // offset safe.
        handler.setCommitRecovered(true);

        return handler;
    }

    /**
     * The dead-letter topic this service publishes to, as a bean.
     *
     * <p>Exposed so the topic name has one source in Java: a configuration file and a Java constant
     * drifting apart produces a consumer that parks events on a topic the catalogue does not
     * provision — which fails at the moment of the first poison event, not at startup.
     */
    @Bean
    String transactionDeadLetterTopic(TransactionConsumerProperties properties) {
        return properties.getDeadLetter().getTopic();
    }
}
