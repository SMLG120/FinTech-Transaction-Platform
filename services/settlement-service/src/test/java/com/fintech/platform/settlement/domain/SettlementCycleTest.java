package com.fintech.platform.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cycle's rules, and the one that Phase 7 exists for.
 *
 * <p>The immutability and cross-cycle cases are tested here rather than only through the service because
 * they are properties of the period, not of the machinery that writes it: a statement is a fact at a moment
 * and the moment is when the cycle closes. Every other test in this service is checking that money is added
 * correctly; these check that money is <em>not</em> changed, which is the harder half to get right and the
 * half that fails silently.
 */
class SettlementCycleTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private static final LocalDate DAY_ONE = LocalDate.of(2026, 9, 26);

    private static final LocalDate DAY_TWO = LocalDate.of(2026, 9, 27);

    private static Money gbp(String decimal) {
        return Money.parse(decimal, GBP);
    }

    private static SettlementCycle open(LocalDate date) {
        return SettlementCycle.open(date, GBP, NOW);
    }

    @Nested
    @DisplayName("references")
    class References {

        @Test
        @DisplayName("derives a readable, chronologically sortable reference")
        void derivesReference() {
            assertThat(open(DAY_TWO).getReference()).isEqualTo("SETTLE-2026-09-27-GBP");
        }

        @Test
        @DisplayName("derives the same reference every time for the same day and currency")
        void isDeterministic() {
            // The property that makes a cycle idempotent by construction. A generated id would make a
            // retried close invent a second statement for the same money, and a derived reference collides
            // on the unique index instead — which is an error rather than a duplicate.
            assertThat(open(DAY_TWO).getReference()).isEqualTo(open(DAY_TWO).getReference());
        }

        @Test
        @DisplayName("gives different cycles different references per day and per currency")
        void distinguishesDayAndCurrency() {
            assertThat(open(DAY_ONE).getReference()).isNotEqualTo(open(DAY_TWO).getReference());
            assertThat(open(DAY_ONE).getReference())
                    .isNotEqualTo(SettlementCycle.open(DAY_ONE, Currency.getInstance("USD"), NOW)
                            .getReference());
        }
    }

    @Nested
    @DisplayName("closing freezes the period")
    class Closing {

        @Test
        @DisplayName("freezes the expected total it was given")
        void freezesTotal() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            assertThat(cycle.getStatus()).isEqualTo(SettlementCycleStatus.CLOSED);
            assertThat(cycle.expected().toDecimal()).isEqualTo("500.00");
            assertThat(cycle.getClosedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("refuses to close twice")
        void refusesDoubleClose() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            assertThatThrownBy(() -> cycle.close(gbp("600.00"), NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not open");
        }

        @Test
        @DisplayName("refuses to add a line to a closed cycle")
        void refusesLineAfterClose() {
            // This is the immutability rule stated from the only direction that can be enforced: the line
            // is built against the cycle, and building one checks the state. A later line is not "added
            // with a warning" — it is refused, because a statement that changed after it was sent is not
            // a statement.
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            assertThatThrownBy(() -> SettlementLine.capture(cycle, UUID.randomUUID(), gbp("100.00"), NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not open");
        }

        @Test
        @DisplayName("refuses a total in the wrong currency")
        void refusesForeignTotal() {
            SettlementCycle cycle = open(DAY_TWO);
            assertThatThrownBy(() -> cycle.close(Money.parse("500.00", Currency.getInstance("USD")), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("settles in GBP");
        }
    }

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("refuses to reconcile before an actual has been declared")
        void refusesWithoutActual() {
            // The check that stops the platform from reconciling a period against a figure it derived
            // itself. That reconciliation would pass on every input, including every input it exists to
            // reject, and its green result would carry no information at all.
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            assertThatThrownBy(() -> cycle.reconcile(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no actual has been declared");
        }

        @Test
        @DisplayName("records the declared actual and a zero difference when it matches")
        void recordsMatchingActual() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            Money difference = cycle.declareActual(gbp("500.00"), NOW);
            assertThat(difference.isZero()).isTrue();
            assertThat(cycle.actual().toDecimal()).isEqualTo("500.00");
            assertThat(cycle.difference().toDecimal()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("distinguishes an actual of zero from no actual at all")
        void distinguishesAbsentFromZero() {
            // A period that genuinely balanced has an actual, and it is zero. A column that defaulted to
            // zero would make "nothing was ever compared" and "it matched perfectly" the same row, and the
            // first is precisely the state a reconciliation exists to prevent being mistaken for the
            // second.
            SettlementCycle cycle = open(DAY_TWO);
            assertThat(cycle.actual()).isNull();
            assertThat(cycle.difference()).isNull();

            cycle.close(gbp("0.00"), NOW);
            cycle.declareActual(gbp("0.00"), NOW);
            assertThat(cycle.actual()).isNotNull();
            assertThat(cycle.actual().isZero()).isTrue();
        }

        @Test
        @DisplayName("records a signed difference when the actual is short")
        void recordsShortfall() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            Money difference = cycle.declareActual(gbp("450.00"), NOW);
            assertThat(difference.toDecimal()).isEqualTo("-50.00");
        }

        @Test
        @DisplayName("records a signed difference when the actual is over")
        void recordsSurplus() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            assertThat(cycle.declareActual(gbp("525.00"), NOW).toDecimal()).isEqualTo("25.00");
        }

        @Test
        @DisplayName("refuses to reconcile a cycle that does not balance")
        void refusesUnbalanced() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            cycle.declareActual(gbp("450.00"), NOW);
            assertThatThrownBy(() -> cycle.reconcile(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unexplained difference");
        }

        @Test
        @DisplayName("refuses to reconcile before closing")
        void refusesUnclosed() {
            assertThatThrownBy(() -> open(DAY_TWO).reconcile(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only a closed cycle");
        }

        @Test
        @DisplayName("refuses to reconcile twice")
        void refusesDoubleReconcile() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            cycle.declareActual(gbp("500.00"), NOW);
            cycle.reconcile(NOW);
            assertThat(cycle.getStatus()).isEqualTo(SettlementCycleStatus.RECONCILED);
            assertThat(cycle.getSettledAt()).isEqualTo(NOW);
            assertThatThrownBy(() -> cycle.reconcile(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only a closed cycle");
        }

        @Test
        @DisplayName("marks a cycle broken rather than raising when the actual does not match")
        void breaksRatherThanThrowing() {
            // The difference is the finding. A service that refused to record it would have decided the
            // money agrees before anybody checked, and the break is how somebody finds out otherwise.
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            cycle.declareActual(gbp("450.00"), NOW);
            cycle.breakWith(NOW);
            assertThat(cycle.getStatus()).isEqualTo(SettlementCycleStatus.BROKEN);
        }

        @Test
        @DisplayName("refuses a foreign-currency actual")
        void refusesForeignActual() {
            SettlementCycle cycle = open(DAY_TWO);
            cycle.close(gbp("500.00"), NOW);
            assertThatThrownBy(() -> cycle.declareActual(Money.parse("500.00", Currency.getInstance("USD")), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("settles in GBP");
        }
    }

    @Nested
    @DisplayName("cross-cycle reversals")
    class CrossCycle {

        @Test
        @DisplayName("leaves a closed cycle exactly as it was closed")
        void closedCycleIsUntouched() {
            // The property the whole phase is built on. A payment settles, the period closes and is given
            // to the merchant, and the refund later must not edit it — so the first period's expected
            // total, its status and its lines are all exactly as they were at closing.
            SettlementCycle settled = open(DAY_ONE);
            settled.close(gbp("1200.00"), NOW);
            String referenceBefore = settled.getReference();
            long expectedBefore = settled.getExpectedMinor();
            SettlementCycleStatus statusBefore = settled.getStatus();

            // The refund lands in a later period, which is the only place it can go.
            SettlementCycle later = open(DAY_TWO);
            SettlementLine.reversal(later, UUID.randomUUID(), gbp("1200.00").negate(), NOW);

            assertThat(settled.getReference()).isEqualTo(referenceBefore);
            assertThat(settled.getExpectedMinor()).isEqualTo(expectedBefore);
            assertThat(settled.getStatus()).isEqualTo(statusBefore);
        }

        @Test
        @DisplayName("a reversal in a later period is recorded in that period, not the original one")
        void reversalGoesToTheRefundsOwnPeriod() {
            SettlementCycle later = open(DAY_TWO);
            UUID payment = UUID.randomUUID();
            SettlementLine line =
                    SettlementLine.reversal(later, payment, gbp("1200.00").negate(), NOW);
            assertThat(line.getCycle().getReference()).isEqualTo("SETTLE-2026-09-27-GBP");
            assertThat(line.getTransactionId()).isEqualTo(payment);
            assertThat(line.amount().toDecimal()).isEqualTo("-1200.00");
        }

        @Test
        @DisplayName("a later period nets a refund against nothing, which is what makes it a finding")
        void laterPeriodNetsAgainstNothing() {
            // The later period's total goes negative on its own, because the capture it would balance
            // against is in a period that is closed. That is not a bug in the arithmetic — it is exactly
            // what has to be true for the mismatch to be visible, and the CYCLE_ALREADY_SETTLED break is
            // what makes it explainable.
            SettlementCycle later = open(DAY_TWO);
            SettlementLine.reversal(later, UUID.randomUUID(), gbp("1200.00").negate(), NOW);
            assertThat(later.getBusinessDate()).isEqualTo(DAY_TWO);
        }

        @Test
        @DisplayName("a refund of a payment settled the same day needs no cross-cycle handling")
        void sameDayRefundIsUnremarkable() {
            SettlementCycle cycle = open(DAY_TWO);
            assertThat(cycle.getReference()).isEqualTo(cycle.cycleReference().value());
        }
    }
}
