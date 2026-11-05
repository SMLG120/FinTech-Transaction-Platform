package com.fintech.platform.auth.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KeycloakUserProvisioningServiceTest {

    private MockRestServiceServer server;

    @Test
    @DisplayName("splits a full name into Keycloak's required first and last name fields")
    void splitsCompoundName() {
        assertThat(KeycloakUserProvisioningService.nameParts("Dana Okonkwo"))
                .isEqualTo(new KeycloakUserProvisioningService.NameParts("Dana", "Okonkwo"));
    }

    @Test
    @DisplayName("keeps remaining name parts together")
    void keepsCompoundFamilyNameTogether() {
        assertThat(KeycloakUserProvisioningService.nameParts("Mary Ann Smith"))
                .isEqualTo(new KeycloakUserProvisioningService.NameParts("Mary", "Ann Smith"));
    }

    @Test
    @DisplayName("does not invent a different name for a mononym")
    void preservesMononym() {
        assertThat(KeycloakUserProvisioningService.nameParts("Cher"))
                .isEqualTo(new KeycloakUserProvisioningService.NameParts("Cher", "Cher"));
    }

    @Test
    @DisplayName("provisions Keycloak with the customer's first and last name")
    void provisionsUserNameFields() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        KeycloakUserProvisioningService service = new KeycloakUserProvisioningService(
                builder, new KeycloakAdminProperties("http://keycloak", "fintech", "registration", "secret"));

        server.expect(requestTo("http://keycloak/realms/fintech/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://keycloak/admin/realms/fintech/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content()
                        .json(
                                "{\"username\":\"dana@example.test\",\"email\":\"dana@example.test\","
                                        + "\"firstName\":\"Dana\",\"lastName\":\"Okonkwo\"}",
                                false))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .header(HttpHeaders.LOCATION, "http://keycloak/admin/realms/fintech/users/user-123"));
        server.expect(requestTo("http://keycloak/admin/realms/fintech/roles/CUSTOMER"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess("{\"id\":\"customer-role\",\"name\":\"CUSTOMER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://keycloak/admin/realms/fintech/users/user-123/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        assertThat(service.createCustomer(
                        "dana@example.test", "dana@example.test", "ignored-test-value", "Dana Okonkwo"))
                .isEqualTo("user-123");
    }

    @AfterEach
    void verifyNoUnexpectedRequests() {
        if (server != null) {
            server.verify();
        }
    }
}
