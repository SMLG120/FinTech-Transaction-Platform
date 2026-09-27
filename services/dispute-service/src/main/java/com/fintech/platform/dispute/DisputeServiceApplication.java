package com.fintech.platform.dispute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Chargeback workflow from customer complaint to investigation decision.
 *
 * <p>Phase 1 note: this is an intentionally thin bootstrap. Behaviour is added in the later phases
 * and the class is not expected to change again once the feature work starts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DisputeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DisputeServiceApplication.class, args);
    }
}
