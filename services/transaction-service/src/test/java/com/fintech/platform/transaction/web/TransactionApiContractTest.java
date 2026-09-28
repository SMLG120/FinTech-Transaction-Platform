package com.fintech.platform.transaction.web;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import java.time.Instant;
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
 * The published HTTP contract of transaction-service, read off the served OpenAPI document.
 *
 * <p>Same pattern as customer-service's {@code CustomerApiContractTest}: the document is
 * generated from the running application, the assertions pin the paths and property names
 * consumers rely on (the dashboard, dispute-service's payment lookup), and the endpoint
 * inherits the service's secure default.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TransactionApiContractTest {

    private static final String IDENTITY_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_transactions_contract");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.security.internal-identity.signing-key", () -> IDENTITY_KEY);
        registry.add(
                "platform.security.subject-digest.key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
        registry.add("app.outbox.relay-enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InternalIdentityCodec codec;

    @Test
    @DisplayName("serves an OpenAPI document pinning the payment paths and required fields")
    void servesContractToVerifiedCaller() throws Exception {
        docsAs("CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.paths['/api/v1/transactions']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/transactions/{id}']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/transactions/{id}/settle']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/transactions/{id}/reverse']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/balance']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/fund']").exists())
                .andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.id")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.amount")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.currency")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.status")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.payeeName")
                        .exists());
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
