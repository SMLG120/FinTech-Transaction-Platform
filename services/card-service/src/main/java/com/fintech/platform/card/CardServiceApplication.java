package com.fintech.platform.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * System of record for simulated cards and their lifecycle. Never stores a PAN or CVV.
 *
 * <p>Phase 1 note: this is an intentionally thin bootstrap. Behaviour is added in the later phases
 * and the class is not expected to change again once the feature work starts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CardServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardServiceApplication.class, args);
    }
}
