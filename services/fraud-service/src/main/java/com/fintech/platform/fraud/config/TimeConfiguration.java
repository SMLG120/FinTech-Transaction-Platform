package com.fintech.platform.fraud.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock, injected rather than called.
 *
 * <p>Same reasoning as transaction-service's, and the fraud engine leans on it harder. Several rules
 * compare an elapsed duration — recent card activation, rapid network change, the alert SLA — and each of
 * those is a boundary question: "what happens at exactly 24 hours". A rule that read the system clock
 * itself would make that untestable, because a test could not place itself on the boundary without
 * sleeping.
 *
 * <p>Every timestamp in this service comes from here: the decision's {@code evaluatedAt}, the observation
 * rows' {@code firstSeenAt}, the alert's {@code createdAt}, the outbox row's {@code occurredAt}. Having
 * them agree is what makes "the event carries the same instant as the state change it describes" true
 * rather than approximately true.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    /** UTC, always. A fraud window is not a local-timezone question. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
