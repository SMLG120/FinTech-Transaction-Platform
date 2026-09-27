package com.fintech.platform.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Settlement cycles, reconciliation, and cross-cycle reversals.
 *
 * <p>Consumes {@code transaction-settled} and {@code transaction-reversed} and builds its own statement
 * view. It reads no table it does not own and holds no copy of the ledger; the reasoning is in ADR-0009
 * and the one-line version is that a date-range scan over the largest table in the platform does not belong
 * on the critical path of a payment.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SettlementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
