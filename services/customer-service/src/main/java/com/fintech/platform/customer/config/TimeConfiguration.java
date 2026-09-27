package com.fintech.platform.customer.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time, as a bean.
 *
 * <p>The rest of the platform reads the system clock inline. That is fine for signing a request or
 * stamping a log line, but not for anything a business rule depends on: an age eligibility check, an
 * approval expiry date, a "last seen within" window. Those rules are only testable at their boundary
 * if the boundary can be moved, and a rule pinned to a fixed date is a rule nobody can test at all.
 *
 * <p>UTC unconditionally. The customer records dates of birth and expiry dates with no zone, and
 * letting a host's local zone decide when a customer's approval lapses would make the answer depend
 * on which region the pod happened to be scheduled in.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
