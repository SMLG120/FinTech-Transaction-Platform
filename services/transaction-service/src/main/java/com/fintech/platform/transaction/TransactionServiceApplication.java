package com.fintech.platform.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The payment core: idempotent authorisation, limit enforcement, explicit state machine and credit reservation under concurrency.
 *
 * <p>Phase 1 note: this is an intentionally thin bootstrap. Behaviour is added in the later phases
 * and the class is not expected to change again once the feature work starts.
 *
 * <p>Scheduling is not enabled here. Phase 5 needs it for {@code OutboxRelay}, and without it the outbox
 * accumulates rows that nothing ever publishes: payments commit, the journal balances, and no event
 * reaches a consumer. That is a failure with no symptom anywhere the platform looks, which is the worst
 * shape a failure can have. It lives in {@code OutboxSchedulingConfiguration} instead, which switches
 * on the same setting that makes the relay meaningful, so that a test can start this application
 * without inheriting a background thread that queries a database the test has already shut down.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
