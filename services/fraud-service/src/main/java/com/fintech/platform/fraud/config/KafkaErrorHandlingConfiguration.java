package com.fintech.platform.fraud.config;

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
 * <p>This class exists because of a bug worth writing down. The fraud service carried a
 * {@code spring.kafka.listener} block with {@code max-attempts}, {@code back-off} and
 * {@code dead-letter}, under comments explaining exactly why a poison event must not block its
 * partition. None of those keys bound to anything: Spring Boot's {@code KafkaProperties.Listener} has
 * no such properties, and a {@code @ConfigurationProperties} class ignores keys it does not recognise
 * instead of failing. So the policy was documented, provisioned and inert. No {@code CommonErrorHandler}
 * bean existed anywhere in the service, which left the container on Spring Kafka's built-in default
 * handler: retry nine times with no back-off, log, commit the offset, drop the event. A transaction
 * event that could not be parsed was not parked on {@code dead-letter-events}; it was lost, with a log
 * line and nothing else. The topic existed, the docs described its contents, and no producer had ever
 * written to it.
 *
 * <p>{@code FraudDeadLetterIntegrationTest} runs a real broker and fails against the old wiring, which
 * is the only reason this is fixed rather than still believed.
 *
 * <p>Two decisions are worth stating, because both are the kind that look arbitrary:
 *
 * <ul>
 *   <li><b>Retries are the recoverer's, not the container's.</b> A {@link DefaultErrorHandler} with a
 *       back-off seeks the failed record back to its offset and re-delivers it, so a retry is a genuine
 *       re-read of the same bytes rather than a second attempt at a payload held in memory. That is what
 *       makes a transient fault — a database that was briefly unavailable — recoverable at all.
 *   <li><b>The recovered offset is committed.</b> Without {@code setCommitRecovered(true)} the container
 *       commits nothing after recovery and the record is republished on the next rebalance, so the same
 *       poison event loops between the source topic and the dead-letter topic forever. Committing is
 *       correct precisely because the recoverer has already durably published a copy somewhere that
 *       somebody can find it.
 * </ul>
 */
@Configuration
public class KafkaErrorHandlingConfiguration {

    /**
     * The error handler every listener in this service shares.
     *
     * <p>Declared as the concrete {@link DefaultErrorHandler} rather than the {@code CommonErrorHandler}
     * interface so the {@code DefaultErrorHandler} bean is what the auto-configured
     * {@code ConcurrentKafkaListenerContainerFactory} finds and applies. Spring Boot's configurer wires a
     * {@code CommonErrorHandler} bean into the factory it builds, so a bean of either type reaches the
     * container; the interface is the contract and this is the implementation.
     *
     * @param kafkaTemplate used to publish to the dead-letter topic
     * @param properties the retry and topic settings, already validated at startup
     * @return the handler the listener containers use
     */
    @Bean
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    DefaultErrorHandler fraudKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate, FraudProperties properties) {
        FraudProperties.DeadLetter settings = properties.getDeadLetter();

        // The original partition, not a hash of the key. A dead-letter topic that re-partitions its
        // contents destroys the only ordering guarantee the source topic offered, and the parked events
        // are most often read by time, which is already an unordered question.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(settings.getTopic(), record.partition()));

        // ExponentialBackOff counts retries, not attempts, so max-attempts of 3 is 2 retries: the record is
        // delivered once, retried once, and parked on the third failure. Subtracting one is the whole
        // difference between "tried three times" and "tried four", and an off-by-one here reads as harmless
        // in the config while showing up as one extra delivery attempt in production.
        ExponentialBackOff backOff = new ExponentialBackOff(settings.getInitialIntervalMs(), settings.getMultiplier());
        backOff.setMaxInterval(settings.getMaxIntervalMs());
        backOff.setMaxAttempts(settings.getMaxAttempts() - 1);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Without this the container seeks back to the failed record after recovery and re-delivers it on
        // the next poll, so the event bounces between the two topics indefinitely. The recoverer has
        // already written a durable copy, which is what makes committing the source offset safe.
        handler.setCommitRecovered(true);

        return handler;
    }

    /**
     * The dead-letter topic this service publishes to, as a bean.
     *
     * <p>Exposed so the topic name has one source in Java. {@code FraudDeadLetterPropertiesTest} asserts
     * that this bean equals {@code KafkaTopics.DEAD_LETTER_EVENTS}, because a configuration file and a
     * Java constant drifting apart produces a consumer that parks events on a topic the catalogue does
     * not provision — which fails at the moment of the first poison event, not at startup.
     *
     * @param properties the configured dead-letter settings
     * @return the resolved topic name
     */
    @Bean
    String fraudDeadLetterTopic(FraudProperties properties) {
        return properties.getDeadLetter().getTopic();
    }
}
