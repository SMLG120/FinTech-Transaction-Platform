package com.fintech.platform.settlement.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler that drives this service's outbox relay.
 *
 * <p>Separate from the application class and conditional, for the same reason as
 * {@code FraudSchedulingConfiguration}: every {@code @SpringBootTest} here would otherwise inherit
 * background threads that query the database and publish to Kafka for the lifetime of the context, and a
 * test whose Testcontainers instance is torn down mid-run logs connection failures for the rest of the
 * suite. A red test has to mean something.
 *
 * <p>Gated on {@code app.outbox.relay-enabled} — deliberately the same property name the other services
 * use, so the switch an operator has already read in the docs and in the Compose file works here without
 * having to learn that this service invented a synonym. A service-specific name for the same concern is a
 * trap: somebody setting {@code app.outbox.relay-enabled=false} across the platform and finding the
 * settlement relay still publishing would have no way to guess a second property was the one being read.
 *
 * <p>Defaults to enabled, because a default of false means a deployment that has never heard of this
 * property silently never publishes {@code settlement-cycle-finalised} — and a settlement service that counts
 * money correctly and tells nobody is not doing its job.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SettlementSchedulingConfiguration {}
