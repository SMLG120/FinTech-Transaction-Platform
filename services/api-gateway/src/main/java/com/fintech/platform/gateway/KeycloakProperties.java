package com.fintech.platform.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the gateway finds Keycloak, and which token audience it will accept.
 *
 * @param issuer the exact {@code iss} claim the gateway requires
 * @param jwkSetUri where the signing keys are fetched from
 * @param audience the {@code aud} value a token must carry to be for this API
 */
@ConfigurationProperties(prefix = "platform.keycloak")
public record KeycloakProperties(String issuer, String jwkSetUri, String audience) {

    public KeycloakProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("platform.keycloak.issuer must be configured");
        }
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalArgumentException("platform.keycloak.jwk-set-uri must be configured");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("platform.keycloak.audience must be configured");
        }
    }

    public List<String> audiences() {
        return List.of(audience);
    }
}
