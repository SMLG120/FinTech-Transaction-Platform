package com.fintech.platform.audit.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock, injected rather than called.
 *
 * <p>The same reasoning as the other services: the received-at timestamps on trail rows are time
 * questions, and a rule that read the system clock itself would make those untestable without
 * waiting.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
