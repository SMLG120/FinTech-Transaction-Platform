package com.fintech.platform.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Explainable rule-based risk scoring. Deterministic first, ML-ready later.
 *
 * <p>Phase 1 note: this is an intentionally thin bootstrap. Behaviour is added in the later phases
 * and the class is not expected to change again once the feature work starts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
