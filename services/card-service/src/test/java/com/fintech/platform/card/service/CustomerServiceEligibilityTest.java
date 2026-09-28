package com.fintech.platform.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.resilience.OutboundGuard;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The fail-closed boundary with customer-service.
 *
 * <p>Against a real HTTP server on a real socket, not a mocked {@code RestClient}. This adapter's whole
 * job is the mapping from an upstream status to a platform error code, and a mock would have to be told
 * what a 403 looks like — at which point the test would be asserting that the mock was configured
 * correctly. A local server lets each case be a genuine status code, and lets the forwarded identity
 * headers be inspected as they actually arrive.
 *
 * <p>The cases that matter are the refusals. A happy path proves nothing here: what is worth proving is
 * that every way of not getting a trustworthy answer produces an error rather than a card.
 */
class CustomerServiceEligibilityTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final String SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    private HttpServer server;
    private CustomerServiceEligibility eligibility;

    /** What the fake customer-service should answer with, and the headers it saw. */
    private final AtomicReference<Map<String, String>> lastHeaders = new AtomicReference<>();

    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String body = """
            {"kycStatus":"APPROVED"}
            """;
    private volatile long delayMillis = 0;

    /** Hits against the stub, so a test can prove the breaker stopped calling. */
    private final AtomicReference<Integer> hits = new AtomicReference<>(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            hits.updateAndGet(count -> count + 1);
            lastHeaders.set(java.util.stream.StreamSupport.stream(
                            exchange.getRequestHeaders().entrySet().spliterator(), false)
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                            e -> e.getValue().getFirst())));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        hits.set(0);

        eligibility = new CustomerServiceEligibility(
                RestClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build(),
                CODEC,
                TIMEOUT,
                testGuard());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- approvals

    @Test
    @DisplayName("approves a cardholder whose identity check is approved")
    void approves() {
        // The full realistic profile from the shared contract fixture, not the minimal
        // shape: the adapter must tolerate the real response, unknown fields and all.
        // The producer side pins the same bytes in CustomerEligibilityContractTest.
        body = fixture("contracts/eligibility-response.json");
        assertThat(eligibility.isApproved(CUSTOMER_ID, caller())).isTrue();
    }

    @Test
    @DisplayName("refuses a cardholder whose identity check is pending or rejected, without erroring")
    void refusesWithoutErroring() {
        // Not an exception: "we checked and the answer was no" is a normal outcome of the check, and it
        // is the service's job to turn it into a domain refusal. Throwing here would make every
        // not-yet-approved card look like an outage.
        for (String kycStatus : List.of("PENDING_REVIEW", "REJECTED", "NOT_STARTED")) {
            body = """
                    {"kycStatus":"%s"}
                    """.formatted(kycStatus);
            assertThat(eligibility.isApproved(CUSTOMER_ID, caller())).isFalse();
        }
    }

    @Test
    @DisplayName("refuses a status it has never heard of rather than failing to parse it")
    void refusesAnUnknownStatus() {
        // A shared enum would throw here and surface as a 500 from this service, which reads as a
        // card-service bug rather than an upstream change. Unknown must mean no.
        body = """
                {"kycStatus":"SOMETHING_NEW_UPSTREAM"}
                """;

        assertThat(eligibility.isApproved(CUSTOMER_ID, caller())).isFalse();
    }

    @Test
    @DisplayName("forwards the caller's own signed identity rather than asserting one of its own")
    void forwardsTheCallersIdentity() {
        eligibility.isApproved(CUSTOMER_ID, caller("SUPPORT_AGENT"));

        Map<String, String> headers = lastHeaders.get();
        assertThat(headers).isNotNull();
        // The subject must be the caller's own, because customer-service authorises the request against
        // it. A hop that minted a fresh identity with elevated roles would turn a compromised
        // card-service into a reader of every profile in the platform, so the forwarded subject is the
        // real one and the forwarded role is the one the caller actually had.
        // Header names are matched case-insensitively, as HTTP requires. The JDK's server normalises
        // them to X-lower-case, so the collected keys are lower-cased rather than compared against the
        // codec's own constants verbatim.
        assertThat(headers.get(InternalIdentityCodec.HEADER_SUBJECT.toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo(SUBJECT);
        assertThat(headers.get(InternalIdentityCodec.HEADER_ROLES.toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo("SUPPORT_AGENT");
        assertThat(headers.get(InternalIdentityCodec.HEADER_CORRELATION_ID.toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo("corr-1");
        assertThat(headers).containsKey(InternalIdentityCodec.HEADER_SIGNATURE.toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    @DisplayName("asks customer-service about the customer it was given")
    void asksAboutTheNamedCustomer() {
        eligibility.isApproved(CUSTOMER_ID, caller());

        // The path is the only place the customer id travels, so it is asserted against the id rather
        // than left implied by the fact that the call returned at all.
        assertThat(requestedPath.get()).isEqualTo("/api/v1/customers/" + CUSTOMER_ID);
    }

    // ------------------------------------------------------- circuit breaker

    @Test
    @DisplayName("opens after consecutive outages and fails fast without calling")
    void opensAndFailsFast() {
        // A downed customer-service must cost microseconds per caller, not a thread
        // held for the full timeout each: once the window fills with failures the
        // breaker refuses without touching the network, and the refusal carries the
        // same fail-closed code as the outage itself.
        status = 500;

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
        }
        int hitsAfterTrip = hits.get();

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
        assertThat(hits.get()).isEqualTo(hitsAfterTrip);
    }

    @Test
    @DisplayName("definitive refusals never trip the breaker")
    void refusalsDoNotTripBreaker() {
        // Five consecutive 403s are five answers, not an outage: the sixth call
        // still goes through and its answer is honoured.
        status = 403;
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.NOT_THE_CARDHOLDER.code());
        }

        status = 200;
        assertThat(eligibility.isApproved(CUSTOMER_ID, caller())).isTrue();
    }

    // -------------------------------------------------------------- refusals

    @Test
    @DisplayName("maps an upstream 403 to not-the-cardholder, not to an outage")
    void mapsForbiddenToNotTheCardholder() {
        // The single most important mapping in this class. Reported as "unavailable" it would tell the
        // caller to retry, turning an attempt to read somebody else's profile into a retry loop and
        // hiding the fact that the answer is simply no.
        status = 403;

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.NOT_THE_CARDHOLDER.code());
    }

    @Test
    @DisplayName("maps an upstream 404 to holder-not-found, distinct from not approved")
    void mapsNotFoundToHolderNotFound() {
        // A typo in a request and a customer failing a check are different events, and an operator
        // chasing the wrong one wastes the outage.
        status = 404;

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.HOLDER_NOT_FOUND.code());
    }

    @Test
    @DisplayName("fails closed when the upstream is unreachable")
    void failsClosedWhenUnreachable() {
        server.stop(0);

        // The whole point of the class. Issuance is the one place where failing open would create a card
        // for somebody the platform has not verified.
        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("fails closed on a 200 with no status in the body")
    void failsClosedOnAnEmptyBody() {
        // Not a refusal and not an approval. Answering "not approved" would be indistinguishable from a
        // real KYC decision and would send the operator chasing a KYC problem that does not exist.
        body = "{}";

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("fails closed on a 200 with a null status")
    void failsClosedOnANullStatus() {
        body = """
                {"kycStatus":null}
                """;

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("fails closed on an unexpected success status")
    void failsClosedOnAnUnexpectedStatus() {
        // 204 and friends carry no body, so there is nothing to approve on.
        status = 204;
        body = "";

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("fails closed on a 5xx rather than reading it as a refusal")
    void failsClosedOnAServerError() {
        status = 500;
        body = """
                {"kycStatus":"APPROVED"}
                """;

        // Note the body says APPROVED. A client that trusted the body of an error response would issue a
        // card on the strength of an upstream failure, which is the worst of both answers at once.
        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("gives up rather than hanging when the upstream is too slow")
    void givesUpOnASlowUpstream() {
        // Without a timeout a hung customer-service becomes a hung card endpoint, and the thread pool
        // exhausts on requests that can never be answered. The budget is short on purpose: a caller who
        // cannot get an answer quickly does not get a card from this call.
        delayMillis = 1_500;
        eligibility = new CustomerServiceEligibility(
                RestClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                            {
                                setConnectTimeout(500);
                                setReadTimeout(200);
                            }
                        })
                        .build(),
                CODEC,
                TIMEOUT,
                testGuard());

        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(CardErrorCodes.ELIGIBILITY_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("never leaks an upstream status code into a platform error")
    void speaksOnlyItsOwnVocabulary() {
        status = 418;

        // card-service should not have to know what customer-service calls things, and a client should
        // not have to know what it calls them either.
        assertThatCode(() -> eligibility.isApproved(CUSTOMER_ID, caller())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> eligibility.isApproved(CUSTOMER_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isNotEqualTo("418");
    }

    private static InternalIdentity caller(String... roles) {
        return new InternalIdentity(SUBJECT, "test-user", List.of(roles), "corr-1", Instant.now());
    }

    /**
     * A guard tuned to trip fast: two calls decide, one attempt each, no waiting.
     * Production uses a wider window and a thirty-second open; the discipline is
     * identical, only the patience differs.
     */
    private static OutboundGuard testGuard() {
        return OutboundGuard.of(
                "test",
                new OutboundGuard.Settings(50, 2, Duration.ofMinutes(1), 1, 1, Duration.ZERO),
                new SimpleMeterRegistry());
    }

    private static String fixture(String name) {
        try (InputStream in =
                CustomerServiceEligibilityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("contract fixture %s on the test classpath", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read contract fixture " + name, e);
        }
    }
}
