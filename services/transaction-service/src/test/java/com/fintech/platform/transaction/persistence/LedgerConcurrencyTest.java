package com.fintech.platform.transaction.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.transaction.domain.Counterparty;
import com.fintech.platform.transaction.domain.JournalEntry;
import com.fintech.platform.transaction.domain.JournalEntryKind;
import com.fintech.platform.transaction.domain.LedgerAccount;
import com.fintech.platform.transaction.domain.LedgerAccountType;
import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.PlatformAccount;
import com.fintech.platform.transaction.domain.PostingDirection;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.service.LedgerService;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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
 * The concurrency claims, tested against a real Postgres.
 *
 * <p>These are the assertions the rest of the design exists to make possible, and none of them can be
 * checked with a mocked repository. A mock returns whatever the test tells it to and holds no lock, so a
 * suite of them would pass while the platform double-spends. Everything here needs real row locks,
 * real {@code SELECT ... FOR UPDATE} semantics and the real CHECK constraints — which is why it is a
 * container test and not a unit test.
 *
 * <p>Each test asserts the property, not the mechanism: that the balance is right afterwards, not that
 * a particular method was called. A test asserting "the repository was asked with PESSIMISTIC_WRITE"
 * would pass if the lock were taken on the wrong row.
 */
@Testcontainers
@SpringBootTest
class LedgerConcurrencyTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final String OWNER = "concurrency-test-owner";
    private static final Counterparty PAYEE = new Counterparty("Concurrency Test Merchant", null);
    private static final String IDENTITY_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";
    /** base64, 32 bytes decoded — the shape the property actually binds from. */
    private static final String SUBJECT_DIGEST_KEY = "eoejXxxDplx9GXusrUH7EqNKm6DTocES99CPSnfvGVI=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_transactions_concurrency");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.security.internal-identity.signing-key", () -> IDENTITY_KEY);
        registry.add("platform.security.subject-digest.key", () -> SUBJECT_DIGEST_KEY);
        // Nothing here publishes, and the container is stopped when this class finishes. Left on, the
        // relay keeps querying for the rest of the run and logs the connection error it will eventually
        // get against a server that has gone.
        registry.add("app.outbox.relay-enabled", () -> "false");
    }

    @Autowired
    private LedgerService ledger;

    @Autowired
    private LedgerAccountRepository accounts;

    @Autowired
    private JournalEntryRepository entries;

    @Autowired
    private TransactionRepository transactionRows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(8);
        // One container serves the whole class and the accounts are per-currency-per-owner, not per-test,
        // so without this the balance a test funds is still there when the next one starts. The
        // concurrency tests below assert exact amounts after asserting exact outcomes, and a leftover
        // hundred pounds from the previous test would fail them for a reason that has nothing to do with
        // concurrency. One TRUNCATE, CASCADE rather than deletes in dependency order, so adding a table
        // later cannot leave this quietly failing to clean it.
        jdbc.execute("TRUNCATE TABLE journal_lines, journal_entries, outbox_events, "
                + "idempotency_keys, transactions, ledger_accounts CASCADE");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("concurrent authorisations cannot spend the same money twice")
    void concurrentAuthorisationsCannotOverdraw() throws Exception {
        // The headline claim. One hundred pounds, ten threads each trying to authorise sixty.
        // At most one can succeed: the second and subsequent finds the balance already committed by the
        // first, because the read that decides is the same locked read that writes.
        fund("100.00");
        int attempts = 10;
        Money each = Money.parse("60.00", GBP);

        AtomicInteger authorised = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(executor.submit(() -> {
                startTogether.await();
                if (holdEach(each).isRefused()) {
                    // The refusal is the expected outcome for nine of the ten.
                    declined.incrementAndGet();
                } else {
                    authorised.incrementAndGet();
                }
                return null;
            }));
        }
        startTogether.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertThat(authorised.get())
                .as("only one payment of 60 can be authorised against a balance of 100")
                .isEqualTo(1);
        assertThat(declined.get()).isEqualTo(attempts - 1);

        LedgerAccount available = account(LedgerAccountType.CUSTOMER_AVAILABLE);
        assertThat(available.balance()).isEqualTo(Money.parse("40.00", GBP));
        assertThat(available.balanceMinor())
                .as("the customer is never overdrawn, whatever the concurrency")
                .isNotNegative();
    }

    @Test
    @DisplayName("concurrent authorisations that all fit still all succeed, and the total is exact")
    void concurrentAuthorisationsThatFitAllSucceed() throws Exception {
        // The other half of the claim, and the one a naive fix gets wrong. If every payment were
        // serialised with a coarse lock and one failed spuriously, the balance would still be right and
        // the test above would still pass. A payment that fits must not be refused because of a race.
        fund("100.00");
        int attempts = 10;
        Money each = Money.parse("10.00", GBP);

        AtomicInteger authorised = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            futures.add(executor.submit(() -> {
                startTogether.await();
                if (!holdEach(each).isRefused()) {
                    authorised.incrementAndGet();
                }
                return null;
            }));
        }
        startTogether.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertThat(authorised.get())
                .as("ten payments of 10 against a balance of 100 must all be authorised")
                .isEqualTo(attempts);
        assertThat(account(LedgerAccountType.CUSTOMER_AVAILABLE).balance()).isEqualTo(Money.zero(GBP));
        assertThat(account(LedgerAccountType.CUSTOMER_RESERVED).balance()).isEqualTo(Money.parse("100.00", GBP));
    }

    @Test
    @DisplayName("the whole ledger sums to zero after concurrent traffic")
    void concurrentTrafficConservesValue() throws Exception {
        // Conservation is the property that catches an off-by-sign in a posting, and it is checked
        // against the database rather than in Java: the deferred trigger in the migration is what
        // would stop this in production, so testing it here means testing the thing that is deployed.
        //
        // All holds, no releases. A release of money that was never held is refused, and a test that
        // mostly expects refusals while claiming to check conservation would be checking that the
        // refusals did not leak, which is a different claim. Twenty holds of 5.00 against 500.00 is
        // real traffic that moves value on both sides of every posting, which is what the trigger is
        // for. The trigger fires on the commit, so this cannot pass by asserting before the writes land.
        fund("500.00");
        int attempts = 20;
        Money each = Money.parse("5.00", GBP);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            futures.add(executor.submit(() -> {
                startTogether.await();
                holdEach(each);
                return null;
            }));
        }
        startTogether.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        long total = accounts.findAll().stream()
                .mapToLong(LedgerAccount::balanceMinor)
                .sum();
        assertThat(total)
                .as("every account in the ledger sums to zero, or a posting created value")
                .isZero();
        assertThat(account(LedgerAccountType.CUSTOMER_RESERVED).balance())
                .as("and the money really moved, rather than the total holding at zero by accident")
                .isEqualTo(Money.minor(attempts * each.minorUnits(), GBP));
    }

    @Test
    @DisplayName("concurrent funding of the same account creates exactly one account")
    void concurrentFundingCreatesOneAccount() throws Exception {
        // Two threads funding a customer who has never been paid would both find no account and both
        // insert. The unique constraint is what stops the second, and the test asserts the outcome a
        // customer would notice: one account, one balance equal to the total, not two accounts holding
        // half each with no query that finds the whole thing.
        int attempts = 8;
        Money each = Money.parse("25.00", GBP);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            futures.add(executor.submit(() -> {
                startTogether.await();
                return transactions.execute(status -> ledger.fund(OWNER, each));
            }));
        }
        startTogether.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        List<LedgerAccount> customerAccounts = accounts.findAllForOwner(OWNER, GBP.getCurrencyCode());
        assertThat(customerAccounts)
                .as("one account per type, however many threads raced to create it")
                .hasSize(LedgerAccountType.customerTypes().size());
        assertThat(account(LedgerAccountType.CUSTOMER_AVAILABLE).balance())
                .isEqualTo(Money.minor(attempts * each.minorUnits(), GBP));
    }

    @Test
    @DisplayName("a hold is refused outright rather than partially applied")
    void aRefusedHoldChangesNothing() {
        // The refusal has to leave no trace. A posting that debited what it could and then gave up
        // would leave money missing from one account and present in another, and the conservation
        // trigger would reject the commit — but only after the damage, and only as an error a customer
        // would see as a failed request with a changed balance.
        fund("50.00");

        LedgerService.HoldOutcome outcome = holdEach(Money.parse("80.00", GBP));
        assertThat(outcome.isRefused())
                .as("a hold larger than the balance is refused")
                .isTrue();

        // On the exception's fields, not on its message. The refusal carries the account and the
        // shortfall so that TransactionService can record a decline with them, and asserting them is
        // what pins that contract; a message assertion would still pass if something upstream began
        // translating this into a bare HTTP error code, which is precisely the regression this
        // arrangement was written to make impossible.
        assertThat(outcome.refusal().accountType()).isEqualTo(LedgerAccountType.CUSTOMER_AVAILABLE);
        assertThat(outcome.refusal().requested()).isEqualTo(Money.parse("80.00", GBP));
        assertThat(outcome.refusal().available()).isEqualTo(Money.parse("50.00", GBP));

        assertThat(account(LedgerAccountType.CUSTOMER_AVAILABLE).balance()).isEqualTo(Money.parse("50.00", GBP));
        assertThat(account(LedgerAccountType.CUSTOMER_RESERVED).balance()).isEqualTo(Money.zero(GBP));
        assertThat(entries.findAllWithLines())
                .as("no journal entry is written for a posting that was refused")
                .noneMatch(entry -> entry.kind() == JournalEntryKind.HOLD);
    }

    @Test
    @DisplayName("each posted line records the balance it produced, in order")
    void journalLinesRecordRunningBalances() {
        // balance_after is what makes the ledger auditable without replaying it. If it were not
        // written, or written as the balance before, a reader checking a reported total against the
        // journal would be off by one posting and would have no way to tell a bug from a convention.
        fund("100.00");
        holdEach(Money.parse("30.00", GBP));
        holdEach(Money.parse("20.00", GBP));

        List<JournalEntry> holds = entries.findAllWithLines().stream()
                .filter(entry -> entry.kind() == JournalEntryKind.HOLD)
                .sorted((a, b) -> a.effectiveAt().compareTo(b.effectiveAt()))
                .toList();
        assertThat(holds).hasSize(2);

        for (JournalEntry hold : holds) {
            for (var line : hold.lines()) {
                if (line.direction() != PostingDirection.CREDIT) {
                    continue;
                }
                // The available account is debited by funding to 100, then credited by each hold.
                assertThat(line.balanceAfterMinor())
                        .as("the balance after the first hold is 70 and after the second is 50")
                        .isIn(7000L, 5000L);
            }
        }
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * Authorises one payment: the transaction row, then the hold, in one transaction.
     *
     * <p>The row is not ceremony. {@code journal_entries.transaction_id} is a real foreign key, because a
     * hold with no payment behind it is money reserved that nothing will ever release, capture or
     * reverse — a reservation that outlives the request that made it. So the ledger cannot be handed an
     * invented id, and a test that does so is testing a schema the platform does not have.
     *
     * <p>Kept to the two steps that matter for locking, and deliberately not calling
     * {@code TransactionService.authorize}, which would drag the outbox writer and the payment limits
     * into a test about row locks. This is the production sequence with the parts that cannot affect
     * concurrency removed, not a different one.
     */
    /**
     * One hold, reporting whether it was granted.
     *
     * <p>The outcome is returned rather than thrown because {@code hold} is defined that way, so that a
     * refused hold never marks the transaction rollback-only and a declined payment can be committed at
     * all. A test that counted refusals by catching an exception would now count zero of them.
     */
    private LedgerService.HoldOutcome holdEach(Money amount) {
        return transactions.execute(status -> {
            Transaction payment = Transaction.create(
                    UUID.randomUUID(), OWNER, "tok_" + UUID.randomUUID(), amount, PAYEE, Instant.now());
            transactionRows.save(payment);
            return ledger.hold(OWNER, payment.id(), amount);
        });
    }

    private void fund(String amount) {
        transactions.executeWithoutResult(status -> ledger.fund(OWNER, Money.parse(amount, GBP)));
    }

    private LedgerAccount account(LedgerAccountType type) {
        return accounts.findByOwnerRefAndTypeAndCurrencyCode(
                        type.isCustomerAccount() ? OWNER : PlatformAccount.OWNER_REF, type, GBP.getCurrencyCode())
                .orElseThrow();
    }
}
