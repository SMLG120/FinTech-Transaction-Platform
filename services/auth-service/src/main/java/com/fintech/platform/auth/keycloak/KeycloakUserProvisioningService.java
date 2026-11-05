package com.fintech.platform.auth.keycloak;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The deliberately small server-side seam for public customer registration.
 * The role is not an argument: every user created through this seam is a CUSTOMER.
 */
@Service
public class KeycloakUserProvisioningService {
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final Logger log = LoggerFactory.getLogger(KeycloakUserProvisioningService.class);

    private final RestClient http;
    private final KeycloakAdminProperties properties;

    public KeycloakUserProvisioningService(RestClient.Builder builder, KeycloakAdminProperties properties) {
        this.http = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
    }

    public String createCustomer(String username, String email, String password, String fullName) {
        String token = accessToken();
        NameParts name = nameParts(fullName);
        try {
            String location = http.post()
                    .uri("/admin/realms/{realm}/users", properties.realm())
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username",
                            username,
                            "email",
                            email,
                            "firstName",
                            name.firstName(),
                            "lastName",
                            name.lastName(),
                            "enabled",
                            true,
                            "credentials",
                            List.of(Map.of("type", "password", "value", password, "temporary", false))))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        boolean accountConflict = response.getStatusCode().value() == 409;
                        throw new KeycloakProvisioningException(
                                accountConflict
                                        ? "An account with that username or email already exists"
                                        : "Unable to create customer account",
                                accountConflict);
                    })
                    .toBodilessEntity()
                    .getHeaders()
                    .getFirst("Location");
            String userId = userId(location);
            try {
                assignCustomerRole(token, userId);
            } catch (KeycloakProvisioningException ex) {
                // Do not leave a login behind if role assignment fails. Without this rollback the
                // identity may exist in Keycloak while having no customer profile in the platform.
                try {
                    deleteUser(userId);
                } catch (KeycloakProvisioningException cleanupFailure) {
                    log.error("Registration cleanup failed for a partially provisioned customer");
                    ex.addSuppressed(cleanupFailure);
                }
                throw ex;
            }
            return userId;
        } catch (KeycloakProvisioningException ex) {
            throw ex;
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new KeycloakProvisioningException("Unable to create customer account", ex);
        }
    }

    /** Compensates a profile-creation failure; never exposed as an HTTP operation. */
    public void deleteUser(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw new KeycloakProvisioningException("Unable to clean up customer account");
        }
        String token = accessToken();
        try {
            http.delete()
                    .uri("/admin/realms/{realm}/users/{userId}", properties.realm(), keycloakUserId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new KeycloakProvisioningException("Unable to clean up customer account");
                    })
                    .toBodilessEntity();
        } catch (KeycloakProvisioningException ex) {
            log.error("Keycloak cleanup failed for a provisioned customer");
            throw ex;
        } catch (RestClientException ex) {
            log.error("Keycloak cleanup unavailable for a provisioned customer");
            throw new KeycloakProvisioningException("Unable to clean up customer account", ex);
        }
    }

    private String accessToken() {
        try {
            TokenResponse response = http.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials&client_id=" + encode(properties.clientId()) + "&client_secret="
                            + encode(properties.clientSecret()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response1) -> {
                        throw new KeycloakProvisioningException("Unable to authenticate provisioning service");
                    })
                    .body(TokenResponse.class);
            if (response == null
                    || response.accessToken() == null
                    || response.accessToken().isBlank()) {
                throw new KeycloakProvisioningException("Unable to authenticate provisioning service");
            }
            return response.accessToken();
        } catch (KeycloakProvisioningException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new KeycloakProvisioningException("Unable to authenticate provisioning service", ex);
        }
    }

    private void assignCustomerRole(String token, String userId) {
        try {
            Map<?, ?> role = http.get()
                    .uri("/admin/realms/{realm}/roles/{role}", properties.realm(), CUSTOMER_ROLE)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new KeycloakProvisioningException("Unable to assign customer role");
                    })
                    .body(Map.class);
            http.post()
                    .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", properties.realm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new KeycloakProvisioningException("Unable to assign customer role");
                    })
                    .toBodilessEntity();
        } catch (KeycloakProvisioningException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new KeycloakProvisioningException("Unable to assign customer role", ex);
        }
    }

    private static String userId(String location) {
        if (location == null || location.isBlank()) {
            throw new KeycloakProvisioningException("Unable to create customer account");
        }
        int slash = location.lastIndexOf('/');
        if (slash < 0 || slash == location.length() - 1) {
            throw new KeycloakProvisioningException("Unable to create customer account");
        }
        return location.substring(slash + 1);
    }

    static NameParts nameParts(String fullName) {
        String[] parts = fullName.trim().split("\\s+", 2);
        String firstName = parts[0];
        // Keycloak's local user profile requires both fields to be populated. Preserve a mononym
        // rather than inventing a surname when the customer supplied only one name.
        String lastName = parts.length == 2 ? parts[1] : firstName;
        return new NameParts(firstName, lastName);
    }

    record NameParts(String firstName, String lastName) {}

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record TokenResponse(String access_token) {
        String accessToken() {
            return access_token;
        }
    }
}
