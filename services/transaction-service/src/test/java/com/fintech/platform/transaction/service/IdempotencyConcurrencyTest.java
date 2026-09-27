package com.fintech.platform.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.TransactionStatus;
import com.fintech.platform.transaction.persistence.JournalEntryRepository;
import com.fintech.platform.transaction.persistence.OutboxRepository;
import com.fintech.platform.transaction.persistence.TransactionRepository;
import com.fintech.platform.transaction.web.CreateTransactionRequest;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The duplicate-payment guarantee, against a real database and real concurrency.
 *
 * <p>These exist because the guarantee is enforced by a database constraint, and a constraint cannot be
 * exercised by a mocked repository. Both tests below would pass against a mock no matter what the code did,
 * because a mock has no unique index and no second transaction.
 */
@Testcontainers
@SpringBootTest
class IdempotencyConcurrencyTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final String OWNER = "3f7a1c9e2b4d8065a1f3c7e9b2d40658a1c3e5f7092b4d6813a7c9e2b5d8f046";
    /** A second, unrelated customer, to show keys are scoped per owner rather than platform-wide. */
    private static final String OTHER_OWNER = "7b2e4f6a8c1d3059e7a4b2c8f6d10e3a5c9b7d1f3a5e7c9b1d3f5a7c9e1b3d5f";

    private static final String CARD_TOKEN = "tok_" + "0123456789abcdef".repeat(3) + "0123456789ab";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_transactions_idempotency");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "platform.security.internal-identity.signing-key",
                () -> "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801");
        registry.add(
                "platform.security.subject-digest.key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
        registry.add("app.outbox.relay-enabled", () -> "false");
    }

    @Autowired
    private PaymentFacade facade;

    @Autowired
    private TransactionService transactions;

    @Autowired
    private TransactionRepository transactionRows;

    @Autowired
    private JournalEntryRepository entries;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // A subject digest is 32 bytes of HMAC-SHA256, so 64 hex characters exactly, and the columns
        // are varchar(64). Asserted here so a mistyped constant fails with the real mistake named,
        // rather than surfacing as "value too long for type character varying(64)" from Postgres.
        assertThat(OWNER).as("subject digests are 64 hex characters").hasSize(64);
        assertThat(OTHER_OWNER)
                .as("and the second owner must be a different customer")
                .hasSize(64)
                .isNotEqualTo(OWNER);

        jdbc.execute("TRUNCATE TABLE journal_lines, journal_entries, outbox_events, "
                + "idempotency_keys, transactions, ledger_accounts CASCADE");
    }

    @Test
    @DisplayName("concurrent requests with one key: one payment, and no 500s")
    void concurrentRequestsWithOneKeyProduceOnePayment() throws Exception {
        // What a retry storm looks like: a client that timed out sends the same request again, several
        // times, before the first has answered. Exactly one payment may result.
        //
        // The second half of the assertion is the one that was broken. The claim was a plain INSERT whose
        // unique violation was caught, and on PostgreSQL that aborts the whole transaction — including the
        // re-read the catch block depended on. Seven of eight requests therefore failed with a
        // JpaSystemException rather than the 409-with-Retry-After that tells a client its request is
        // already in hand. The payment was still made exactly once, so the duplicate-payment guarantee
        // held and the test would have passed; what failed was the service telling anyone about it.
        int threads = 8;
        String key = "retry-storm-key";
        CreateTransactionRequest request = paymentRequest("40.00");
        // Funded up front, so that every thread is racing for the same reason. An unfunded account
        // would be refused by AccountNotFound before the claim, and the test would pass without ever
        // reaching the code it exists to test.
        transactions.fund(OWNER, Money.parse("400.00", GBP));

        AtomicInteger created = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    go.await();
                    // Each request in its own transaction, as the web layer would run it.
                    PaymentFacade.Result result = tx.execute(
                            status -> facade.createPayment(OWNER, key, fingerprintOf(request), request, FRAUD_CONTEXT));
                    if (result instanceof PaymentFacade.Result.Answer) {
                        created.incrementAndGet();
                    } else if (result instanceof PaymentFacade.Result.InProgress) {
                        inProgress.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(failures)
                .as("a losing racer is told its request is in progress, not that the platform failed")
                .isEmpty();
        assertThat(failed.get()).isZero();
        assertThat(transactionRows.count())
                .as("one request, one payment, however many times it was sent")
                .isEqualTo(1);
        // A payment publishes two events, created and authorised. Funding posts to the journal but
        // publishes nothing, so exactly two events here is one payment's worth and not eight.
        assertThat(outbox.countPending()).isEqualTo(2);
    }

    @Test
    @DisplayName("a settled key replays the stored response instead of paying again")
    void replayAfterCompletionDoesNotPayAgain() {
        // The ordinary retry: the first attempt answered, the client did not hear it, and it sends the
        // same request again. The answer must be the stored one, and no second payment may exist.
        String key = "ordinary-retry";
        CreateTransactionRequest request = paymentRequest("25.00");
        transactions.fund(OWNER, Money.parse("500.00", GBP));

        PaymentFacade.Result first = facade.createPayment(OWNER, key, fingerprintOf(request), request, FRAUD_CONTEXT);
        assertThat(first).isInstanceOf(PaymentFacade.Result.Answer.class);
        assertThat(((PaymentFacade.Result.Answer) first).replayed()).isFalse();
        assertThat(((PaymentFacade.Result.Answer) first).status()).isEqualTo(201);

        PaymentFacade.Result second = facade.createPayment(OWNER, key, fingerprintOf(request), request, FRAUD_CONTEXT);

        assertThat(second).isInstanceOf(PaymentFacade.Result.Answer.class);
        var answer = (PaymentFacade.Result.Answer) second;
        assertThat(answer.replayed()).isTrue();
        assertThat(answer.status())
                .as("a retry is told exactly what the first attempt was told")
                .isEqualTo(201);
        assertThat(answer.storedJson())
                .as("the first attempt's bytes, not a freshly computed response")
                .isEqualTo(
                        ((PaymentFacade.Result.Answer) first).storedJson() == null
                                ? storedBodyFor(key)
                                : ((PaymentFacade.Result.Answer) first).storedJson());

        assertThat(transactionRows.count())
                .as("the replay did not run the payment again")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a key reused with a different request is a conflict, not a replay")
    void keyReusedWithADifferentRequestConflicts() {
        // The case that must NOT replay. Handing back the first response would tell the caller their
        // second, different payment succeeded — a payment that was never made.
        String key = "reused-key";
        transactions.fund(OWNER, Money.parse("1000.00", GBP));
        facade.createPayment(
                OWNER, key, fingerprintOf(paymentRequest("10.00")), paymentRequest("10.00"), FRAUD_CONTEXT);

        CreateTransactionRequest different = paymentRequest("999.00");
        PaymentFacade.Result result =
                facade.createPayment(OWNER, key, fingerprintOf(different), different, FRAUD_CONTEXT);

        assertThat(result)
                .as("a different amount under the same key is a client bug, and a loud one")
                .isInstanceOf(PaymentFacade.Result.Conflict.class);
        assertThat(transactionRows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a decline is stored and replayed as 422, not upgraded to success on a retry")
    void declineIsReplayedWithItsOriginalStatus() {
        // A client retrying a declined card must not be told it succeeded the second time. That would be
        // the worst possible outcome of a retry: a client that believes it was paid and the merchant
        // believes it was not.
        String key = "declined-retry";
        transactions.fund(OWNER, Money.parse("5.00", GBP));
        CreateTransactionRequest request = paymentRequest("40.00");

        PaymentFacade.Result first = facade.createPayment(OWNER, key, fingerprintOf(request), request, FRAUD_CONTEXT);
        assertThat(((PaymentFacade.Result.Answer) first).status()).isEqualTo(422);
        assertThat(((PaymentFacade.Result.Answer) first).replayed()).isFalse();

        PaymentFacade.Result second = facade.createPayment(OWNER, key, fingerprintOf(request), request, FRAUD_CONTEXT);

        var answer = (PaymentFacade.Result.Answer) second;
        assertThat(answer.replayed()).isTrue();
        assertThat(answer.status()).isEqualTo(422);
        assertThat(answer.storedJson()).contains("DECLINED");
        assertThat(transactionRows.count()).isEqualTo(1);
        assertThat(transactionRows.findAll().get(0).status()).isEqualTo(TransactionStatus.DECLINED);
    }

    @Test
    @DisplayName("two customers may use the same key without seeing each other's responses")
    void keysAreScopedToTheOwner() {
        // Keys are the customer's, not the platform's. A shared reference like "order-12345" from two
        // unrelated clients must not collide, or one customer would be handed the other's payment.
        String shared = "order-12345";
        // A subject digest is 32 bytes of HMAC output: exactly 64 hex characters. The column enforces
        // the width, so an over-long "digest" is a 500 rather than a tidy test failure.
        String otherOwner = OTHER_OWNER;
        transactions.fund(OWNER, Money.parse("100.00", GBP));
        transactions.fund(otherOwner, Money.parse("100.00", GBP));
        facade.createPayment(
                OWNER, shared, fingerprintOf(paymentRequest("10.00")), paymentRequest("10.00"), FRAUD_CONTEXT);

        CreateTransactionRequest request = paymentRequest("70.00");
        PaymentFacade.Result result =
                facade.createPayment(otherOwner, shared, fingerprintOf(request), request, FRAUD_CONTEXT);

        assertThat(result)
                .as("the constraint is on (owner, key), so a different owner is a different key space")
                .isInstanceOf(PaymentFacade.Result.Answer.class);
        assertThat(((PaymentFacade.Result.Answer) result).replayed()).isFalse();
        assertThat(transactionRows.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a missing key is refused before anything is written")
    void missingKeyIsRefused() {
        transactions.fund(OWNER, Money.parse("100.00", GBP));
        int postingsBefore = entries.findAllWithLines().size();
        CreateTransactionRequest request = paymentRequest("10.00");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> facade.createPayment(OWNER, null, fingerprintOf(request), request, FRAUD_CONTEXT))
                .isInstanceOf(com.fintech.platform.common.error.ApiException.class);

        assertThat(transactionRows.count())
                .as("generating a key for a caller who omitted one would let a timeout retry pay twice, "
                        + "and the API would have caused it")
                .isZero();
        assertThat(entries.findAllWithLines())
                .as("no hold and no release: only the funding entry from setUp, unchanged")
                .hasSize(postingsBefore);
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * The fraud context these tests carry.
     *
     * <p>Empty on purpose. This file is about idempotency, and an empty context is the case that proves
     * the fraud plumbing does not change idempotency's behaviour: a merchant server integration makes no
     * device and observes no client network, and a payment that scores with three rules instead of seven
     * is still the same payment as far as replay is concerned. The rules that read these digests are
     * exercised in fraud-service, where the digests are the subject under test rather than an aside.
     */
    private static final com.fintech.platform.transaction.domain.FraudContext FRAUD_CONTEXT =
            com.fintech.platform.transaction.domain.FraudContext.empty();

    private CreateTransactionRequest paymentRequest(String amount) {
        // A fixed channel and fingerprint rather than null: they are part of the canonical fingerprint
        // now, so leaving them null would make this helper's requests differ from the ones a real client
        // sends for reasons that have nothing to do with the assertion under test.
        return new CreateTransactionRequest(amount, "GBP", CARD_TOKEN, "Coffee", "ref-1", "WEB", "device-abc");
    }

    private String fingerprintOf(CreateTransactionRequest request) {
        return IdempotencyService.fingerprint(request.canonicalFingerprint("POST", "/api/v1/transactions"));
    }

    private String storedBodyFor(String key) {
        return jdbc.queryForObject(
                "SELECT response_body FROM idempotency_keys WHERE owner_subject_digest = ? AND idempotency_key = ?",
                String.class,
                OWNER,
                key);
    }
}
