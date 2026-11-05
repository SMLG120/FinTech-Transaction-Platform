package com.fintech.platform.auth.keycloak;

/** Safe application-facing failure for Keycloak provisioning. */
public class KeycloakProvisioningException extends RuntimeException {
    private final boolean accountConflict;

    public KeycloakProvisioningException(String message) {
        this(message, false);
    }

    public KeycloakProvisioningException(String message, Throwable cause) {
        super(message, cause);
        this.accountConflict = false;
    }

    public KeycloakProvisioningException(String message, boolean accountConflict) {
        super(message);
        this.accountConflict = accountConflict;
    }

    public boolean accountConflict() {
        return accountConflict;
    }
}
