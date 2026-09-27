package com.fintech.platform.notification.config;

import com.fintech.platform.notification.service.NotificationProperties;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Wires the retry-then-park policy that {@code application.yml} describes and the topic catalogue
 * provisions.
 *
 * <p>The same policy fraud-service and settlement-service carry, for the same reason: without it,
 * Spring Kafka's default on a poison record is to retry on its own schedule, log, commit the offset
 * and drop the event. A dropped {@code transaction-settled} event here is money that moved and
 * nobody was told, with a log line as the only evidence. The policy retries a bounded number of
 * times with back-off, then republishes to {@code dead-letter-events} where somebody will find it.
 *
 * <p>Two decisions worth stating, because both look arbitrary:
 *
 * <ul>
 *   <li><b>Retries are the recoverer's, not the container's.</b> A {@link DefaultErrorHandler} with
 *       a back-off re-delivers the same bytes rather than re-attempting a payload held in memory,
 *       which is what makes a transient fault recoverable at all.
 *   <li><b>The recovered offset is committed.</b> Without {@code setCommitRecovered(true)} the
 *       container re-delivers the record after recovery and the event loops between the source topic
 *       and the dead-letter topic forever. Committing is correct precisely because the recoverer has
 *       already durably published a copy somewhere findable.
 * </ul>
 */
@Configuration
public class KafkaErrorHandlingConfiguration {

    /**
     * The error handler every listener in this service shares.
     *
     * <p>Declared as the concrete {@link DefaultErrorHandler} rather than the
     * {@code CommonErrorHandler} interface so the auto-configured listener factory finds and applies
     * it.
     *
     * @param kafkaTemplate used to publish to the dead-letter topic
     * @param properties the retry and topic settings, already validated at startup
     * @return the handler the listener containers use
     */
    @Bean
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    DefaultErrorHandler notificationKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate, NotificationProperties properties) {
        NotificationProperties.DeadLetter settings = properties.getDeadLetter();

        // The original partition, not a hash of the key. A dead-letter topic that re-partitions its
        // contents destroys the only ordering guarantee the source topic offered.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(settings.getTopic(), record.partition()));

        // ExponentialBackOff counts retries, not attempts, so max-attempts of 3 is 2 retries: the record is
        // delivered once, retried once, and parked on the third failure.
        ExponentialBackOff backOff = new ExponentialBackOff(settings.getInitialIntervalMs(), settings.getMultiplier());
        backOff.setMaxInterval(settings.getMaxIntervalMs());
        backOff.setMaxAttempts(settings.getMaxAttempts() - 1);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Without this the container seeks back to the failed record after recovery and re-delivers it
        // on the next poll, so the event bounces between the two topics indefinitely. The recoverer has
        // already written a durable copy, which is what makes committing the source offset safe.
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
    String notificationDeadLetterTopic(NotificationProperties properties) {
        return properties.getDeadLetter().getTopic();
    }
}
