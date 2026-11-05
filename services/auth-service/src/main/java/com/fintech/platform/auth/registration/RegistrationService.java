package com.fintech.platform.auth.registration;

import com.fintech.platform.auth.customer.CustomerProvisioningClient;
import com.fintech.platform.auth.customer.CustomerProvisioningException;
import com.fintech.platform.auth.keycloak.KeycloakProvisioningException;
import com.fintech.platform.auth.keycloak.KeycloakUserProvisioningService;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final KeycloakUserProvisioningService keycloak;
    private final CustomerProvisioningClient customers;

    public RegistrationService(KeycloakUserProvisioningService keycloak, CustomerProvisioningClient customers) {
        this.keycloak = keycloak;
        this.customers = customers;
    }

    public RegistrationDtos.RegisterResponse register(RegistrationDtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String subject;
        try {
            subject = keycloak.createCustomer(email, email, request.password(), request.fullName());
        } catch (KeycloakProvisioningException ex) {
            if (ex.accountConflict()) {
                throw RegistrationErrorCodes.EMAIL_ALREADY_REGISTERED.exception();
            }
            throw ex;
        }
        try {
            CustomerProvisioningClient.CustomerProfile profile =
                    customers.provisionCustomer(new CustomerProvisioningClient.CustomerProvisioningRequest(
                            subject,
                            request.fullName(),
                            request.dateOfBirth().toString(),
                            request.nationality(),
                            email,
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
            log.error("Registration compensation failed for a provisioned customer");
        }
    }

    public static class RegistrationException extends RuntimeException {
        public RegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
