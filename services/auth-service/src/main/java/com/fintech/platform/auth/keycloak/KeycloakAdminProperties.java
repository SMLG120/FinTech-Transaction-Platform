package com.fintech.platform.auth.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.keycloak.admin")
public record KeycloakAdminProperties(
        String baseUrl,
        String realm,
        String clientId,
        String clientSecret) {

    public KeycloakAdminProperties {
        require(baseUrl, "baseUrl");
        require(realm, "realm");
        require(clientId, "clientId");
        require(clientSecret, "clientSecret");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("platform.keycloak.admin." + name + " must be configured");
        }
    }
}
