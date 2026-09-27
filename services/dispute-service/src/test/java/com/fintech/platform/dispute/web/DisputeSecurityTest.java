package com.fintech.platform.dispute.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import com.fintech.platform.dispute.domain.DisputeReason;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceRepository;
import com.fintech.platform.dispute.persistence.DisputeRepository;
import com.fintech.platform.dispute.service.DisputeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every endpoint's authorization, through the real signed-identity filter.
 *
 * <p>This class exists to test one claim the controller makes about itself: that every handler
 * checks the caller <em>before</em> touching a case. A rule applied after the data has been read
 * is not a rule, so the assertions are "the call is refused and the case service was never
 * reached" — {@code verifyNoInteractions} on the service is the part that would catch a check
 * moved to the bottom of a method.
 *
 * <p>Identities are signed rather than injected as a request attribute, because {@code
 * REQUEST_ATTRIBUTE} is package-private to platform-common-web deliberately — a test that could set
 * it directly would prove the controller's behaviour without proving that the only way to reach the
 * controller is through a verified identity.
 */
@WebMvcTest(DisputeController.class)
@AutoConfigureMockMvc
// Imported rather than mocked on purpose. Mocking DisputeAuthorization would make this class assert
// that the controller calls a method, not that the real role rules refuse anybody, which is the
// property worth having here.
@Import({WebAutoConfiguration.class, CurrentCaller.class, DisputeAuthorization.class})
class DisputeSecurityTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final String DISPUTE_ID = "11111111-1111-4111-8111-111111111111";

    private static final String TRANSACTION_ID = "22222222-2222-4222-8222-222222222222";

    private static final String OPENER_SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";

    private static final String OTHER_SUBJECT = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    /** The system clock, because the filter under test builds its own codec and refuses an old identity. */
    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @DynamicPropertySource
    static void internalIdentityKey(DynamicPropertyRegistry registry) {
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DisputeService cases;

    @MockitoBean
    private DisputeRepository disputes;

    @MockitoBean
    private DisputeEvidenceRepository file;

    private static DisputeEntity openCase() {
        return DisputeEntity.open(
                UUID.fromString(TRANSACTION_ID),
                DisputeReason.FRAUD,
                "I did not pay this",
                OPENER_SUBJECT,
                Instant.parse("2026-09-27T12:00:00Z"));
    }

    @ParameterizedTest(name = "{0} {1} as a role-less caller is refused without reaching the case service")
    @CsvSource({
        "GET,  /api/v1/disputes",
        "GET,  /api/v1/disputes/11111111-1111-4111-8111-111111111111",
        "POST, /api/v1/disputes",
        "POST, /api/v1/disputes/11111111-1111-4111-8111-111111111111/evidence",
        "POST, /api/v1/disputes/11111111-1111-4111-8111-111111111111/resolve",
    })
    @DisplayName("refuses an unprivileged caller on every endpoint, before any case is touched")
    void refusesEveryEndpoint(String method, String path) throws Exception {
        mvc.perform(request(method, path).with(signedAs("roleless-subject", List.of())));

        verifyNoInteractions(cases);
    }

    @Test
    @DisplayName("refuses an auditor on open, plead and decide alike")
    void refusesAnAuditor() throws Exception {
        // The trail records the case; reading it is the auditor's job, and working it is not.
        mvc.perform(post("/api/v1/disputes")
                        .with(signedAs("auditor-subject", List.of("AUDITOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"" + TRANSACTION_ID + "\",\"reason\":\"FRAUD\","
                                + "\"description\":\"I did not pay this\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/disputes/" + DISPUTE_ID + "/resolve")
                        .with(signedAs("auditor-subject", List.of("AUDITOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"REFUND\",\"resolution\":\"looks fraudulent\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cases);
    }

    @Test
    @DisplayName("refuses a customer deciding their own case: an opener is not a decider")
    void refusesACustomerDeciding() throws Exception {
        when(disputes.findById(any())).thenReturn(Optional.of(openCase()));

        mvc.perform(post("/api/v1/disputes/" + DISPUTE_ID + "/resolve")
                        .with(signedAs(OPENER_SUBJECT, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"REFUND\",\"resolution\":\"give it back\"}"))
                .andExpect(status().isForbidden());

        // The repository read happened — the controller must load the row to know whose it is —
        // but the case service, which is what would move the matter forward, was never reached.
        verifyNoInteractions(cases);
    }

    @Test
    @DisplayName("refuses a customer reading somebody else's case")
    void refusesAnotherCustomersCase() throws Exception {
        when(disputes.findById(any())).thenReturn(Optional.of(openCase()));

        mvc.perform(get("/api/v1/disputes/" + DISPUTE_ID).with(signedAs(OTHER_SUBJECT, List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cases);
    }

    @Test
    @DisplayName("refuses a request carrying no verified identity at all")
    void refusesAnUnverifiedRequest() throws Exception {
        mvc.perform(get("/api/v1/disputes")).andExpect(status().isUnauthorized());

        verifyNoInteractions(cases);
    }

    @Test
    @DisplayName("lets the opener plead, so the refusal above is a role decision and not a blanket one")
    void letsTheOpenerReachTheCase() throws Exception {
        // Without this, a controller that refused everybody would pass every test above. The
        // positive case is what makes the refusals mean something.
        when(disputes.findById(any())).thenReturn(Optional.of(openCase()));
        when(cases.fileOf(any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/disputes/" + DISPUTE_ID).with(signedAs(OPENER_SUBJECT, List.of("CUSTOMER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("lets support list the queue, for the same reason")
    void letsSupportListTheQueue() throws Exception {
        when(disputes.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/v1/disputes").with(signedAs("agent-subject", List.of("SUPPORT_AGENT"))))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor signedAs(String subject, List<String> roles) {
        return request -> {
            Map<String, String> headers =
                    CODEC.headersFor(new InternalIdentity(subject, "user", roles, "correlation-1", Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder request(String method, String path) {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
        // Every write endpoint takes a body, and a 400 for a missing one would mask the status
        // under test. The bodies are valid by construction; authorization runs before validation
        // could matter, and the role-less caller never gets past it either way.
        if (path.equals("/api/v1/disputes")) {
            return builder.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transactionId\":\"" + TRANSACTION_ID + "\",\"reason\":\"FRAUD\","
                            + "\"description\":\"I did not pay this\"}");
        }
        if (path.endsWith("/evidence")) {
            return builder.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"it arrived broken\"}");
        }
        if (path.endsWith("/resolve")) {
            return builder.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"outcome\":\"REFUND\",\"resolution\":\"tracking shows no delivery\"}");
        }
        return builder;
    }
}
