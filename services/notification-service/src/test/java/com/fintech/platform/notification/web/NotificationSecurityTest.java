package com.fintech.platform.notification.web;

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
import com.fintech.platform.notification.persistence.NotificationRepository;
import com.fintech.platform.notification.service.NotificationService;
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
 * {@code requireRead} or {@code requireRetry} <em>before</em> touching data. A rule applied after the
 * data has been read is not a rule, so the assertions are not "the call is refused" but "the call is
 * refused and nothing downstream was reached" — {@code verifyNoInteractions} on the service and the
 * repository is the part that would catch a check moved to the bottom of a method.
 *
 * <p>It is the same property {@code SettlementSecurityTest} pins for settlement-service. Here it
 * matters because the delivery log's rows carry a join key into another service's pseudonyms: a
 * handler that read a notification and then discovered the caller was unprivileged would leak that
 * key through its timing and its logs to somebody who has no business seeing it.
 *
 * <p>Identities are signed rather than injected as a request attribute, because {@code
 * REQUEST_ATTRIBUTE} is package-private to platform-common-web deliberately — a test that could set
 * it directly would prove the controller's behaviour without proving that the only way to reach the
 * controller is through a verified identity.
 */
@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc
// Imported rather than mocked on purpose. Mocking NotificationAuthorization would make this class
// assert that the controller calls a method, not that the real role rules refuse anybody, which is
// the property worth having here.
@Import({WebAutoConfiguration.class, CurrentCaller.class, NotificationAuthorization.class})
class NotificationSecurityTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final String NOTIFICATION_ID = "11111111-1111-4111-8111-111111111111";

    private static final String TRANSACTION_ID = "22222222-2222-4222-8222-222222222222";

    private static final String SUPPORT_SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";

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
    private NotificationService notifications;

    @MockitoBean
    private NotificationRepository repository;

    @ParameterizedTest(name = "{0} {1} as a role-less caller is refused without reaching the data")
    @CsvSource({
        "GET,  /api/v1/notifications",
        "GET,  /api/v1/notifications/11111111-1111-4111-8111-111111111111",
        "GET,  /api/v1/notifications/by-transaction/22222222-2222-4222-8222-222222222222",
        "POST, /api/v1/notifications/11111111-1111-4111-8111-111111111111/retry",
    })
    @DisplayName("refuses an unprivileged caller on every endpoint, before any data is read")
    void refusesEveryEndpoint(String method, String path) throws Exception {
        mvc.perform(request(method, path).with(signedAs("roleless-subject", List.of())));

        // verifyNoInteractions rather than a status code alone. A handler that loaded the row, then
        // discovered the caller was not allowed and returned 403 would pass a status assertion and
        // still leak the row's existence through its timing and its logs.
        verifyNoInteractions(notifications, repository);
    }

    @Test
    @DisplayName("refuses a customer, who has no route to the delivery log by any verb")
    void refusesACustomer() throws Exception {
        // A 200 with an empty page is the failure worth naming: it is indistinguishable from "nobody
        // was notified", so a misconfigured role looks exactly like a working system with no data.
        mvc.perform(get("/api/v1/notifications").with(signedAs("customer-subject", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/" + NOTIFICATION_ID + "/retry")
                        .with(signedAs("customer-subject", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notifications, repository);
    }

    @Test
    @DisplayName("refuses an auditor, who supervises other queues but does not work this one")
    void refusesAnAuditor() throws Exception {
        mvc.perform(get("/api/v1/notifications").with(signedAs("auditor-subject", List.of("AUDITOR"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/" + NOTIFICATION_ID + "/retry")
                        .with(signedAs("auditor-subject", List.of("AUDITOR"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notifications, repository);
    }

    @Test
    @DisplayName("refuses a request carrying no verified identity at all")
    void refusesAnUnverifiedRequest() throws Exception {
        // 401, not 403. The distinction is worth keeping: 403 says "we know who you are and you may
        // not", which is the answer to a role question, while 401 says "we do not know who you are" —
        // a different problem with a different fix, and reporting it as a role failure sends an
        // operator to request a role they do not need.
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());

        verifyNoInteractions(notifications, repository);
    }

    @Test
    @DisplayName("lets a support agent read, so the refusal above is a role decision and not a blanket one")
    void letsSupportReachTheData() throws Exception {
        // Without this, a controller that refused everybody would pass every test above. The positive
        // case is what makes the refusals mean something.
        when(repository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/v1/notifications").with(signedAs(SUPPORT_SUBJECT, List.of("SUPPORT_AGENT"))))
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
        return switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
    }
}
