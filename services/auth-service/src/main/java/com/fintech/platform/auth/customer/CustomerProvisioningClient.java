package com.fintech.platform.auth.customer;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The single Auth Service operation needed to provision a customer profile. */
@Service
public class CustomerProvisioningClient {
    private static final String PATH = "/api/v1/customers/internal/provision";
    private static final String AUTH_SUBJECT = "auth-service";

    private final RestClient http;
    private final InternalIdentityCodec identityCodec;
    private final Clock clock;

    public CustomerProvisioningClient(
            RestClient.Builder builder,
            InternalIdentityCodec identityCodec,
            Clock clock,
            CustomerProvisioningProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeoutMs());
        factory.setReadTimeout((int) properties.timeoutMs());
        this.http = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.identityCodec = identityCodec;
        this.clock = clock;
    }

    public CustomerProfile provisionCustomer(CustomerProvisioningRequest request) {
        InternalIdentity identity = new InternalIdentity(
                AUTH_SUBJECT, AUTH_SUBJECT, List.of(), UUID.randomUUID().toString(), Instant.now(clock));
        try {
            CustomerProfile response = http.post()
                    .uri(PATH)
                    .headers(headers -> identityCodec.headersFor(identity).forEach(headers::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (ignored, responseEntity) -> {
                        HttpStatusCode status = responseEntity.getStatusCode();
                        throw new CustomerProvisioningException(
                                status.value() == 409 ? "Customer profile already exists" : "Customer profile provisioning failed",
                                status);
                    })
                    .body(CustomerProfile.class);
            if (response == null) {
                throw new CustomerProvisioningException("Customer profile provisioning returned no response", null);
            }
            return response;
        } catch (CustomerProvisioningException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new CustomerProvisioningException("Customer Service is unavailable", null, ex);
        }
    }

    public record CustomerProvisioningRequest(
            String keycloakSubject,
            String fullName,
            String dateOfBirth,
            String nationality,
            String email,
            String phone,
            Address address) {}

    public record Address(String line1, String line2, String city, String postalCode, String country) {}

    public record CustomerProfile(UUID id, String email) {}
}
