package com.fintech.platform.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler that retries failed deliveries.
 *
 * <p>Separate from the application class and conditional, for the same reason as the other
 * services' scheduling configuration: every {@code @SpringBootTest} here would otherwise inherit a
 * background thread that queries the database for the lifetime of the context, and a test whose
 * database is torn down mid-run logs connection failures for the rest of the suite. A red test has
 * to mean something.
 *
 * <p>Gated on {@code app.notification.retry-enabled} rather than a shared name on purpose. The
 * outbox relay switch is deliberately one name across the platform because every relay does the same
 * job; a delivery retry is this service's own concern, and borrowing the outbox name for it would
 * let an operator disable publishing platform-wide and silently stop retries here too.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.notification.retry-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class NotificationSchedulingConfiguration {}
