package com.fintech.platform.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Append-only audit trail. Write path is insert-only; there is no update or delete API.
 *
 * <p>Phase 1 note: this is an intentionally thin bootstrap. Behaviour is added in the later phases
 * and the class is not expected to change again once the feature work starts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
