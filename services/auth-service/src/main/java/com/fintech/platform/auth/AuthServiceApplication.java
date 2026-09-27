package com.fintech.platform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Owns the link between an authenticated principal (a Keycloak subject) and a platform customer record, plus role and permission resolution.
 *
 * <p>Phase 1 note: this is an intentionally thin bootstrap. Behaviour is added in the later phases
 * and the class is not expected to change again once the feature work starts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
