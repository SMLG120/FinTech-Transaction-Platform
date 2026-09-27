package com.fintech.platform.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.correlation.CorrelationId;
import com.fintech.platform.common.error.CommonErrorCodes;
import com.fintech.platform.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * These tests exist to lock down the platform's disclosure boundary, not just to raise coverage:
 * each one asserts that a specific class of information does <em>not</em> reach the client.
 */
class ApiExceptionHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    record CreateCardRequest(
            @NotBlank String cardholderName, @Positive Long creditLimit) {}

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/boom")
        void unhandled() {
            throw new IllegalStateException("connection to db-primary.internal:5432 failed, user=fintech_app");
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("missing required authority ROLE_FRAUD_ANALYST");
        }

        @GetMapping("/api-exception")
        void apiException() {
            throw CommonErrorCodes.NOT_FOUND.exception("Card TXN-1 not found");
        }

        @GetMapping("/with-details")
        void withDetails() {
            throw CommonErrorCodes.CONFLICT.exception("Card already locked", Map.of("cardId", "CARD-1"));
        }

        @PostMapping("/cards")
        void create(@Valid @RequestBody CreateCardRequest request) {
            throw new IllegalStateException("unreachable");
        }
    }

    @Test
    @DisplayName("an unexpected exception returns a generic message and never the internal one")
    void doesNotLeakInternalDetail() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db-primary.internal"))))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fintech_app"))))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("IllegalStateException"))));
    }

    @Test
    @DisplayName("the error body carries the mandated fields and nothing that could identify internals")
    void errorBodyShape() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/probe/boom"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("an unhandled failure still returns a correlation id so support can find the log entry")
    void unhandledFailureIsCorrelatable() throws Exception {
        String body = mockMvc.perform(get("/probe/boom").header(CorrelationId.HEADER, "support-quote-999"))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(MAPPER.readTree(body).get("correlationId").asText()).isEqualTo("support-quote-999");
    }

    @Test
    @DisplayName("access denied does not disclose which role was required")
    void doesNotLeakRequiredRole() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("FRAUD_ANALYST"))));
    }

    @Test
    @DisplayName("a declared ApiException keeps its own code, message and status")
    void mapsApiException() throws Exception {
        mockMvc.perform(get("/probe/api-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Card TXN-1 not found"));
    }

    @Test
    @DisplayName("structured details are returned when the service chooses to publish them")
    void mapsDetails() throws Exception {
        mockMvc.perform(get("/probe/with-details"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.cardId").value("CARD-1"));
    }

    @Test
    @DisplayName("bean validation failures list the offending fields")
    void mapsValidationFailures() throws Exception {
        mockMvc.perform(post("/probe/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\":\"\",\"creditLimit\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field")
                        .value(org.hamcrest.Matchers.hasItems("cardholderName", "creditLimit")));
    }

    @Test
    @DisplayName("a rejected value is never echoed back; in a payments API the body holds PANs and tokens")
    void doesNotEchoRejectedValues() throws Exception {
        String body = mockMvc.perform(post("/probe/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\":\"SUPER_SECRET_NAME\",\"creditLimit\":-5}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("SUPER_SECRET_NAME");
        assertThat(body).doesNotContain("rejectedValue");
    }

    @Test
    @DisplayName("an unparseable body is reported as malformed without quoting the submitted document")
    void doesNotEchoMalformedBody() throws Exception {
        String body = mockMvc.perform(post("/probe/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"4111111111111111\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("4111111111111111");
    }

    @Test
    @DisplayName("error codes are always SCREAMING_SNAKE_CASE so clients can rely on the vocabulary")
    void errorCodesAreMachineReadable() throws Exception {
        String body =
                mockMvc.perform(get("/probe/boom")).andReturn().getResponse().getContentAsString();

        assertThat(MAPPER.readTree(body).get("error").asText()).matches("^[A-Z][A-Z0-9_]*$");
    }

    @Test
    @DisplayName("every shared code maps to a sensible HTTP status")
    void httpStatusesAreCoherent() {
        for (ErrorCode code : CommonErrorCodes.all()) {
            assertThat(code.status().is4xxClientError() || code.status().is5xxServerError())
                    .as("%s -> %s", code.code(), code.status())
                    .isTrue();
            assertThat(HttpStatus.resolve(code.httpStatus()))
                    .as("%s is a real HTTP status", code.code())
                    .isNotNull();
        }
    }
}
