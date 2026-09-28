package com.fintech.platform.settlement.web;

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
 * The published HTTP contract of settlement-service, read off the served OpenAPI document.
 *
 * <p>Same pattern as the other services' contract tests. The pinned paths are the reads and
 * the four money-moving actions the dashboard calls; the pinned fields are the figures it
 * renders — including the null-able actual and difference, because "not declared yet" and
 * "zero" are different facts about a period.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiContractTest {

    private static final String IDENTITY_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_settlement_contract");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.security.internal-identity.signing-key", () -> IDENTITY_KEY);
        registry.add("app.settlement.outbox.relay-enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InternalIdentityCodec codec;

    @Test
    @DisplayName("serves an OpenAPI document pinning the settlement paths and required fields")
    void servesContractToVerifiedCaller() throws Exception {
        docsAs("SETTLEMENT_OPERATOR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.paths['/api/v1/settlement/cycles']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/settlement/cycles/{reference}']")
                        .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/settlement/cycles/close']").exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/settlement/cycles/actual']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/settlement/cycles/{reference}/reconcile']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/settlement/breaks']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/settlement/breaks/{breakId}']")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CycleSummary.properties.reference")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CycleSummary.properties.status")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CycleSummary.properties.expected")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.BreakView.properties.status")
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
