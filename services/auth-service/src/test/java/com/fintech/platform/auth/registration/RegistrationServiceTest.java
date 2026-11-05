package com.fintech.platform.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fintech.platform.auth.customer.CustomerProvisioningClient;
import com.fintech.platform.auth.keycloak.KeycloakProvisioningException;
import com.fintech.platform.auth.keycloak.KeycloakUserProvisioningService;
import com.fintech.platform.common.error.ApiException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private KeycloakUserProvisioningService keycloak;

    @Mock
    private CustomerProvisioningClient customers;

    @InjectMocks
    private RegistrationService registrations;

    @Test
    void normalizesEmailBeforeCreatingLoginAndCustomerProfile() {
        RegistrationDtos.RegisterRequest request = request(" Ada@Example.Test ");
        UUID customerId = UUID.randomUUID();
        when(keycloak.createCustomer("ada@example.test", "ada@example.test", request.password(), request.fullName()))
                .thenReturn("keycloak-subject");
        when(customers.provisionCustomer(any()))
                .thenReturn(new CustomerProvisioningClient.CustomerProfile(customerId, "ada@example.test"));

        RegistrationDtos.RegisterResponse response = registrations.register(request);

        assertThat(response.customerId()).isEqualTo(customerId);
        ArgumentCaptor<CustomerProvisioningClient.CustomerProvisioningRequest> profile =
                ArgumentCaptor.forClass(CustomerProvisioningClient.CustomerProvisioningRequest.class);
        verify(customers).provisionCustomer(profile.capture());
        assertThat(profile.getValue().email()).isEqualTo("ada@example.test");
    }

    @Test
    void turnsIdentityProviderDuplicateIntoStableConflictError() {
        RegistrationDtos.RegisterRequest request = request("ada@example.test");
        when(keycloak.createCustomer(
                        eq(request.email()),
                        eq(request.email()),
                        eq(request.password()),
                        eq(request.fullName())))
                .thenThrow(new KeycloakProvisioningException("duplicate", true));

        assertThatThrownBy(() -> registrations.register(request))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiError = (ApiException) error;
                    assertThat(apiError.getErrorCode()).isEqualTo(RegistrationErrorCodes.EMAIL_ALREADY_REGISTERED);
                });
    }

    private static RegistrationDtos.RegisterRequest request(String email) {
        return new RegistrationDtos.RegisterRequest(
                "Ada Lovelace",
                LocalDate.parse("1815-12-10"),
                "GB",
                email,
                "correct-horse-battery-staple",
                null,
                new RegistrationDtos.Address("1 Analytical Engine Way", null, "London", "EC1A 1AA", "GB"));
    }
}
