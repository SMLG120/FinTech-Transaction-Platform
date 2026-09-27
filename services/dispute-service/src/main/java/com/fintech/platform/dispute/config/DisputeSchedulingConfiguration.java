package com.fintech.platform.dispute.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the outbox relay that announces dispute facts.
 *
 * <p>Separate from the application class and conditional, for the same reason as the other
 * services' scheduling configuration: every {@code @SpringBootTest} here would otherwise inherit a
 * background thread that publishes to a broker for the lifetime of the context, and a test whose
 * broker is unavailable logs failures for the rest of the suite. A red test has to mean something.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.dispute.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class DisputeSchedulingConfiguration {}
