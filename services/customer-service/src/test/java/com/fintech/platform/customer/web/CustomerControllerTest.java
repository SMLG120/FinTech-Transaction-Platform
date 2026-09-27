package com.fintech.platform.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import com.fintech.platform.customer.error.CustomerErrorCodes;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.service.CustomerProfileView;
import com.fintech.platform.customer.service.CustomerService;
import com.fintech.platform.customer.service.KycService;
import com.fintech.platform.customer.service.KycWorkflow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The HTTP contract, exercised through the real signed-identity filter.
 *
 * <p>The services are mocked, so what is under test is the controller's part of the job: binding a
 * request, taking the caller from the verified identity rather than the body, and rendering exactly
 * what the service decided. The three mistakes this class exists to catch are the ones that only
 * appear at this layer.
 *
 * <ol>
 *   <li>A response that leaks unmasked personal data to a support caller, because the controller
 *       second-guessed the service and "helpfully" rendered the decrypted value.
 *   <li>A request shape that accepts a subject or a customer id, which would let a caller act as
 *       somebody else no matter what the service checks.
 *   <li>An endpoint that forgets to pass the identity to the service. That is the failure the
 *       service-side tests cannot see, so it is asserted here with {@code verify}.
 * </ol>
 *
 * <p>Identities are signed here rather than injected as a request attribute, because
 * {@code REQUEST_ATTRIBUTE} is package-private to platform-common-web on purpose: a test that could
 * set it directly would be able to assert the controller's behaviour without proving that the only way
 * to reach the controller is through a verified identity.
 */
@WebMvcTest(CustomerController.class)
@AutoConfigureMockMvc
// @WebMvcTest's type-exclude filter drops WebAutoConfiguration, because it is a component-scanning
// configuration class rather than a web bean. Importing it puts the real InternalIdentityFilter and
// the real ApiExceptionHandler on the chain, so these tests see the same request pipeline the
// deployed service does. CurrentCaller is imported alongside it for the same reason: a mocked one
// would let the controller read an identity from somewhere these tests never controlled.
@Import({WebAutoConfiguration.class, CurrentCaller.class})
class CustomerControllerTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final String OWNER = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";
    private static final String STRANGER = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    /**
     * The signing key, and the clock, used to produce request headers.
     *
     * <p>The clock here is deliberately the system clock rather than a fixed one. The filter under
     * test builds its own codec with {@code Clock.systemUTC()} and refuses an identity older than the
     * max age, so a fixed timestamp is a signature that has already expired by the time the test
     * runs. Timestamps in assertions are still fixed; only the signed header has to be live.
     */
    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @DynamicPropertySource
    static void internalIdentityKey(DynamicPropertyRegistry registry) {
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CustomerService customerService;

    @MockitoBean
    private KycService kycService;

    // ---------------------------------------------------------------- registration

    @Test
    @DisplayName("registration takes the subject from the token and never from the request body")
    void registrationIgnoresAnySubjectInTheBody() throws Exception {
        when(customerService.register(any(), any(), any())).thenReturn(profile(false));

        // A subject smuggled into the body has to be dropped, not honoured. If it were honoured the
        // caller could register a profile under somebody else's subject, and no amount of checking
        // further down would notice.
        String body = """
                {
                  "fullName": "Dana Okonkwo",
                  "dateOfBirth": "1991-04-17",
                  "nationality": "GB",
                  "email": "dana@example.test",
                  "phone": "+447700900123",
                  "subject": "%s",
                  "address": {
                    "line1": "12 Alder Way",
                    "city": "Manchester",
                    "postalCode": "M1 4BT",
                    "country": "GB"
                  }
                }
                """.formatted(STRANGER);

        mvc.perform(post("/api/v1/customers")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/customers/" + CUSTOMER_ID))
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.email").value("dana@example.test"))
                .andExpect(jsonPath("$.masked").value(false));

        ArgumentCaptor<InternalIdentity> captor = ArgumentCaptor.forClass(InternalIdentity.class);
        verify(customerService).register(captor.capture(), any(), eq("+447700900123"));
        assertThat(captor.getValue().subject()).isEqualTo(OWNER);
        assertThat(captor.getValue().roles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("the response carries no subject field, so there is nothing to leak")
    void responseHasNoSubjectField() throws Exception {
        when(customerService.register(any(), any(), any())).thenReturn(profile(false));

        String body = mvc.perform(post("/api/v1/customers")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain(OWNER)
                .doesNotContain("subject")
                .doesNotContain("subjectDigest");
    }

    @Test
    @DisplayName("an invalid profile is rejected before any service call")
    void rejectsInvalidProfile() throws Exception {
        mvc.perform(post("/api/v1/customers")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "",
                                  "dateOfBirth": "2099-01-01",
                                  "nationality": "GB",
                                  "email": "not-an-email",
                                  "address": {
                                    "line1": "",
                                    "city": "Manchester",
                                    "postalCode": "M1 4BT",
                                    "country": "GBR"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(customerService);
    }

    // ---------------------------------------------------------------- masking

    @Test
    @DisplayName("a support caller gets masked personal data from the by-id route")
    void staffReadIsMasked() throws Exception {
        // The service has already decided to mask. The controller's job is to honour that, not to
        // decide it, and certainly not to "helpfully" show the support agent the real values.
        when(customerService.getProfile(any(), eq(CUSTOMER_ID))).thenReturn(profile(true));

        mvc.perform(get("/api/v1/customers/{id}", CUSTOMER_ID).with(signedAs(STRANGER, List.of("SUPPORT_AGENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(true))
                .andExpect(jsonPath("$.fullName").value("D*** O******"))
                .andExpect(jsonPath("$.email").value("d***@example.test"))
                .andExpect(jsonPath("$.phone").value("**********23"))
                .andExpect(jsonPath("$.dateOfBirth").doesNotExist())
                .andExpect(jsonPath("$.birthYear").value("1991"))
                .andExpect(jsonPath("$.address.country").value("GB"));
    }

    @Test
    @DisplayName("the owner reading their own profile is unmasked")
    void ownerReadIsNotMasked() throws Exception {
        when(customerService.getOwnProfile(any())).thenReturn(profile(false));

        mvc.perform(get("/api/v1/customers/me").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(false))
                .andExpect(jsonPath("$.fullName").value("Dana Okonkwo"))
                .andExpect(jsonPath("$.dateOfBirth").value("1991-04-17"))
                .andExpect(jsonPath("$.birthYear").doesNotExist());
    }

    @Test
    @DisplayName("a caller who is not the owner gets 403, and the body leaks no personal data")
    void nonOwnerReadIsForbidden() throws Exception {
        when(customerService.getProfile(any(), eq(CUSTOMER_ID)))
                .thenThrow(CustomerErrorCodes.NOT_THE_OWNER.exception());

        mvc.perform(get("/api/v1/customers/{id}", CUSTOMER_ID).with(signedAs(STRANGER, List.of("SUPPORT_AGENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_THE_OWNER"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.fullName").doesNotExist());
    }

    @Test
    @DisplayName("an erased profile answers 410, so a deletion right can be confirmed")
    void erasedProfileIsGone() throws Exception {
        when(customerService.getOwnProfile(any())).thenThrow(CustomerErrorCodes.CUSTOMER_NOT_ERASED.exception());

        mvc.perform(get("/api/v1/customers/me").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("CUSTOMER_NOT_ERASED"));
    }

    // ---------------------------------------------------------------- identity

    @Test
    @DisplayName("a request with no verified identity never reaches the controller")
    void unsignedRequestIsRejected() throws Exception {
        // No signed headers at all. The filter has to stop this, because CurrentCaller would
        // otherwise throw and surface as a 500, which tells the caller "we broke" rather than
        // "authenticate", and invites a retry loop against a service that will never trust them.
        mvc.perform(get("/api/v1/customers/me")).andExpect(status().isUnauthorized());

        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("a forged signature is rejected, and no service call happens")
    void forgedSignatureIsRejected() throws Exception {
        RequestPostProcessor forged = signedAs(OWNER, List.of("PLATFORM_ADMIN"));
        // The roles are the whole point of the signature. Re-signing the subject but asking for an
        // admin role, with the original signature left in place, is the forgery this must refuse.
        mvc.perform(get("/api/v1/customers/{id}", CUSTOMER_ID)
                        .with(forged)
                        .header("X-Internal-Identity-Roles", "PLATFORM_ADMIN")
                        .header("X-Internal-Identity-Signature", "00".repeat(32)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(customerService);
    }

    // ---------------------------------------------------------------- KYC

    @Test
    @DisplayName("a KYC submission reports the provider's outcome, not a bare status")
    void kycSubmissionReportsOutcome() throws Exception {
        when(kycService.submit(any(), eq(CUSTOMER_ID), any(), any()))
                .thenReturn(new KycWorkflow.KycDecision(UUID.randomUUID(), KycStatus.UNDER_REVIEW, List.of()));

        mvc.perform(post("/api/v1/customers/{id}/kyc", CUSTOMER_ID)
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentReference": "DOC-001",
                                  "printedName": "Dana Okonkwo",
                                  "expiryDate": "2031-04-17",
                                  "issuingCountry": "GB",
                                  "nationality": "GB"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.message").value("identity check is being reviewed by a compliance officer"));
    }

    @Test
    @DisplayName("an expired document is refused before the provider is called")
    void expiredDocumentIsRejected() throws Exception {
        mvc.perform(post("/api/v1/customers/{id}/kyc", CUSTOMER_ID)
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentReference": "DOC-001",
                                  "printedName": "Dana Okonkwo",
                                  "expiryDate": "2020-01-01",
                                  "issuingCountry": "GB",
                                  "nationality": "GB"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expiryDate"));

        verifyNoInteractions(kycService);
    }

    @Test
    @DisplayName("an unavailable provider surfaces as 503 rather than a 500")
    void providerUnavailableIs503() throws Exception {
        when(kycService.submit(any(), eq(CUSTOMER_ID), any(), any()))
                .thenThrow(CustomerErrorCodes.KYC_PROVIDER_UNAVAILABLE.exception());

        mvc.perform(post("/api/v1/customers/{id}/kyc", CUSTOMER_ID)
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentReference": "DOC-001",
                                  "printedName": "Dana Okonkwo",
                                  "expiryDate": "2031-04-17",
                                  "issuingCountry": "GB",
                                  "nationality": "GB"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("KYC_PROVIDER_UNAVAILABLE"));
    }

    @Test
    @DisplayName("KYC history returns the check's audit evidence")
    void kycHistoryRendersChecks() throws Exception {
        when(customerService.getOwnProfile(any())).thenReturn(profile(false));
        when(customerService.kycHistory(any(), eq(CUSTOMER_ID)))
                .thenReturn(List.of(new CustomerProfileView.KycCheckView(
                        UUID.randomUUID(),
                        KycStatus.APPROVED,
                        "prov-ref-1",
                        List.of(),
                        List.of(
                                new CustomerProfileView.KycCheckView.CheckResultView(
                                        "DOCUMENT_AUTHENTICITY", true, null),
                                new CustomerProfileView.KycCheckView.CheckResultView("ADDRESS_MATCH", true, null)),
                        NOW,
                        NOW)));

        mvc.perform(get("/api/v1/customers/me/kyc").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("APPROVED"))
                .andExpect(jsonPath("$[0].providerReference").value("prov-ref-1"))
                .andExpect(jsonPath("$[0].checks.length()").value(2))
                .andExpect(jsonPath("$[0].checks[0].checkName").value("DOCUMENT_AUTHENTICITY"))
                .andExpect(jsonPath("$[0].checks[0].passed").value(true));
    }

    // ---------------------------------------------------------------- erasure

    @Test
    @DisplayName("self-service erasure is resolved by subject, with no id in the path")
    void eraseOwnProfileTakesNoId() throws Exception {
        mvc.perform(delete("/api/v1/customers/me").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$.email").doesNotExist());

        verify(customerService).eraseOwnProfile(any());
    }

    @Test
    @DisplayName("a profile update passes the caller's identity and the resolved id to the service")
    void updatePassesIdentity() throws Exception {
        when(customerService.getOwnProfile(any())).thenReturn(profile(false));
        when(customerService.updateProfile(any(), eq(CUSTOMER_ID), any(), any()))
                .thenReturn(profile(false));

        mvc.perform(put("/api/v1/customers/me")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody()))
                .andExpect(status().isOk());

        // Both arguments are asserted. Passing the identity but the wrong id, or the right id with a
        // null identity, are the two ways this endpoint could go wrong and both are invisible in a
        // status-code-only assertion.
        ArgumentCaptor<InternalIdentity> captor = ArgumentCaptor.forClass(InternalIdentity.class);
        verify(customerService).updateProfile(captor.capture(), eq(CUSTOMER_ID), any(), any());
        assertThat(captor.getValue().subject()).isEqualTo(OWNER);
    }

    // ---------------------------------------------------------------- helpers

    private static CustomerProfileView profile(boolean masked) {
        return new CustomerProfileView(
                CUSTOMER_ID,
                "Dana Okonkwo",
                LocalDate.of(1991, 4, 17),
                "dana@example.test",
                "+447700900123",
                new com.fintech.platform.customer.domain.CustomerIdentity.PostalAddress(
                        "12 Alder Way", null, "Manchester", "M1 4BT", "GB"),
                KycStatus.PENDING_REVIEW,
                NOW,
                NOW,
                false,
                masked);
    }

    private static InternalIdentity identityOf(String subject, List<String> roles) {
        return new InternalIdentity(subject, "test-user", roles, "corr-1", Instant.now());
    }

    private static RequestPostProcessor signedAs(String subject, List<String> roles) {
        return request -> {
            Map<String, String> headers = CODEC.headersFor(identityOf(subject, roles));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    private static String registrationBody() throws Exception {
        return """
                {
                  "fullName": "Dana Okonkwo",
                  "dateOfBirth": "1991-04-17",
                  "nationality": "GB",
                  "email": "dana@example.test",
                  "phone": "+447700900123",
                  "address": {
                    "line1": "12 Alder Way",
                    "city": "Manchester",
                    "postalCode": "M1 4BT",
                    "country": "GB"
                  }
                }
                """.formatted();
    }
}
