package com.fintech.platform.transaction.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    /**
     * Injected everywhere rather than calling {@code Instant.now()} inline.
     *
     * <p>The ledger is timestamped by whoever posts it, and a post has to be distinguishable from the
     * post that follows it. That is a clock decision with a boundary worth testing: a limit is a
     * calendar-day limit, so "what happens to the last payment of the day at 23:59:59.999" is a real
     * question and one that is unanswerable if the code under test decides for itself what time it is.
     * With a {@link Clock} the day boundary is a fixed instant, and the production bean is
     * indistinguishable from the one the test replaced.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
