package com.fintech.platform.audit.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.audit.persistence.AuditRecordRepository;
import com.fintech.platform.audit.service.AuditService;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
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
 * {@code requireRead} <em>before</em> touching data. A rule applied after the data has been read is
 * not a rule, so the assertions are not "the call is refused" but "the call is refused and nothing
 * downstream was reached" — {@code verifyNoInteractions} on the repository is the part that would
 * catch a check moved to the bottom of a method.
 *
 * <p>Identities are signed rather than injected as a request attribute, because {@code
 * REQUEST_ATTRIBUTE} is package-private to platform-common-web deliberately — a test that could set
 * it directly would prove the controller's behaviour without proving that the only way to reach the
 * controller is through a verified identity.
 */
@WebMvcTest(AuditController.class)
@AutoConfigureMockMvc
// Imported rather than mocked on purpose. Mocking AuditAuthorization would make this class assert
// that the controller calls a method, not that the real role rules refuse anybody, which is the
// property worth having here.
@Import({WebAutoConfiguration.class, CurrentCaller.class, AuditAuthorization.class})
class AuditSecurityTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final String RECORD_ID = "11111111-1111-4111-8111-111111111111";

    private static final String TRANSACTION_ID = "22222222-2222-4222-8222-222222222222";

    private static final String AUDITOR_SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";

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
    private AuditService trail;

    @MockitoBean
    private AuditRecordRepository records;

    @ParameterizedTest(name = "{0} {1} as a role-less caller is refused without reaching the data")
    @CsvSource({
        "GET,  /api/audit/records",
        "GET,  /api/audit/records/11111111-1111-4111-8111-111111111111",
        "GET,  /api/audit/records/by-resource/fraud-alert/11111111-1111-4111-8111-111111111111",
        "GET,  /api/audit/records/by-transaction/22222222-2222-4222-8222-222222222222",
        "GET,  /api/audit/records/by-correlation/correlation-1",
    })
    @DisplayName("refuses an unprivileged caller on every endpoint, before any data is read")
    void refusesEveryEndpoint(String method, String path) throws Exception {
        mvc.perform(request(method, path).with(signedAs("roleless-subject", List.of())));

        // verifyNoInteractions rather than a status code alone. A handler that loaded the row, then
        // discovered the caller was not allowed and returned 403 would pass a status assertion and
        // still leak the row's existence through its timing and its logs.
        verifyNoInteractions(trail, records);
    }

    @Test
    @DisplayName("refuses a customer, who has no route to the trail by any verb")
    void refusesACustomer() throws Exception {
        mvc.perform(get("/api/audit/records").with(signedAs("customer-subject", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(trail, records);
    }

    @Test
    @DisplayName("refuses a support agent and a fraud analyst, who are supervised rather than supervisors")
    void refusesTheSupervised() throws Exception {
        mvc.perform(get("/api/audit/records").with(signedAs("agent-subject", List.of("SUPPORT_AGENT"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/records").with(signedAs("analyst-subject", List.of("FRAUD_ANALYST"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(trail, records);
    }

    @Test
    @DisplayName("refuses a request carrying no verified identity at all")
    void refusesAnUnverifiedRequest() throws Exception {
        // 401, not 403. The distinction is worth keeping: 403 says "we know who you are and you may
        // not", while 401 says "we do not know who you are" — a different problem with a different
        // fix, and reporting it as a role failure sends an operator to request a role they do not
        // need.
        mvc.perform(get("/api/audit/records")).andExpect(status().isUnauthorized());

        verifyNoInteractions(trail, records);
    }

    @Test
    @DisplayName("lets an auditor read, so the refusal above is a role decision and not a blanket one")
    void letsAnAuditorReachTheData() throws Exception {
        // Without this, a controller that refused everybody would pass every test above. The positive
        // case is what makes the refusals mean something.
        when(records.search(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/audit/records").with(signedAs(AUDITOR_SUBJECT, List.of("AUDITOR"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a write is not a forbidden trail action but an unmapped route")
    void refusesAWritingCaller() throws Exception {
        // There is no POST, PUT, PATCH or DELETE mapping on this controller by design, so a write
        // does not reach authorization at all — it reaches no handler. A 403 here would be the
        // wrong answer twice over: it would claim a rule decided, and it would imply a rule could
        // allow it.
        mvc.perform(post("/api/audit/records").with(signedAs(AUDITOR_SUBJECT, List.of("AUDITOR"))))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(trail, records);
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
        return switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
    }
}
