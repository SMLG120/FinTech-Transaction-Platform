package com.fintech.platform.notification.web;

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
 * The published HTTP contract of notification-service, read off the served OpenAPI document.
 *
 * <p>Same pattern as the other services' contract tests. The pinned paths are the two reads
 * and the retry the dashboard calls; the pinned fields are what the delivery log renders.
 * No recipient appears anywhere in this document — the digest stays inside by design.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiContractTest {

    private static final String IDENTITY_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_notifications_contract");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.security.internal-identity.signing-key", () -> IDENTITY_KEY);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("app.notification.retry-enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InternalIdentityCodec codec;

    @Test
    @DisplayName("serves an OpenAPI document pinning the delivery paths and required fields")
    void servesContractToVerifiedCaller() throws Exception {
        docsAs("SUPPORT_AGENT")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi", startsWith("3.")))
                .andExpect(jsonPath("$.paths['/api/v1/notifications']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications/by-transaction/{transactionId}']")
                        .exists())
                .andExpect(
                        jsonPath("$.paths['/api/v1/notifications/{id}/retry']").exists())
                .andExpect(jsonPath("$.components.schemas.NotificationView.properties.subject")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.NotificationView.properties.status")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.NotificationView.properties.attempts")
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
