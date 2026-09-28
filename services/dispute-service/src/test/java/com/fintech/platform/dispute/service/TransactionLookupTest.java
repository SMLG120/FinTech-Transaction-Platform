package com.fintech.platform.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.resilience.OutboundGuard;
import com.fintech.platform.dispute.error.DisputeErrors;
import com.fintech.platform.dispute.service.TransactionLookup.SettledPayment;
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
 * The fail-closed boundary with transaction-service.
 *
 * <p>Same shape as card-service's {@code CustomerServiceEligibilityTest}, deliberately: the two
 * adapters are the platform's only synchronous inter-service calls, and they share the same three
 * duties — short timeouts, fail-closed refusals in this service's own vocabulary, and forwarding
 * the caller's own signed identity. Against a real HTTP server on a real socket, for the same
 * reason: the mapping from an upstream status to a platform error code is the whole job, and a
 * mock would only assert its own configuration.
 *
 * <p>The settled case serves the shared {@code transaction-view.json} contract fixture — the full
 * realistic payment, not the minimal shape — and the producer side pins the same bytes in
 * transaction-service's {@code TransactionViewContractTest}.
 */
class TransactionLookupTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    private static final String SUBJECT = "9f3d5c7a-1b2e-4a3f-8c5d-6e7f8a9b0c1d";
    private static final UUID TRANSACTION_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    private HttpServer server;
    private TransactionLookup lookup;

    /** What the fake transaction-service should answer with, and the headers it saw. */
    private final AtomicReference<Map<String, String>> lastHeaders = new AtomicReference<>();

    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String body = "";

    /** Hits against the stub, so a test can prove the breaker stopped calling. */
    private final AtomicReference<Integer> hits = new AtomicReference<>(0);

    @BeforeEach
    void startServer() throws IOException {
        body = fixture("contracts/transaction-view.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            hits.updateAndGet(count -> count + 1);
            lastHeaders.set(java.util.stream.StreamSupport.stream(
                            exchange.getRequestHeaders().entrySet().spliterator(), false)
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                            e -> e.getValue().getFirst())));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        hits.set(0);

        lookup = new TransactionLookup(
                RestClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build(),
                CODEC,
                TIMEOUT,
                OutboundGuard.of(
                        "test",
                        new OutboundGuard.Settings(50, 2, Duration.ofMinutes(1), 1, 1, Duration.ZERO),
                        new SimpleMeterRegistry()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("returns a settled payment with its figure from the contract fixture")
    void returnsSettledPayment() {
        SettledPayment settled = lookup.requireSettledOwnedBy(TRANSACTION_ID, caller());

        assertThat(settled.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(settled.amount()).isEqualTo("25.00");
        assertThat(settled.currency()).isEqualTo("GBP");
    }

    @Test
    @DisplayName("refuses a payment that is authorised but not yet settled")
    void refusesUnsettledPayment() {
        // A hold is money reserved, not money moved — disputing it would demand a refund
        // of a payment that never completed, so the payment's state (422) is the refusal.
        body = """
                {"id":"%s","amount":"25.00","currency":"GBP","status":"AUTHORIZED"}
                """.formatted(TRANSACTION_ID);

        assertThatThrownBy(() -> lookup.requireSettledOwnedBy(TRANSACTION_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(DisputeErrors.PAYMENT_NOT_SETTLED.code());
    }

    @Test
    @DisplayName("maps an upstream 404 to payment-not-found, whether missing or not the caller's")
    void mapsNotFoundToPaymentNotFound() {
        // transaction-service deliberately answers both the same way, so reaching for
        // another customer's money looks exactly like a typo. Carried through, not
        // second-guessed: a 403 here would confirm the payment is real.
        status = 404;

        assertThatThrownBy(() -> lookup.requireSettledOwnedBy(TRANSACTION_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(DisputeErrors.PAYMENT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("maps an upstream outage to transaction-unavailable, failing closed")
    void mapsOutageToTransactionUnavailable() {
        status = 500;

        assertThatThrownBy(() -> lookup.requireSettledOwnedBy(TRANSACTION_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(DisputeErrors.TRANSACTION_UNAVAILABLE.code());
    }

    @Test
    @DisplayName("opens after consecutive outages and fails fast without calling")
    void opensAndFailsFast() {
        // A downed transaction-service must cost microseconds per caller, not a
        // thread held for the full timeout each: once the window fills with
        // failures the breaker refuses without touching the network, and the
        // refusal carries the same fail-closed code as the outage itself.
        status = 500;

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> lookup.requireSettledOwnedBy(TRANSACTION_ID, caller()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(DisputeErrors.TRANSACTION_UNAVAILABLE.code());
        }
        int hitsAfterTrip = hits.get();

        assertThatThrownBy(() -> lookup.requireSettledOwnedBy(TRANSACTION_ID, caller()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(DisputeErrors.TRANSACTION_UNAVAILABLE.code());
        assertThat(hits.get()).isEqualTo(hitsAfterTrip);
    }

    @Test
    @DisplayName("asks transaction-service about the payment it was given")
    void asksAboutTheNamedTransaction() {
        lookup.requireSettledOwnedBy(TRANSACTION_ID, caller());

        assertThat(requestedPath.get()).isEqualTo("/api/v1/transactions/" + TRANSACTION_ID);
    }

    @Test
    @DisplayName("forwards the caller's own signed identity rather than asserting one of its own")
    void forwardsTheCallersIdentity() {
        lookup.requireSettledOwnedBy(TRANSACTION_ID, caller());

        // transaction-service applies its ownership rule to this identity, so the subject
        // must be the caller's own: a hop that minted a fresh identity would turn a
        // compromised dispute-service into a reader of every payment on the platform.
        Map<String, String> headers = lastHeaders.get();
        assertThat(headers).isNotNull();
        assertThat(headers.get(InternalIdentityCodec.HEADER_SUBJECT.toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo(SUBJECT);
        assertThat(headers).containsKey(InternalIdentityCodec.HEADER_SIGNATURE.toLowerCase(java.util.Locale.ROOT));
    }

    private static InternalIdentity caller() {
        return new InternalIdentity(SUBJECT, "test-user", List.of("CUSTOMER"), "corr-1", Instant.now());
    }

    private static String fixture(String name) {
        try (InputStream in = TransactionLookupTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("contract fixture %s on the test classpath", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read contract fixture " + name, e);
        }
    }
}
