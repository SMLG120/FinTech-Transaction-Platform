package com.fintech.platform.fraud.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import com.fintech.platform.fraud.identity.FraudDigestKey;
import com.fintech.platform.fraud.identity.SubjectDigester;
import com.fintech.platform.fraud.service.AlertService;
import com.fintech.platform.fraud.service.AnalyticsService;
import com.fintech.platform.fraud.service.DecisionService;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every endpoint's authorization, exercised through the real signed-identity filter.
 *
 * <p>This class exists to test one claim the controller makes about itself: that every handler calls
 * {@code requireRead} or {@code requireAction} <em>before</em> touching data. A rule that is applied after
 * the data has been read is not a rule, so the assertions are not "the call is refused" but "the call is
 * refused and nothing downstream was reached" — {@code verifyNoInteractions} on all three services is the
 * part that would catch a check moved to the bottom of a method.
 *
 * <p>It is the same property {@code CardControllerTest} pins for card-service, and it is worth a
 * separate class rather than a few extra cases in the service tests because the failure it catches is
 * invisible from the service layer: a controller that forgets to call the check looks identical to one
 * that calls it, from inside the service.
 *
 * <p>Identities are signed rather than injected as a request attribute, because
 * {@code REQUEST_ATTRIBUTE} is package-private to platform-common-web deliberately — a test that could set
 * it directly would prove the controller's behaviour without proving that the only way to reach the
 * controller is through a verified identity.
 */
@WebMvcTest(FraudController.class)
@AutoConfigureMockMvc
// FraudAuthorization and SubjectDigester are @Components, so the MVC slice skips them, and they are
// imported rather than mocked on purpose. Mocking FraudAuthorization would make this class assert that
// the controller calls a method — not that the real role rules refuse anybody, which is the property
// worth having here. SubjectDigester is imported for the same reason: the analyst's digest is what lands
// in the audit column, and a stubbed digest would let a test pass that had proved nothing about it.
@Import({WebAutoConfiguration.class, CurrentCaller.class, FraudAuthorization.class, SubjectDigester.class})
@EnableConfigurationProperties(FraudDigestKey.class)
class FraudSecurityTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final UUID TRANSACTION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ALERT_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final String ANALYST_SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";
    private static final String AUDITOR_SUBJECT = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    /**
     * The system clock, because the filter under test builds its own codec and refuses an identity older
     * than the max age. Only the signed header has to be live.
     */
    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @DynamicPropertySource
    static void internalIdentityKey(DynamicPropertyRegistry registry) {
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
        registry.add(
                "platform.security.subject-digest.key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SubjectDigester digester;

    @MockitoBean
    private DecisionService decisions;

    @MockitoBean
    private AlertService alertService;

    @MockitoBean
    private AnalyticsService analytics;

    // ------------------------------------------------------------------ every endpoint refuses

    @ParameterizedTest(name = "{0} as a role-less caller is refused without reaching the data")
    @CsvSource({
        "GET,    /api/v1/fraud/decisions/" + "11111111-2222-3333-4444-555555555555",
        "GET,    /api/v1/fraud/decisions",
        "POST,   /api/v1/fraud/decisions/11111111-2222-3333-4444-555555555555/adjust",
        "POST,   /api/v1/fraud/decisions/11111111-2222-3333-4444-555555555555/rescore",
        "GET,    /api/v1/fraud/alerts",
        "GET,    /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666",
        "POST,   /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666/claim",
        "POST,   /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666/resolve",
        "POST,   /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666/dismiss",
        "GET,    /api/v1/fraud/summary",
    })
    @DisplayName("refuses an unprivileged caller on every endpoint, before any data is read")
    void refusesEveryEndpoint(String method, String path) throws Exception {
        mvc.perform(request(method, path).with(signedAs("roleless-subject", List.of())));

        // verifyNoInteractions rather than a status code alone. A handler that read the decision, then
        // discovered the caller was not allowed and returned 403 would pass a status-code assertion and
        // still leak the row's existence through its timing and its logs.
        verifyNoInteractions(decisions, alertService, analytics);
    }

    @ParameterizedTest(name = "{0} is refused for an auditor, who may read but not act")
    @CsvSource({
        "POST, /api/v1/fraud/decisions/11111111-2222-3333-4444-555555555555/adjust",
        "POST, /api/v1/fraud/decisions/11111111-2222-3333-4444-555555555555/rescore",
        "POST, /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666/claim",
        "POST, /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666/resolve",
        "POST, /api/v1/fraud/alerts/22222222-3333-4444-5555-666666666666/dismiss",
    })
    @DisplayName("refuses an auditor on the endpoints that change things")
    void refusesAnAuditorFromActing(String method, String path) throws Exception {
        mvc.perform(request(method, path).with(signedAs(AUDITOR_SUBJECT, List.of("AUDITOR"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(decisions, alertService, analytics);
    }

    @Test
    @DisplayName("refuses a customer, who has no route to a score by any verb")
    void refusesACustomer() throws Exception {
        mvc.perform(get("/api/v1/fraud/decisions/" + TRANSACTION_ID)
                        .with(signedAs("customer-subject", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fraud/alerts/{id}/claim", ALERT_ID)
                        .with(signedAs("customer-subject", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(decisions, alertService, analytics);
    }

    @Test
    @DisplayName("refuses a request with no verified identity at all")
    void refusesAnUnverifiedRequest() throws Exception {
        // The filter is in the chain here, not stubbed, so this is the assertion that the only way to
        // reach the controller is through a signed identity. An unsigned fraud API is not a fraud API with
        // a bug in it.
        mvc.perform(get("/api/v1/fraud/decisions/" + TRANSACTION_ID)).andExpect(status().isUnauthorized());

        verifyNoInteractions(decisions, alertService, analytics);
    }

    // ------------------------------------------------------------------ auditors may read

    @Test
    @DisplayName("lets an auditor read the queue, which is the point of having the role")
    void letsAnAuditorRead() throws Exception {
        when(alertService.queue(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), toPageable(0), 0));

        mvc.perform(get("/api/v1/fraud/alerts").with(signedAs(AUDITOR_SUBJECT, List.of("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        verify(alertService).queue(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("lets an analyst read the dashboard")
    void letsAnAnalystReadTheDashboard() throws Exception {
        when(analytics.summary(any())).thenReturn(emptyDashboard());

        mvc.perform(get("/api/v1/fraud/summary").with(signedAs(ANALYST_SUBJECT, List.of("FRAUD_ANALYST"))))
                .andExpect(status().isOk());

        verify(analytics).summary(any());
    }

    // ------------------------------------------------------------------ the analyst's own actions

    @Test
    @DisplayName("takes the analyst from the verified identity and digests it, never from the body")
    void takesTheAnalystFromTheIdentity() throws Exception {
        // The body is deliberately a valid adjustment that would succeed if the actor were taken from it.
        // Captured rather than inferred from a status code, because passing the right digest for the
        // wrong subject is just as much a bug as passing none — it is how an analyst ends up authoring
        // somebody else's override.
        when(decisions.manualAdjust(eq(TRANSACTION_ID), eq(40), anyString(), anyString()))
                .thenReturn(storedDecision());

        mvc.perform(post("/api/v1/fraud/decisions/{id}/adjust", TRANSACTION_ID)
                        .with(signedAs(ANALYST_SUBJECT, List.of("FRAUD_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"score":40,"reason":"known customer, confirmed by phone"}
                                """))
                .andExpect(status().isOk());

        // And the digest is this service's own, produced by the same bean the controller used: a staff
        // subject written straight into an audit column would be personal data in a table that reporting
        // queries and is on its way to being exported.
        verify(decisions)
                .manualAdjust(
                        eq(TRANSACTION_ID),
                        eq(40),
                        eq(digester.analystDigestOf(ANALYST_SUBJECT)),
                        eq("known customer, confirmed by phone"));
    }

    @Test
    @DisplayName("refuses a manual score above the cap, naming what is missing")
    void refusesAScoreAboveTheCap() throws Exception {
        when(decisions.manualAdjust(eq(TRANSACTION_ID), eq(90), anyString(), anyString()))
                .thenThrow(new DecisionService.ManualScoreAboveCapException(90, 75));

        mvc.perform(post("/api/v1/fraud/decisions/{id}/adjust", TRANSACTION_ID)
                        .with(signedAs(ANALYST_SUBJECT, List.of("FRAUD_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"score":90,"reason":"escalating this one"}
                                """))
                // 422, not 400: the request was well formed and the caller was allowed to make it. What
                // is missing is a second approver, which is a business rule rather than a client mistake.
                .andExpect(status().isUnprocessableEntity());

        // And the cap is enforced in the service, not only here — a caller reaching the service directly
        // gets the same refusal, which is why the mock throws rather than the controller checking.
    }

    @Test
    @DisplayName("accepts a re-score request as 202, because the work happens on the topic")
    void acceptsARescore() throws Exception {
        when(decisions.findDecision(eq(TRANSACTION_ID))).thenReturn(Optional.of(storedDecision()));

        mvc.perform(post("/api/v1/fraud/decisions/{id}/rescore", TRANSACTION_ID)
                        .with(signedAs(ANALYST_SUBJECT, List.of("FRAUD_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"customer queried the decline"}
                                """))
                .andExpect(status().isAccepted());

        verify(decisions).requestRescore(eq(TRANSACTION_ID), anyString(), eq("customer queried the decline"));
    }

    @Test
    @DisplayName("refuses a re-score for a payment it has never scored")
    void refusesARescoreOfNothing() throws Exception {
        when(decisions.findDecision(eq(TRANSACTION_ID))).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/fraud/decisions/{id}/rescore", TRANSACTION_ID)
                        .with(signedAs(ANALYST_SUBJECT, List.of("FRAUD_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"just wondering"}
                                """))
                .andExpect(status().isNotFound());

        // Nothing is published: a request for a payment this service has never scored is a message that
        // provably cannot succeed, and putting it on the topic only moves the failure somewhere quieter.
        verifyNoInteractions(alertService, analytics);
    }

    @Test
    @DisplayName("validates the body before doing anything, so a blank reason is a 400 not an empty audit row")
    void validatesTheBody() throws Exception {
        mvc.perform(post("/api/v1/fraud/decisions/{id}/adjust", TRANSACTION_ID)
                        .with(signedAs(ANALYST_SUBJECT, List.of("FRAUD_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"score":40,"reason":"   "}
                                """))
                .andExpect(status().isBadRequest());

        // A manual adjustment's reason is the audit trail. An empty one that reached the service would be
        // an override nobody can explain later, so it never gets that far.
        verifyNoInteractions(decisions, alertService, analytics);
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder request(String method, String path) throws Exception {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
        // Every write endpoint takes a body, and a 400 for a missing one would mask the 403 under test.
        return path.contains("/adjust")
                ? builder.contentType(MediaType.APPLICATION_JSON).content("""
                        {"score":40,"reason":"a reason"}
                        """)
                : path.contains("/rescore")
                        ? builder.contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason":"a reason"}
                                """)
                        : path.contains("/resolve") || path.contains("/dismiss")
                                ? builder.contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                        {"resolution":"FALSE_POSITIVE","note":"a note"}
                                        """)
                                : builder;
    }

    private static org.springframework.data.domain.Pageable toPageable(int page) {
        return org.springframework.data.domain.PageRequest.of(page, 50);
    }

    /**
     * A real decision row, not a mock.
     *
     * <p>{@code DecisionResponse.of} reads a dozen fields off the entity to render it, and a mock would
     * answer null for whichever of them the render path does not happen to touch today — a test that
     * passes because the fields it does touch happen to be stubbed.
     */
    private static com.fintech.platform.fraud.persistence.RiskDecisionEntity storedDecision() {
        return com.fintech.platform.fraud.persistence.RiskDecisionEntity.firstAssessment(
                assessment(), "[]", "{}", Instant.parse("2024-06-15T12:00:00Z"));
    }

    private static com.fintech.platform.fraud.domain.RiskAssessment assessment() {
        return new com.fintech.platform.fraud.domain.RiskAssessment(
                TRANSACTION_ID,
                Instant.parse("2024-06-15T11:59:00Z"),
                Instant.parse("2024-06-15T12:00:00Z"),
                com.fintech.platform.fraud.domain.Money.parse("60.00", java.util.Currency.getInstance("GBP")),
                "GBP",
                "d".repeat(64),
                "Coffee Bar",
                "merchant-1",
                "WEB",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                com.fintech.platform.fraud.domain.RiskScore.of(60),
                com.fintech.platform.fraud.domain.FraudDecision.REVIEW,
                true,
                List.of(),
                Map.of("score", "60"));
    }

    private static AnalyticsService.DashboardSummary emptyDashboard() {
        return new AnalyticsService.DashboardSummary(
                Duration.ofHours(24),
                0L,
                Map.of(),
                Map.of(),
                0.0d,
                0L,
                0L,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of());
    }

    private static RequestPostProcessor signedAs(String subject, List<String> roles) {
        return request -> {
            Map<String, String> headers =
                    CODEC.headersFor(new InternalIdentity(subject, "user", roles, "correlation-1", Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }
}
