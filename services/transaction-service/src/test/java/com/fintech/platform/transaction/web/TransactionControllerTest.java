package com.fintech.platform.transaction.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import com.fintech.platform.transaction.domain.Counterparty;
import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.domain.TransactionStatus;
import com.fintech.platform.transaction.error.TransactionErrorCodes;
import com.fintech.platform.transaction.identity.SubjectDigester;
import com.fintech.platform.transaction.service.PaymentFacade;
import com.fintech.platform.transaction.service.TransactionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The HTTP contract for the two money-moving endpoints, through the real signed-identity filter.
 *
 * <p>The services are mocked, so what is under test is the adapter's part: taking the caller from the
 * verified identity, and rendering each {@link PaymentFacade.Result} as the status and bytes the client
 * needs.
 *
 * <p><b>Most of this class is about one bug.</b> A replayed response is the JSON <em>text</em> stored on
 * the first attempt, not an object. Handing that text to Jackson as a body gets it JSON-encoded a second
 * time, and the client receives {@code "{\"id\":\"...\"}"} — a quoted string — where its first attempt
 * received an object. The payment had succeeded either way, so nothing in the ledger, the idempotency
 * table, or the response <em>status</em> looked wrong: only the client noticed, when its parser asked for
 * {@code $.id} and found a string. That is why the assertions below check the shape of the body and not
 * merely the status code.
 */
@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc
@Import({WebAutoConfiguration.class, CurrentCaller.class})
class TransactionControllerTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final String SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";
    private static final String OWNER_DIGEST = "3f7a1c9e2b4d8065a1f3c7e9b2d40658a1c3e5f7092b4d6813a7c9e2b5d8f046";
    private static final String CARD_TOKEN = "tok_" + "0123456789abcdef".repeat(3) + "0123456789ab";
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant CREATED_AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant AUTHORIZED_AT = Instant.parse("2026-03-01T10:00:01Z");
    private static final UUID TRANSACTION_ID = UUID.fromString("22222233-4444-5555-6666-777788889999");

    /**
     * The system clock, because the filter builds its own codec and refuses an identity older than the
     * maximum age. Only the signed header has to be live; every asserted value stays fixed.
     */
    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @DynamicPropertySource
    static void internalIdentityKey(DynamicPropertyRegistry registry) {
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentFacade payments;

    @MockitoBean
    private TransactionService transactions;

    @MockitoBean
    private SubjectDigester digester;

    @BeforeEach
    void stubTheCaller() {
        when(digester.digestOf(SUBJECT)).thenReturn(OWNER_DIGEST);
    }

    // ------------------------------------------------------------ replayed responses

    @Test
    @DisplayName("a replay returns the stored bytes as JSON, not a quoted string")
    void replayReturnsStoredBytesUnchanged() throws Exception {
        // The regression. The stored text is what the first attempt put on the wire; a retry must see
        // the same document, so that a client parsing it as an object keeps working.
        String stored = """
                {"id":"%s","status":"AUTHORIZED","amount":"25.00","currency":"GBP","cardLast4":"1111"}""".formatted(TRANSACTION_ID);
        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(PaymentFacade.Result.Answer.replayed(201, stored));

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Byte for byte, because a retry that receives a different document is a client bug.
                .andExpect(content().string(stored))
                // And still an object, which is the half that double-encoding broke.
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("a fresh response is an object, and is not marked as a replay")
    void freshResponseIsAnObject() throws Exception {
        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(PaymentFacade.Result.Answer.fresh(201, response(TransactionStatus.AUTHORIZED)));

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "first-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/transactions/" + TRANSACTION_ID))
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("a replay carries no Location header, since the first attempt already sent it")
    void replayCarriesNoLocation() throws Exception {
        // Not cosmetic: a Location on a replay would point at the payment again, and a client that
        // followed it would re-request the thing it had just been told about.
        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(PaymentFacade.Result.Answer.replayed(201, """
                        {"id":"%s","status":"AUTHORIZED"}""".formatted(TRANSACTION_ID)));

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    @DisplayName("a decline is a 422, and a retry of it stays a 422")
    void declineIs422AndReplaysAs422() throws Exception {
        // The dangerous version of the same bug: a client that retries a decline and is told 201 would
        // believe it had been paid when it had not.
        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(PaymentFacade.Result.Answer.fresh(422, response(TransactionStatus.DECLINED)));

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "declined-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(PaymentFacade.Result.Answer.replayed(422, """
                        {"id":"%s","status":"DECLINED","reason":"INSUFFICIENT_FUNDS"}""".formatted(TRANSACTION_ID)));

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "declined-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("DECLINED"));
    }

    // ------------------------------------------------------------ idempotency failures

    @Test
    @DisplayName("a key reused with a different request is a 409 naming the key")
    void reusedKeyConflicts() throws Exception {
        when(payments.createPayment(any(), any(), any(), any(), any())).thenReturn(new PaymentFacade.Result.Conflict());

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "reused-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isConflict())
                // The platform's error envelope, not a bespoke one. Asserted on the fields a client
                // depends on, because a second shape here would still satisfy a status-code check.
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/v1/transactions"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a key still in flight is a 409 that says when to come back")
    void inProgressKeySaysWhenToRetry() throws Exception {
        // Without Retry-After a client has three options: give up, guess, or hammer. With it, the
        // answer is "one second", and the difference between that and a guess is the retry storm the
        // claim is built to absorb.
        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentFacade.Result.InProgress());

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "busy-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.error").value("IDEMPOTENT_REQUEST_IN_PROGRESS"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/api/v1/transactions"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a payment with no Idempotency-Key is a 400, not a 500")
    void paymentWithoutKeyIsRefused() throws Exception {
        // The status is the point. The refusal is correct in the service, but a caller that omits the
        // header has made a mistake, and answering 500 would send them looking for a platform outage
        // instead of at their own request. It also means a 5xx alert fires for ordinary client misuse.
        when(payments.createPayment(any(), isNull(), any(), any(), any()))
                .thenThrow(TransactionErrorCodes.IDEMPOTENCY_KEY_REQUIRED.exception());

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("a top-up with no Idempotency-Key is a 400 too")
    void fundingWithoutKeyIsRefused() throws Exception {
        // Both money-moving endpoints, because funding is exactly as double-spendable as a payment and
        // a top-up guarded while a payment was not would be the more surprising of the two gaps.
        when(payments.fundAccount(any(), isNull(), any()))
                .thenThrow(TransactionErrorCodes.IDEMPOTENCY_KEY_REQUIRED.exception());

        mvc.perform(post("/api/v1/accounts/fund")
                        .with(signedAsCustomer())
                        .param("amount", "100.00")
                        .param("currency", "GBP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("the caller comes from the verified identity, not the request")
    void callerComesFromTheIdentity() throws Exception {
        when(payments.createPayment(any(), any(), any(), any(), any()))
                .thenReturn(PaymentFacade.Result.Answer.fresh(201, response(TransactionStatus.AUTHORIZED)));

        mvc.perform(post("/api/v1/transactions")
                        .with(signedAsCustomer())
                        .header("Idempotency-Key", "first-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("25.00")))
                .andExpect(status().isCreated());

        // The digest is derived from the signed subject, so a body cannot name someone else's account.
        verify(payments).createPayment(eq(OWNER_DIGEST), eq("first-key"), any(), any(), any());
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * A real response, built through the entity's own factory and state machine rather than by
     * assembling one. The point of this class is what goes on the wire, and a hand-built DTO would
     * keep passing if the mapping from entity to response rotted.
     */
    private TransactionResponse response(TransactionStatus status) {
        Money amount = Money.parse("25.00", GBP);
        Counterparty payee = new Counterparty("Coffee", "ref-1");
        Transaction transaction =
                Transaction.create(TRANSACTION_ID, OWNER_DIGEST, CARD_TOKEN, amount, payee, CREATED_AT);
        if (status == TransactionStatus.AUTHORIZED) {
            transaction.authorize(AUTHORIZED_AT);
        } else {
            transaction.decline("INSUFFICIENT_FUNDS", AUTHORIZED_AT);
        }
        return TransactionResponse.from(transaction);
    }

    private static InternalIdentity identityOf(String subject, List<String> roles) {
        return new InternalIdentity(subject, "test-user", roles, "corr-1", Instant.now());
    }

    private static RequestPostProcessor signedAsCustomer() {
        return request -> {
            Map<String, String> headers = CODEC.headersFor(identityOf(SUBJECT, List.of("CUSTOMER")));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    private static String createBody(String amount) {
        return """
                {"amount":"%s","currency":"GBP","cardToken":"%s","payeeName":"Coffee","payeeReference":"ref-1"}""".formatted(amount, CARD_TOKEN);
    }
}
