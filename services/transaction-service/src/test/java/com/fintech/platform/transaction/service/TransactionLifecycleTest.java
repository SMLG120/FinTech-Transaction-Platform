package com.fintech.platform.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.transaction.domain.Counterparty;
import com.fintech.platform.transaction.domain.FraudContext;
import com.fintech.platform.transaction.domain.LedgerAccountType;
import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.OutboxEvent;
import com.fintech.platform.transaction.domain.PlatformAccount;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.domain.TransactionStatus;
import com.fintech.platform.transaction.persistence.OutboxRepository;
import com.fintech.platform.transaction.persistence.TransactionRepository;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The payment lifecycle against a real database, with no broker.
 *
 * <p>These are the assertions that only a real transaction manager can support, because each one depends
 * on what the database has actually been told: the order of version numbers, the event written beside a
 * state change, and a decline that survives the transaction it happened in.
 */
@Testcontainers
@SpringBootTest
class TransactionLifecycleTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    /** Platform accounts are all owned by this one reference, not by a customer digest. */
    private static final String PLATFORM_OWNER_REF = PlatformAccount.OWNER_REF;
    /** A subject digest: 64 hex characters, which is what SubjectDigester produces and the column holds. */
    private static final String OWNER = "3f7a1c9e2b4d8065a1f3c7e9b2d40658a1c3e5f7092b4d6813a7c9e2b5d8f046";
    /**
     * An opaque card token at exactly the 64-character column limit.
     *
     * <p>Nothing here can become a card number, which is the point: the service stores an opaque string it
     * cannot reverse, and {@code "tok_"} marks it as one. See ADR-0006.
     */
    private static final String CARD_TOKEN = "tok_" + "0123456789abcdef".repeat(3) + "0123456789ab";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_transactions_lifecycle");

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
    private TransactionService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE journal_lines, journal_entries, outbox_events, "
                + "idempotency_keys, transactions, ledger_accounts CASCADE");
    }

    @Test
    @DisplayName("every event records the version it describes, in ascending order")
    void eventVersionsAscend() {
        // The regression this pins. `version` is a JPA @Version, incremented on flush and not on
        // mutation, so reading it before the flush reported the *previous* value. Both the creation and
        // the authorisation then recorded version 0, and a consumer applying the documented rule — "I have
        // seen version N, so N again is a redelivery" — discarded the authorisation as a duplicate. No
        // exception, no log line, and a consumer that had silently stopped applying payments.
        // Funded first: an account that does not exist is refused rather than declined, so these
        // lifecycle tests need a balance to be working through.
        service.fund(OWNER, Money.parse("100.00", GBP));
        Transaction payment =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("40.00", GBP), payee("Coffee"), FraudContext.empty());
        service.settle(payment.id(), OWNER);
        service.reverse(payment.id(), OWNER);

        List<OutboxEvent> events = eventsFor(payment.id());

        assertThat(events)
                .extracting(OutboxEvent::eventType)
                .as("the full lifecycle, in the order it happened")
                .containsExactly(
                        "transaction.created", "transaction.authorized", "transaction.settled", "transaction.reversed");
        assertThat(events)
                .extracting(OutboxEvent::aggregateVersion)
                .as("strictly increasing, so a consumer can order and deduplicate on this")
                .containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    @DisplayName("the version in the payload is the version on the outbox row")
    void payloadAndOutboxAgreeOnVersion() {
        // Two reads of the same field moments apart. If they could disagree, a consumer would deduplicate
        // on one number and act on another, and the row would be no use as an audit of what was sent.
        service.fund(OWNER, Money.parse("100.00", GBP));
        Transaction payment =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("12.34", GBP), payee("Books"), FraudContext.empty());
        service.settle(payment.id(), OWNER);

        for (OutboxEvent event : eventsFor(payment.id())) {
            assertThat(payloadVersion(event))
                    .as("%s carries the version of the row it was recorded with", event.eventType())
                    .isEqualTo(event.aggregateVersion());
        }
    }

    @Test
    @DisplayName("an account that was never funded refuses the payment rather than declining it")
    void unfundedCustomerIsRefusedNotDeclined() {
        // LedgerService.fund documents this choice: a hold against an account that does not exist says so,
        // instead of creating an empty one and declining on a zero balance. The two read as different
        // problems to whoever is told why the payment failed, and conflating them would also mean a payment
        // attempt never produced a row to look up afterwards.
        //
        // So a customer who has never been funded gets an ACCOUNT_NOT_FOUND, not a DECLINED row — and
        // nothing is written, because a refusal that created a declined row would have quietly become the
        // behaviour the code deliberately avoids.
        assertThatThrownBy(() -> service.authorize(
                        OWNER, CARD_TOKEN, Money.parse("40.00", GBP), payee("Coffee"), FraudContext.empty()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).getMessage()).contains("CUSTOMER_AVAILABLE"));

        assertThat(transactions.count())
                .as("a refused attempt against a nonexistent account leaves no payment to find")
                .isZero();
    }

    @Test
    @DisplayName("a decline is committed with its reason, not rolled away")
    void declineIsCommitted() {
        // Funded, but not enough. A decline is a real outcome a merchant needs to see, and the only record
        // that the customer tried is the row — so throwing here would erase it.
        service.fund(OWNER, Money.parse("10.00", GBP));
        Transaction payment =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("40.00", GBP), payee("Coffee"), FraudContext.empty());

        assertThat(payment.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(payment.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");

        Transaction reloaded = transactions.findById(payment.id()).orElseThrow();
        assertThat(reloaded.status())
                .as("the row survived the transaction it was declined in")
                .isEqualTo(TransactionStatus.DECLINED);

        assertThat(eventsFor(payment.id()))
                .extracting(OutboxEvent::eventType)
                .as("and the merchant is told, rather than left to notice a missing payment")
                .containsExactly("transaction.created", "transaction.declined");
        assertThat(declineReason(declinedEvent(payment.id()))).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("a declined payment holds no money")
    void declineLeavesBalancesUnmoved() {
        // The declined path must not have half-moved the funds. A decline that left a hold behind would
        // show the customer money reserved for a payment that never happened, and the balance would only
        // look wrong to them.
        service.fund(OWNER, Money.parse("10.00", GBP));
        Transaction payment =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("40.00", GBP), payee("Coffee"), FraudContext.empty());

        assertThat(payment.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(balance(LedgerAccountType.CUSTOMER_AVAILABLE))
                .as("the whole deposit is still spendable, not partly consumed by a refused hold")
                .isEqualTo(Money.parse("10.00", GBP));
        assertThat(balance(LedgerAccountType.CUSTOMER_RESERVED)).isEqualTo(Money.parse("0.00", GBP));
    }

    @Test
    @DisplayName("a settled payment moves the money from held to spent")
    void settleMovesTheMoney() {
        service.fund(OWNER, Money.parse("100.00", GBP));
        Transaction payment =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("40.00", GBP), payee("Coffee"), FraudContext.empty());

        assertThat(balance(LedgerAccountType.CUSTOMER_AVAILABLE)).isEqualTo(Money.parse("60.00", GBP));
        assertThat(balance(LedgerAccountType.CUSTOMER_RESERVED)).isEqualTo(Money.parse("40.00", GBP));

        service.settle(payment.id(), OWNER);

        assertThat(balance(LedgerAccountType.CUSTOMER_AVAILABLE))
                .as("the hold is gone, not duplicated")
                .isEqualTo(Money.parse("60.00", GBP));
        assertThat(balance(LedgerAccountType.CUSTOMER_RESERVED)).isEqualTo(Money.parse("0.00", GBP));
        assertThat(platformBalance(LedgerAccountType.PLATFORM_CLEARING))
                .as("the captured money is in clearing; reached by query because the customer map omits it")
                .isEqualTo(Money.parse("40.00", GBP));
    }

    @Test
    @DisplayName("reversing a settlement returns the money to spendable")
    void reverseSettlementRefunds() {
        // The case that needs both postings reversed. Releasing only the capture would leave the funds in
        // clearing and the customer unable to spend money they were refunded — which looks correct in
        // every total except the one the customer checks.
        // Funded first: an account that does not exist is refused rather than declined, so these
        // lifecycle tests need a balance to be working through.
        service.fund(OWNER, Money.parse("100.00", GBP));
        Transaction payment =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("40.00", GBP), payee("Coffee"), FraudContext.empty());
        service.settle(payment.id(), OWNER);
        service.reverse(payment.id(), OWNER);

        assertThat(transactions.findById(payment.id()).orElseThrow().status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(balance(LedgerAccountType.CUSTOMER_AVAILABLE)).isEqualTo(Money.parse("100.00", GBP));
        assertThat(balance(LedgerAccountType.CUSTOMER_RESERVED)).isEqualTo(Money.parse("0.00", GBP));
        assertThat(platformBalance(LedgerAccountType.PLATFORM_CLEARING))
                .as("clearing is empty again, so the refund did not leave funds stranded there")
                .isEqualTo(Money.parse("0.00", GBP));
    }

    @Test
    @DisplayName("the customer balances map carries no platform accounts")
    void customerBalancesDoNotExposePlatformMoney() {
        // The customer-facing map is built from LedgerAccountType.customerTypes() alone. A platform balance
        // leaking into it would put the platform's total position in a response addressed to one customer,
        // and it is the reason the platform figures in these tests have to be read straight from the table.
        service.fund(OWNER, Money.parse("100.00", GBP));

        assertThat(service.balances(OWNER, GBP))
                .as("spendable and held, and nothing else")
                .containsOnlyKeys(LedgerAccountType.CUSTOMER_AVAILABLE, LedgerAccountType.CUSTOMER_RESERVED);
    }

    @Test
    @DisplayName("one payment's events are all keyed to that payment")
    void eventsAreKeyedByTransaction() {
        // The partition key is what keeps a consumer's view of one payment in order. Keyed by topic or by
        // customer instead, a settlement could reach a consumer before the authorisation that caused it.
        service.fund(OWNER, Money.parse("100.00", GBP));
        Transaction first =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("10.00", GBP), payee("One"), FraudContext.empty());
        Transaction second =
                service.authorize(OWNER, CARD_TOKEN, Money.parse("20.00", GBP), payee("Two"), FraudContext.empty());

        assertThat(eventsFor(first.id()))
                .isNotEmpty()
                .allSatisfy(event ->
                        assertThat(event.eventKey()).isEqualTo(first.id().toString()));
        assertThat(eventsFor(second.id()))
                .isNotEmpty()
                .allSatisfy(event ->
                        assertThat(event.eventKey()).isEqualTo(second.id().toString()));
    }

    // ---------------------------------------------------------------------------- helpers

    private OutboxEvent declinedEvent(UUID transactionId) {
        return eventsFor(transactionId).stream()
                .filter(event -> event.eventType().equals("transaction.declined"))
                .findFirst()
                .orElseThrow();
    }

    private List<OutboxEvent> eventsFor(UUID transactionId) {
        return outbox.findByAggregateIdOrderByAggregateVersionAsc(transactionId);
    }

    private long payloadVersion(OutboxEvent event) {
        return body(event).path("version").asLong();
    }

    private String declineReason(OutboxEvent event) {
        return body(event).path("declineReason").asText();
    }

    /** The event body, which the writer nests under the envelope's own "payload" field. */
    private JsonNode body(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.payload()).path("payload");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored payload is not JSON, which cannot happen: " + event.eventType(), e);
        }
    }

    /**
     * A customer balance, through the same method the API uses.
     *
     * <p>Only ever a customer account type: {@code balancesFor} builds its map from
     * {@code LedgerAccountType.customerTypes()}, so a platform account is not in it at all. That is the
     * behaviour asserted in {@link #customerBalancesDoNotExposePlatformMoney}, not an accident of this
     * helper.
     */
    private Money balance(LedgerAccountType accountType) {
        return service.balances(OWNER, GBP).get(accountType);
    }

    /** A platform balance, which the customer-facing API deliberately does not expose. */
    private Money platformBalance(LedgerAccountType accountType) {
        Long minor = jdbc.queryForObject(
                "SELECT balance_minor FROM ledger_accounts WHERE owner_ref = ? AND type = ? AND currency_code = ?",
                Long.class,
                PLATFORM_OWNER_REF,
                accountType.name(),
                GBP.getCurrencyCode());
        return Money.minor(minor == null ? 0L : minor, GBP);
    }

    private Counterparty payee(String name) {
        return new Counterparty(name, "ref-" + name.toLowerCase());
    }
}
