package com.fintech.platform.auth.registration;

import com.fintech.platform.auth.customer.CustomerProvisioningClient;
import com.fintech.platform.auth.customer.CustomerProvisioningException;
import com.fintech.platform.auth.keycloak.KeycloakProvisioningException;
import com.fintech.platform.auth.keycloak.KeycloakUserProvisioningService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private static final Logger log = Logger.getLogger(RegistrationService.class.getName());

    private final KeycloakUserProvisioningService keycloak;
    private final CustomerProvisioningClient customers;

    public RegistrationService(KeycloakUserProvisioningService keycloak, CustomerProvisioningClient customers) {
        this.keycloak = keycloak;
        this.customers = customers;
    }

    public RegistrationDtos.RegisterResponse register(RegistrationDtos.RegisterRequest request) {
        String subject = keycloak.createCustomer(request.email(), request.email(), request.password());
        try {
            CustomerProvisioningClient.CustomerProfile profile = customers.provisionCustomer(
                    new CustomerProvisioningClient.CustomerProvisioningRequest(
                            subject,
                            request.fullName(),
                            request.dateOfBirth().toString(),
                            request.nationality(),
                            request.email(),
                            request.phone(),
                            new CustomerProvisioningClient.Address(
                                    request.address().line1(),
                                    request.address().line2(),
                                    request.address().city(),
                                    request.address().postalCode(),
                                    request.address().country())));
            return new RegistrationDtos.RegisterResponse(profile.id(), "Registration successful");
        } catch (CustomerProvisioningException ex) {
            compensate(subject);
            throw new RegistrationException("Registration could not be completed", ex);
        }
    }

    private void compensate(String subject) {
        try {
            keycloak.deleteUser(subject);
        } catch (KeycloakProvisioningException ex) {
            log.log(Level.SEVERE, "Registration compensation failed for Keycloak user " + subject, ex);
        }
    }

    public static class RegistrationException extends RuntimeException {
        public RegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
