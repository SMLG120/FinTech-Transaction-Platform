package com.fintech.platform.fraud.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler that drives this service's background work: the outbox relay and the retention
 * jobs.
 *
 * <p>Separate from the application class and conditional, for the same reason as transaction-service's
 * {@code OutboxSchedulingConfiguration}: every {@code @SpringBootTest} in this service would otherwise
 * inherit background threads that query the database and publish to Kafka for the lifetime of the
 * context, and a test whose Testcontainers instance is torn down mid-run logs connection failures for the
 * rest of the suite. A red test has to mean something.
 *
 * <p><b>Gated on {@code app.outbox.relay-enabled}</b> — the same property name transaction-service uses,
 * deliberately, so that the switch an operator has already read in the docs, in the Compose file and in
 * the transaction-service config works here without having to know this service invented a name for it.
 * A service-specific synonym for the same concern is a trap: someone setting
 * {@code app.outbox.relay-enabled=false} across the platform and finding the fraud relay still publishing
 * would have no way to guess that a second, differently-named property was the one being read.
 *
 * <p>The individual jobs are gated separately, because they are separable concerns: a deployment that
 * runs the relay as its own process wants the relay off here and the retention jobs on, which the two
 * properties express between them. Note the consequence, since it is not obvious from the names — this
 * class enables scheduling for both, so {@code relay-enabled=false} stops the retention jobs too unless
 * {@code background-jobs-enabled} is also involved; set both to false to run neither in this process.
 *
 * <p>Both default to enabled. A default of false would mean a deployment that has never heard of these
 * properties silently never publishes its {@code fraud-analysis-completed} events, which is the failure
 * mode this class exists to prevent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class FraudSchedulingConfiguration {}
