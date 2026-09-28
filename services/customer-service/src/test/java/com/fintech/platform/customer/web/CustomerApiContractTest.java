package com.fintech.platform.customer.web;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The published HTTP contract of customer-service, read off the served OpenAPI document.
 *
 * <p>Phase 12's premise is that the wire shape is a contract, not an accident of the code: a
 * renamed field or a removed endpoint must break this build on the producer side before it breaks
 * a consumer (card-service's eligibility check, the React dashboard's DTOs) in production. The
 * document is generated from the running application rather than checked in, so it cannot drift
 * from what the service actually serves; the assertions below pin the parts consumers rely on —
 * the paths and the required fields — without snapshotting the whole document, which would fail
 * on every cosmetic change.
 *
 * <p>The spec endpoint inherits the service's secure default: it answers a verified caller and
 * refuses anyone else, like every other non-operational path.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CustomerApiContractTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static final String IDENTITY_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_customers_contract");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.customer.pii.master-key", () -> KEY);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("platform.security.internal-identity.signing-key", () -> IDENTITY_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InternalIdentityCodec codec;

    @Test
    @DisplayName("serves an OpenAPI document pinning the customer paths and required fields")
    void servesContractToVerifiedCaller() throws Exception {
        docsAs("CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.paths['/api/v1/customers']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/customers/me']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/customers/me/kyc']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/customers/{customerId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/customers/{customerId}/kyc']")
                        .exists())
                // Property names, not a snapshot: a renamed or removed field breaks a
                // consumer (card-service's eligibility parser, the dashboard DTOs), so it
                // breaks here first. Springdoc emits no `required` for unannotated
                // records, and adding annotations to production DTOs to please the test
                // would invert the relationship — the document describes the code.
                .andExpect(jsonPath("$.components.schemas.CustomerResponse.properties.id")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CustomerResponse.properties.fullName")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CustomerResponse.properties.email")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CustomerResponse.properties.kycStatus")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CustomerResponse.properties.masked")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.KycResponse").exists());
    }

    @Test
    @DisplayName("refuses the contract document to a caller with no verified identity")
    void refusesContractToAnonymousCaller() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions docsAs(String... roles) throws Exception {
        InternalIdentity identity = new InternalIdentity(
                "contract-test-subject", "contract-test", List.of(roles), "correlation-contract-1", Instant.now());
        MockHttpServletRequestBuilder request = get("/v3/api-docs");
        for (Map.Entry<String, String> header : codec.headersFor(identity).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        return mvc.perform(request);
    }
}
