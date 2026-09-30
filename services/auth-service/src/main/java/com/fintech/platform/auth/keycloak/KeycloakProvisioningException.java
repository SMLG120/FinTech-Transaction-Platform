package com.fintech.platform.auth.keycloak;

/** Safe application-facing failure for Keycloak provisioning. */
public class KeycloakProvisioningException extends RuntimeException {
    public KeycloakProvisioningException(String message) {
        super(message);
    }

    public KeycloakProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
