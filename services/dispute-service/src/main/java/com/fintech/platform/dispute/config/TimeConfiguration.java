package com.fintech.platform.dispute.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock, injected rather than called.
 *
 * <p>The same reasoning as the other services: case timestamps and outbox occurrence instants are
 * time questions, and a rule that read the system clock itself would make those untestable without
 * waiting.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
