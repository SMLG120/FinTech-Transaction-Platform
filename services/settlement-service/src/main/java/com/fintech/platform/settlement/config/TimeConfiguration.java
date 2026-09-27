package com.fintech.platform.settlement.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock, injected rather than called.
 *
 * <p>The same reasoning as the other services, and this one leans on it hardest, because a settlement
 * cycle is defined by a date. Every boundary in this service is a date question — which day does this
 * capture belong to, which period was a refund's business date, what was open when the event arrived — and
 * a rule that read the system clock itself would make those untestable, because a test could not place a
 * payment on a day boundary without waiting for the day to change.
 *
 * <p>UTC, always, and the UTC choice is load-bearing rather than incidental: a cycle's business date is
 * derived from an event's {@code occurredAt} in UTC, so that a statement's day is the same day for every
 * consumer of that statement. A settlement date in local time is a bug waiting for a DST boundary, and
 * the two moments a year it would go wrong are exactly the two nobody tests.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
