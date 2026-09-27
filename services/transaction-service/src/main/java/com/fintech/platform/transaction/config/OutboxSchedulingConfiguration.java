package com.fintech.platform.transaction.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler that drives {@code OutboxRelay}.
 *
 * <p>Separate from the application class and conditional, for one reason: every
 * {@code @SpringBootTest} in this service otherwise inherits a background thread that queries the
 * database every 500ms for the lifetime of the context. That is noise at best — a test that starts no
 * database logs a connection failure every poll, and a test whose Testcontainers instance is torn down
 * mid-run logs one afterwards — and at worst it is a source of failures unrelated to what is under
 * test, in a suite whose whole value is that a red test means something.
 *
 * <p>Gated on {@code app.outbox.relay-enabled} rather than a test-detection heuristic. An explicit
 * switch can also be set false in a deployment that runs the relay as a separate process, which is a
 * real topology: the relay is stateless and does nothing but publish, so running it on its own is
 * natural once there is more than one instance to coordinate.
 *
 * <p>Defaults to enabled, so a deployment that has never heard of this property still publishes its
 * outbox. A default of false would fail silently, and silent is the failure mode this class exists to
 * prevent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class OutboxSchedulingConfiguration {}
