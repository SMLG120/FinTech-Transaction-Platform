package com.fintech.platform.auth.keycloak;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KeycloakRegistrationRollbackTest {

    @Test
    void deletesCreatedIdentityWhenCustomerRoleAssignmentFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakUserProvisioningService service = new KeycloakUserProvisioningService(
                builder, new KeycloakAdminProperties("http://keycloak", "fintech", "registration", "secret"));

        server.expect(requestTo("http://keycloak/realms/fintech/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://keycloak/admin/realms/fintech/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .header(HttpHeaders.LOCATION, "http://keycloak/admin/realms/fintech/users/user-123"));
        server.expect(requestTo("http://keycloak/admin/realms/fintech/roles/CUSTOMER"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo("http://keycloak/realms/fintech/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://keycloak/admin/realms/fintech/users/user-123"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> service.createCustomer(
                        "ada@example.test", "ada@example.test", "ignored-test-value", "Ada Lovelace"))
                .isInstanceOf(KeycloakProvisioningException.class);

        server.verify();
    }
}
