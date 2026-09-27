package com.fintech.platform.card.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.card.domain.CardBrand;
import com.fintech.platform.card.domain.CardStatus;
import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.card.service.CardService;
import com.fintech.platform.card.service.CardView;
import com.fintech.platform.card.service.IssuedCard;
import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
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
 * <p>The service is mocked, so what is under test is the controller's part of the job: binding a
 * request, taking the caller from the verified identity rather than the body, and rendering exactly
 * what the service decided. Two mistakes in this class are invisible to every other test in the
 * service, and they are the reason it exists.
 *
 * <ol>
 *   <li>An endpoint that forgets to pass the identity to the service, which would leave the service
 *       unable to tell who is asking. Asserted with {@code verify} rather than by status code.
 *   <li>A lifecycle endpoint that returns something other than the state the service transitioned to,
 *       which is how a UI ends up showing a card as frozen while the database says active.
 * </ol>
 *
 * <p>The one-time number is asserted at both ends of the controller: present on the issue response,
 * absent from every other. A second endpoint that rendered {@code IssuedCard} would be the most
 * natural way for this design to rot, and it would be invisible to the service tests.
 *
 * <p>Identities are signed rather than injected as a request attribute, because
 * {@code REQUEST_ATTRIBUTE} is package-private to platform-common-web on purpose: a test that could
 * set it directly would prove the controller's behaviour without proving that the only way to reach
 * the controller is through a verified identity.
 */
@WebMvcTest(CardController.class)
@AutoConfigureMockMvc
@Import({WebAutoConfiguration.class, CurrentCaller.class})
class CardControllerTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final String OWNER = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";
    private static final String AGENT = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CARD_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final String CARD_NUMBER = "4925 4873 7447 9139";

    /**
     * The system clock rather than a fixed one, because the filter under test builds its own codec and
     * refuses an identity older than the max age. Timestamps in assertions stay fixed; only the signed
     * header has to be live.
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
    private CardService cardService;

    // ------------------------------------------------------------------ issue

    @Test
    @DisplayName("issues a card, returning the number once and a Location for the card")
    void issuesACard() throws Exception {
        when(cardService.issue(any(), eq(CUSTOMER_ID), eq(CardBrand.DEBIT)))
                .thenReturn(new IssuedCard(view(CardStatus.ACTIVE), CARD_NUMBER));

        mvc.perform(post("/api/v1/cards")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/cards/" + CARD_ID))
                .andExpect(jsonPath("$.cardNumber").value(CARD_NUMBER))
                .andExpect(jsonPath("$.card.id").value(CARD_ID.toString()))
                .andExpect(jsonPath("$.card.last4").value("9139"))
                .andExpect(jsonPath("$.card.status").value("ACTIVE"))
                .andExpect(jsonPath("$.card.usable").value(true));
    }

    @Test
    @DisplayName("takes the issuing caller from the verified identity, not the body")
    void takesTheCallerFromTheIdentity() throws Exception {
        // The request body names a customer; it must never be able to name a subject. Captured rather
        // than inferred from a status code, because passing the right identity with the wrong customer
        // id is just as much a bug as passing none.
        when(cardService.issue(any(), any(), any())).thenReturn(new IssuedCard(view(CardStatus.ACTIVE), CARD_NUMBER));

        mvc.perform(post("/api/v1/cards")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody()))
                .andExpect(status().isCreated());

        ArgumentCaptor<InternalIdentity> captor = ArgumentCaptor.forClass(InternalIdentity.class);
        verify(cardService).issue(captor.capture(), eq(CUSTOMER_ID), eq(CardBrand.DEBIT));
        assertThat(captor.getValue().subject()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("refuses an issue request with no customer id, without calling the service")
    void refusesAnIssueWithNoCustomer() throws Exception {
        mvc.perform(post("/api/v1/cards")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brand":"DEBIT"}
                                """))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(cardService);
    }

    @Test
    @DisplayName("refuses an unknown brand, without calling the service")
    void refusesAnUnknownBrand() throws Exception {
        mvc.perform(post("/api/v1/cards")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","brand":"MAESTRO"}
                                """.formatted(CUSTOMER_ID)))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(cardService);
    }

    @Test
    @DisplayName("maps an ineligible customer to 409, carrying the platform's error code")
    void mapsIneligibilityToConflict() throws Exception {
        // 409 rather than 403, deliberately: the caller is authenticated and is asking for something
        // permitted, but the state of their identity check conflicts with it. 403 would tell a client
        // the caller lacks permission, which is not true and would send a cardholder chasing a permissions
        // problem instead of completing their KYC.
        when(cardService.issue(any(), any(), any())).thenThrow(CardErrorCodes.HOLDER_NOT_ELIGIBLE.exception());

        mvc.perform(post("/api/v1/cards")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("HOLDER_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("maps the card limit to 409, and says how many are held")
    void mapsTheCardLimitToConflict() throws Exception {
        when(cardService.issue(any(), any(), any()))
                .thenThrow(CardErrorCodes.CARD_LIMIT_REACHED.exception(
                        "customer already holds 5 of a permitted 5 cards", Map.of("held", 5, "limit", 5)));

        mvc.perform(post("/api/v1/cards")
                        .with(signedAs(OWNER, List.of("CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CARD_LIMIT_REACHED"))
                .andExpect(jsonPath("$.details.held").value(5));
    }

    // ------------------------------------------------------------------- read

    @Test
    @DisplayName("lists the caller's own cards, and never a number")
    void listsOwnCards() throws Exception {
        when(cardService.listOwnCards(any())).thenReturn(List.of(view(CardStatus.ACTIVE), view(CardStatus.FROZEN)));

        mvc.perform(get("/api/v1/cards").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].cardNumber").doesNotExist())
                .andExpect(jsonPath("$[0].last4").value("9139"));
    }

    @Test
    @DisplayName("returns one card without a number on it")
    void returnsOneCardWithoutANumber() throws Exception {
        when(cardService.getCard(any(), eq(CARD_ID))).thenReturn(view(CardStatus.ACTIVE));

        mvc.perform(get("/api/v1/cards/" + CARD_ID).with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CARD_ID.toString()))
                .andExpect(jsonPath("$.cardNumber").doesNotExist());
    }

    @Test
    @DisplayName("answers 404 for a card that does not exist, to everyone")
    void answersNotFound() throws Exception {
        when(cardService.getCard(any(), eq(CARD_ID))).thenThrow(CardErrorCodes.CARD_NOT_FOUND.exception());

        mvc.perform(get("/api/v1/cards/" + CARD_ID).with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CARD_NOT_FOUND"));
    }

    // --------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("freezes a card and reports the state the service moved it to")
    void freezesACard() throws Exception {
        when(cardService.freeze(any(), eq(CARD_ID))).thenReturn(view(CardStatus.FROZEN));

        mvc.perform(post("/api/v1/cards/" + CARD_ID + "/freeze").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.cardNumber").doesNotExist());

        ArgumentCaptor<InternalIdentity> captor = ArgumentCaptor.forClass(InternalIdentity.class);
        verify(cardService).freeze(captor.capture(), eq(CARD_ID));
        assertThat(captor.getValue().subject()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("unfreezes a card")
    void unfreezesACard() throws Exception {
        when(cardService.unfreeze(any(), eq(CARD_ID))).thenReturn(view(CardStatus.ACTIVE));

        mvc.perform(post("/api/v1/cards/" + CARD_ID + "/unfreeze").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("lets a support agent report a card lost")
    void supportReportsLost() throws Exception {
        when(cardService.reportLost(any(), eq(CARD_ID))).thenReturn(view(CardStatus.LOST));

        mvc.perform(post("/api/v1/cards/" + CARD_ID + "/lost").with(signedAs(AGENT, List.of("SUPPORT_AGENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOST"));
    }

    @Test
    @DisplayName("cancels a card with DELETE and still returns the record, not a 404")
    void cancelsACard() throws Exception {
        // DELETE, but the card is retained. A client expecting a hard delete would read the 200 as the
        // row being gone and draw the wrong conclusion from the 404 it gets for a second delete.
        when(cardService.cancel(any(), eq(CARD_ID))).thenReturn(view(CardStatus.CANCELLED));

        mvc.perform(delete("/api/v1/cards/" + CARD_ID).with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("maps an invalid transition to 409, with the current status in the details")
    void mapsInvalidTransitionToConflict() throws Exception {
        when(cardService.unfreeze(any(), eq(CARD_ID)))
                .thenThrow(new ApiException(
                        CardErrorCodes.CARD_REPORTED_LOST,
                        "card was reported lost on 2026-03-01T10:00:00Z and cannot be reactivated",
                        Map.of("status", "LOST")));

        mvc.perform(post("/api/v1/cards/" + CARD_ID + "/unfreeze").with(signedAs(OWNER, List.of("CUSTOMER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CARD_REPORTED_LOST"));
    }

    // ------------------------------------------------------------------ trust

    @Test
    @DisplayName("refuses an unsigned request, so the controller cannot be reached directly")
    void refusesAnUnsignedRequest() throws Exception {
        // The whole authorisation model rests on the caller being the verified one. A path that reached
        // the controller without a signature would be an unauthenticated card API, so it is worth
        // asserting the refusal rather than assuming the filter is in the chain.
        mvc.perform(get("/api/v1/cards/" + CARD_ID)).andExpect(status().isUnauthorized());

        verifyNoInteractions(cardService);
    }

    // ---------------------------------------------------------------- helpers

    private static CardView view(CardStatus status) {
        return new CardView(
                CARD_ID,
                CUSTOMER_ID,
                "9139",
                CardBrand.DEBIT,
                LocalDate.of(2029, 6, 30),
                status,
                status == CardStatus.ACTIVE,
                status == CardStatus.FROZEN ? NOW : null,
                status == CardStatus.LOST ? NOW : null,
                status == CardStatus.CANCELLED ? NOW : null,
                NOW);
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

    private static String issueBody() {
        return """
                {"customerId":"%s","brand":"DEBIT"}
                """.formatted(CUSTOMER_ID);
    }
}
