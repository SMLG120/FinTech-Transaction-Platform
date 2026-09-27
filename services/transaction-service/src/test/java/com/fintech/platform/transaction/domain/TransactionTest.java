package com.fintech.platform.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment's own rules: what a valid amount is, and which status moves are legal.
 *
 * <p>The transition table gets a table-driven test rather than one assertion per edge, because the
 * table is the thing being tested and a per-edge test would restate it in a second place that can
 * disagree.
 */
class TransactionTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30Z");

    private static Transaction pending() {
        return Transaction.create(
                UUID.randomUUID(),
                "owner-digest",
                "a".repeat(64),
                Money.parse("40.00", GBP),
                new Counterparty("Coffee House", "ORDER-1"),
                NOW);
    }

    @Test
    @DisplayName("a new payment starts pending, with the amount and card token it was created with")
    void createdPending() {
        Transaction transaction = pending();

        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transaction.amount()).isEqualTo(Money.parse("40.00", GBP));
        assertThat(transaction.currency()).isEqualTo(GBP);
        assertThat(transaction.declineReason()).isNull();
        assertThat(transaction.authorizedAt()).isNull();
        assertThat(transaction.settledAt()).isNull();
        assertThat(transaction.reversedAt()).isNull();
        assertThat(transaction.cardToken()).isEqualTo("a".repeat(64)).doesNotContain(" ");
    }

    @Test
    @DisplayName("the amount, payee, card token and payer are fixed for the payment's whole life")
    void identityIsImmutable() {
        // Asserted by walking the payment through its whole life and re-reading, rather than by
        // reflecting over the class to prove no setter exists. A setter could be added without this
        // test noticing if the name check missed it — a mutator called `mutate` or `record` would slip
        // past a prefix match — whereas the property that actually matters is that a payment which was
        // authorised for £40 is still a £40 payment after it settles and after it is refunded.
        Transaction transaction = pending();
        Money amount = transaction.amount();
        String token = transaction.cardToken();
        String owner = transaction.ownerSubjectDigest();
        Counterparty payee = transaction.payee();

        transaction.authorize(NOW.plusSeconds(1)).settle(NOW.plusSeconds(30)).reverse(NOW.plusSeconds(86400));

        assertThat(transaction.amount())
                .as("the ledger reserved this amount; the receipt must show it")
                .isEqualTo(amount);
        assertThat(transaction.cardToken())
                .as("the token the payment was made with must not change as it progresses")
                .isEqualTo(token);
        assertThat(transaction.ownerSubjectDigest()).isEqualTo(owner);
        assertThat(transaction.payee()).isEqualTo(payee);
    }

    @Test
    @DisplayName("a zero or negative amount is refused at creation, not at posting time")
    void refusesNonPositiveAmount() {
        assertThatThrownBy(() -> Transaction.create(
                        UUID.randomUUID(), "digest", "a".repeat(64), Money.zero(GBP), new Counterparty("X", null), NOW))
                .hasMessageContaining("must be positive");

        // A negative payment has no representation: a posting leg's amount is always positive and the
        // sign lives in the direction, so a negative amount could not be posted even if it were
        // accepted here.
        assertThatThrownBy(() -> Transaction.create(
                        UUID.randomUUID(),
                        "digest",
                        "a".repeat(64),
                        Money.minor(-4000, GBP),
                        new Counterparty("X", null),
                        NOW))
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("a blank card token or payer is refused")
    void refusesBlankIdentity() {
        assertThatThrownBy(() -> Transaction.create(
                        UUID.randomUUID(), "digest", "   ", Money.parse("1.00", GBP), new Counterparty("X", null), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardToken");

        assertThatThrownBy(() -> Transaction.create(
                        UUID.randomUUID(),
                        " ",
                        "a".repeat(64),
                        Money.parse("1.00", GBP),
                        new Counterparty("X", null),
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerSubjectDigest");
    }

    @Test
    @DisplayName("authorise, then settle, then reverse, stamping each moment")
    void walksTheHappyPath() {
        Transaction transaction = pending();

        assertThat(transaction.authorize(NOW.plusSeconds(1))).isSameAs(transaction);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(transaction.authorizedAt()).isEqualTo(NOW.plusSeconds(1));

        assertThat(transaction.settle(NOW.plusSeconds(30))).isSameAs(transaction);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(transaction.settledAt()).isEqualTo(NOW.plusSeconds(30));

        assertThat(transaction.reverse(NOW.plusSeconds(86400))).isSameAs(transaction);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(transaction.reversedAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    @DisplayName("a decline needs a reason, and records it")
    void declineRequiresAReason() {
        Transaction transaction = pending().decline("INSUFFICIENT_FUNDS", NOW.plusSeconds(2));

        assertThat(transaction.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(transaction.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");

        assertThatThrownBy(() -> pending().decline("  ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decline reason");
    }

    @Test
    @DisplayName("re-authorising an authorised payment is refused, so a replay cannot reserve twice")
    void refusesDoubleAuthorisation() {
        Transaction transaction = pending().authorize(NOW);

        assertThatThrownBy(() -> transaction.authorize(NOW.plusSeconds(1)))
                .hasMessageContaining("AUTHORIZED")
                .hasMessageContaining("cannot become");
    }

    @Test
    @DisplayName("capturing a payment that was never authorised is refused")
    void refusesSettlingWithoutAuthorising() {
        assertThatThrownBy(() -> pending().settle(NOW))
                .hasMessageContaining("PENDING")
                .hasMessageContaining("cannot become SETTLED");
    }

    @Test
    @DisplayName("an authorised payment can be reversed, and the reversal is what the caller sees")
    void authorisedCanBeReversed() {
        // Voiding an authorisation is a real event: the merchant abandons the sale, or the hold expires
        // unreleased. Without this edge a hold could only be undone by capturing it, which would pay
        // out money for a sale that did not happen.
        Transaction transaction = pending().authorize(NOW).reverse(NOW.plusSeconds(60));

        assertThat(transaction.status()).isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    @DisplayName("a declined or reversed payment is terminal")
    void terminalStatesAreTerminal() {
        assertThat(TransactionStateMachine.isTerminal(TransactionStatus.DECLINED))
                .isTrue();
        assertThat(TransactionStateMachine.isTerminal(TransactionStatus.REVERSED))
                .isTrue();
        assertThat(TransactionStateMachine.isTerminal(TransactionStatus.PENDING))
                .isFalse();
        assertThat(TransactionStateMachine.isTerminal(TransactionStatus.AUTHORIZED))
                .isFalse();
        assertThat(TransactionStateMachine.isTerminal(TransactionStatus.SETTLED))
                .isFalse();
    }

    @Test
    @DisplayName("the transition table matches the mutators, with no status left undeclared")
    void transitionTableIsComplete() {
        // Every status in the enum must have a row, or it would look terminal merely by being
        // forgotten. Without declaredStatuses(), a status added with no edges would pass as
        // deliberately terminal and the guard above would be testing an accident.
        assertThat(TransactionStateMachine.declaredStatuses()).containsExactlyInAnyOrder(TransactionStatus.values());

        for (TransactionStatus from : TransactionStatus.values()) {
            for (TransactionStatus to : TransactionStatus.values()) {
                boolean reachable = mutate(from, to);
                assertThat(TransactionStateMachine.canTransition(from, to))
                        .as("%s -> %s must agree between the table and the aggregate", from, to)
                        .isEqualTo(reachable);
            }
        }
    }

    @Test
    @DisplayName("money is in play only while authorised or settled")
    void moneyInPlay() {
        assertThat(pending().hasMoneyInPlay()).isFalse();
        assertThat(pending().decline("X", NOW).hasMoneyInPlay()).isFalse();

        Transaction authorized = pending().authorize(NOW);
        assertThat(authorized.hasMoneyInPlay()).isTrue();
        assertThat(authorized.settle(NOW.plusSeconds(1)).hasMoneyInPlay()).isTrue();
        assertThat(authorized.reverse(NOW.plusSeconds(2)).hasMoneyInPlay()).isFalse();
    }

    /**
     * @return whether {@code from} can legally become {@code to}, by actually trying it
     */
    private static boolean mutate(TransactionStatus from, TransactionStatus to) {
        Transaction transaction = switch (from) {
            case PENDING -> pending();
            case AUTHORIZED -> pending().authorize(NOW);
            case DECLINED -> pending().decline("X", NOW);
            case SETTLED -> pending().authorize(NOW).settle(NOW.plusSeconds(1));
            case REVERSED -> pending().authorize(NOW).reverse(NOW.plusSeconds(1));
        };
        try {
            switch (to) {
                case AUTHORIZED -> transaction.authorize(NOW.plusSeconds(9));
                case DECLINED -> transaction.decline("X", NOW.plusSeconds(9));
                case SETTLED -> transaction.settle(NOW.plusSeconds(9));
                case REVERSED -> transaction.reverse(NOW.plusSeconds(9));
                case PENDING -> {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
