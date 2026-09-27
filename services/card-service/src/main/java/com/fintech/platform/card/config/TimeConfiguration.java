package com.fintech.platform.card.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    /**
     * Injected everywhere rather than calling {@code Instant.now()} inline.
     *
     * <p>Card expiry is a calendar decision, and a calendar decision that reads the wall clock at the
     * point of use cannot be tested at the boundary it exists to handle: "what happens the day after
     * this card expires". With a {@link Clock} the same test is a fixed instant, and the production bean
     * is indistinguishable from the one the test replaced.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
