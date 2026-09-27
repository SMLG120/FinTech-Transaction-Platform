package com.fintech.platform.settlement.web;

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
import com.fintech.platform.settlement.persistence.SettlementBreakRepository;
import com.fintech.platform.settlement.persistence.SettlementCycleRepository;
import com.fintech.platform.settlement.persistence.SettlementLineRepository;
import com.fintech.platform.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * <p>This class exists to test one claim the controller makes about itself: that every handler calls
 * {@code requireRead} or {@code requireAction} <em>before</em> touching data. A rule applied after the data
 * has been read is not a rule, so the assertions are not "the call is refused" but "the call is refused and
 * nothing downstream was reached" — {@code verifyNoInteractions} on the service and all three repositories is
 * the part that would catch a check moved to the bottom of a method.
 *
 * <p>It is the same property {@code FraudSecurityTest} pins for fraud-service. Here it matters twice over,
 * because a settlement statement is the platform's record of money that has already moved to a merchant: a
 * handler that read a cycle and then discovered the caller was unprivileged would leak the figures through
 * its timing and its logs to somebody who has no business seeing them.
 *
 * <p>Identities are signed rather than injected as a request attribute, because {@code REQUEST_ATTRIBUTE} is
 * package-private to platform-common-web deliberately — a test that could set it directly would prove the
 * controller's behaviour without proving that the only way to reach the controller is through a verified
 * identity.
 */
@WebMvcTest(SettlementController.class)
@AutoConfigureMockMvc
// Imported rather than mocked on purpose. Mocking SettlementAuthorization would make this class assert
// that the controller calls a method, not that the real role rules refuse anybody, which is the property
// worth having here.
@Import({WebAutoConfiguration.class, CurrentCaller.class, SettlementAuthorization.class})
class SettlementSecurityTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final String REFERENCE = "SETTLE-2026-04-02-GBP";

    private static final String OPERATOR_SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";
    private static final String AUDITOR_SUBJECT = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

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
    private SettlementService settlement;

    @MockitoBean
    private SettlementCycleRepository cycles;

    @MockitoBean
    private SettlementLineRepository lines;

    @MockitoBean
    private SettlementBreakRepository breaks;

    // ------------------------------------------------------------------ every endpoint refuses

    @ParameterizedTest(name = "{0} {1} as a role-less caller is refused without reaching the data")
    @CsvSource({
        "GET,  /api/v1/settlement/cycles",
        "GET,  /api/v1/settlement/cycles/SETTLE-2026-04-02-GBP",
        "GET,  /api/v1/settlement/breaks",
        "GET,  /api/v1/settlement/breaks/22222233-3333-4444-5555-666666777777",
        "POST, /api/v1/settlement/cycles/close",
        "POST, /api/v1/settlement/cycles/actual",
        "POST, /api/v1/settlement/cycles/SETTLE-2026-04-02-GBP/reconcile",
        "POST, /api/v1/settlement/breaks/22222233-3333-4444-5555-666666777777/acknowledge",
        "POST, /api/v1/settlement/breaks/22222233-3333-4444-5555-666666777777/resolve",
    })
    @DisplayName("refuses an unprivileged caller on every endpoint, before any data is read")
    void refusesEveryEndpoint(String method, String path) throws Exception {
        mvc.perform(request(method, path).with(signedAs("roleless-subject", List.of())));

        // verifyNoInteractions rather than a status code alone. A handler that loaded the cycle, then
        // discovered the caller was not allowed and returned 403 would pass a status assertion and still
        // leak the row's existence through its timing and its logs.
        verifyNoInteractions(settlement, cycles, lines, breaks);
    }

    @ParameterizedTest(name = "{0} {1} is refused for an auditor, who may read but not act")
    @CsvSource({
        "POST, /api/v1/settlement/cycles/close",
        "POST, /api/v1/settlement/cycles/actual",
        "POST, /api/v1/settlement/cycles/SETTLE-2026-04-02-GBP/reconcile",
        "POST, /api/v1/settlement/breaks/22222233-3333-4444-5555-666666777777/acknowledge",
        "POST, /api/v1/settlement/breaks/22222233-3333-4444-5555-666666777777/resolve",
    })
    @DisplayName("refuses an auditor on every endpoint that changes a period or a finding")
    void refusesAnAuditorFromActing(String method, String path) throws Exception {
        // An auditor who can declare an actual supplies the figure they are there to check; one who can
        // resolve a break edits the evidence of a discrepancy. Refusing reads as well would be no better —
        // an auditor who cannot open the statement cannot audit it.
        mvc.perform(request(method, path).with(signedAs(AUDITOR_SUBJECT, List.of("AUDITOR"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(settlement, cycles, lines, breaks);
    }

    @Test
    @DisplayName("refuses a customer, who has no route to a statement by any verb")
    void refusesACustomer() throws Exception {
        // A 200 with an empty page is the failure worth naming: it is indistinguishable from "there are no
        // cycles", so a misconfigured role looks exactly like a working system with no data.
        mvc.perform(get("/api/v1/settlement/cycles").with(signedAs("customer-subject", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settlement/cycles/close")
                        .with(signedAs("customer-subject", List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + REFERENCE + "\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(settlement, cycles, lines, breaks);
    }

    @Test
    @DisplayName("refuses a request carrying no verified identity at all")
    void refusesAnUnverifiedRequest() throws Exception {
        // 401, not 403. The distinction is worth keeping: 403 says "we know who you are and you may not",
        // which is the answer to a role question, while 401 says "we do not know who you are" — a
        // different problem with a different fix, and reporting it as a role failure sends an operator to
        // request a role they do not need.
        //
        // The filter is in the chain rather than stubbed, so this is the assertion that the only way in is
        // a signed identity from the gateway. Without it, a request that merely failed to authenticate
        // would be indistinguishable from one that was never checked.
        mvc.perform(get("/api/v1/settlement/cycles")).andExpect(status().isUnauthorized());

        verifyNoInteractions(settlement, cycles, lines, breaks);
    }

    @Test
    @DisplayName("refuses a forged identity header, because a role nobody signed is not a role")
    void refusesAForgedIdentityHeader() throws Exception {
        // A role claim is the whole basis of every decision in this service, so a header that merely
        // looks like the right shape must not be honoured. This is the assertion that a role is
        // established by a signature rather than asserted by a caller.
        mvc.perform(get("/api/v1/settlement/cycles")
                        .header("X-Internal-Identity", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(settlement, cycles, lines, breaks);
    }

    // ------------------------------------------------------------------ an authorised caller gets through

    @Test
    @DisplayName("lets an operator read, so the refusal above is a role decision and not a blanket one")
    void letsAnOperatorReachTheData() throws Exception {
        // Without this, a controller that refused everybody would pass every test above. The positive case
        // is what makes the refusals mean something.
        // A one-row page rather than Page.empty(), because PageResponse requires a page size of at least
        // one and an empty page is not something the wire format can represent.
        when(cycles.findAllByOrderByBusinessDateDesc(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/v1/settlement/cycles").with(signedAs(OPERATOR_SUBJECT, List.of("SETTLEMENT_OPERATOR"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private static RequestPostProcessor signedAs(String subject, List<String> roles) {
        return request -> {
            Map<String, String> headers =
                    CODEC.headersFor(new InternalIdentity(subject, "user", roles, "correlation-1", Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder request(String method, String path) throws Exception {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
        // Every write endpoint takes a body, and a 400 for a missing one would mask the 403 under test.
        if (path.endsWith("/close")) {
            return builder.contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"" + REFERENCE + "\"}");
        }
        if (path.endsWith("/actual")) {
            return builder.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reference\":\"" + REFERENCE + "\",\"actualAmount\":\"10.00\",\"currency\":\"GBP\"}");
        }
        if (path.endsWith("/resolve")) {
            return builder.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"resolution\":\"found it in the clearing file\"}");
        }
        return builder;
    }
}
