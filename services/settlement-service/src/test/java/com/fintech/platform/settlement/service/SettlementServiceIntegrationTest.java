package com.fintech.platform.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.settlement.domain.BreakKind;
import com.fintech.platform.settlement.domain.BreakStatus;
import com.fintech.platform.settlement.domain.Money;
import com.fintech.platform.settlement.domain.SettlementBreak;
import com.fintech.platform.settlement.domain.SettlementCycle;
import com.fintech.platform.settlement.domain.SettlementCycleStatus;
import com.fintech.platform.settlement.messaging.TransactionMovementEvent;
import com.fintech.platform.settlement.persistence.SettlementBreakRepository;
import com.fintech.platform.settlement.persistence.SettlementCycleRepository;
import com.fintech.platform.settlement.persistence.SettlementLineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The rules that only a database can settle, against real Postgres.
 *
 * <p>The domain tests already prove a closed cycle refuses a line in memory. That proof is worth little on
 * its own: an entity method is one way in, and the thing that actually decides whether a closed period can
 * be written to is the unique constraint underneath it. So the scenarios here go through the service, with
 * a real transaction and a real schema, and then read the rows back over JDBC rather than through the
 * repositories — because a JPA read can return a persistence-context copy that no longer matches what was
 * committed, and a test that trusts it would pass on a bug where nothing was written.
 *
 * <p>What is under test, specifically:
 *
 * <ul>
 *   <li>A late capture cannot write to a given-out period, and is recorded instead of thrown.
 *   <li>A refund whose capture day is still open is ordinary: it gets a line and no break.
 *   <li>A refund whose capture day has been given out gets a line <em>and</em> a finding, because the line
 *       nets against nothing in its own period.
 *   <li>An orphan refund gets a finding and no line.
 *   <li>Re-declaring a mismatched period reports the same gap once, not once per attempt.
 * </ul>
 */
@Testcontainers
@SpringBootTest
class SettlementServiceIntegrationTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_settlement_service");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The service publishes to Kafka as a side effect of closing and reconciling. Nothing here is
        // about the broker, and letting the first publish fail would turn an immutability test into a
        // connectivity test, so the relay is disabled and the outbox rows are read directly instead.
        registry.add("app.settlement.outbox.relay-enabled", () -> false);
    }

    @Autowired
    private SettlementService service;

    @Autowired
    private SettlementCycleRepository cycles;

    @Autowired
    private SettlementLineRepository lines;

    @Autowired
    private SettlementBreakRepository breaks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private Clock clock;

    // ------------------------------------------------------------------ helpers

    private TransactionMovementEvent capture(UUID payment, String amount, Instant occurredAt) {
        return new TransactionMovementEvent(
                payment, "sha256:irrelevant-here", amount, "GBP", "SETTLED", "Coffee", occurredAt, 1L);
    }

    private TransactionMovementEvent refund(UUID payment, String amount, Instant occurredAt) {
        return new TransactionMovementEvent(
                payment, "sha256:irrelevant-here", amount, "GBP", "REVERSED", "Coffee", occurredAt, 2L);
    }

    private Instant at(int hour, int dayOfMonth) {
        return LocalDate.of(2026, 3, dayOfMonth).atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    private long lineCount(UUID cycleId) {
        return transactions.execute(status ->
                jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE cycle_id = ?", Long.class, cycleId));
    }

    private long lineTotalMinor(UUID cycleId) {
        Long total = transactions.execute(status -> jdbc.queryForObject(
                "SELECT coalesce(sum(amount_minor), 0) FROM settlement_lines WHERE cycle_id = ?", Long.class, cycleId));
        return total == null ? 0L : total;
    }

    private String cycleStatusOf(UUID cycleId) {
        return transactions.execute(status ->
                jdbc.queryForObject("SELECT status FROM settlement_cycles WHERE id = ?", String.class, cycleId));
    }

    private List<String> breakKindsOf(UUID cycleId) {
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT kind FROM settlement_breaks WHERE cycle_id = ? ORDER BY kind", String.class, cycleId));
    }

    /**
     * The event types published on the finalising topic, in the order they were written.
     *
     * <p>Ordered rather than sorted, because the order is the assertion: a reporting consumer acts on the
     * sequence, and a cycle that announced "broken" before "closed" would be describing a period as both
     * still growing and already broken.
     *
     * @return the event types, oldest first
     */
    private List<String> finalisingEventTypes() {
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT event_type FROM outbox_events WHERE topic = 'settlement-cycle-finalised' "
                        + "ORDER BY created_at, id",
                String.class));
    }

    private long pendingOutbox() {
        Long count = transactions.execute(status ->
                jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class));
        return count == null ? 0L : count;
    }

    /** Clears every table, so each test starts from an empty ledger rather than inheriting the last one's. */
    private void resetLedger() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute(
                    "TRUNCATE settlement_breaks, settlement_lines, settlement_cycles, outbox_events, processed_events RESTART IDENTITY CASCADE");
        });
    }

    // ------------------------------------------------------------------ scenarios

    @Nested
    @DisplayName("a period that has been given out")
    class ClosedPeriod {

        @Test
        @DisplayName("refuses a late capture and records the money that moved without a line")
        void refusesLateCapture() {
            resetLedger();
            UUID payment = UUID.randomUUID();

            service.applyCapture(capture(payment, "500.00", at(9, 10)));
            String reference = cycleReferenceOf(2026, 3, 10);
            service.close(reference);

            long before = lineCount(cycleIdOf(reference));
            Money expected = expectedOf(reference);

            service.applyCapture(capture(UUID.randomUUID(), "250.00", at(9, 10)));

            // The period is byte-for-byte what it was when it was given out. This is the whole point of
            // closing it, and it is the assertion the in-memory domain test cannot make: the unique
            // constraint on (cycle_id, transaction_id, kind) is what actually stops the second write.
            assertThat(lineCount(cycleIdOf(reference))).isEqualTo(before);
            assertThat(lineTotalMinor(cycleIdOf(reference))).isEqualTo(expected.minorUnits());
            assertThat(expectedOf(reference)).isEqualTo(expected);
            assertThat(cycleStatusOf(cycleIdOf(reference))).isEqualTo(SettlementCycleStatus.CLOSED.name());

            // And the payment is not simply lost. It is the second half of the contract: a period that
            // was given out is now short, and somebody has to know that.
            assertThat(breakKindsOf(cycleIdOf(reference))).containsExactly(BreakKind.PERIOD_ALREADY_CLOSED.name());
        }

        @Test
        @DisplayName("keeps the stored total equal to its lines after a late capture is refused")
        void storedTotalStillMatchesItsLines() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "100.00", at(9, 11)));
            String reference = cycleReferenceOf(2026, 3, 11);
            service.close(reference);

            service.applyCapture(capture(UUID.randomUUID(), "40.00", at(23, 11)));

            // expected_minor is the frozen copy; the sum of the lines is the live one. They agreed at
            // close and must still agree, because a stored total that has drifted from the lines it claims
            // to summarise is a statement that reconciles against nothing and cannot be defended.
            long stored = transactions.execute(status -> jdbc.queryForObject(
                    "SELECT expected_minor FROM settlement_cycles WHERE reference = ?", Long.class, reference));
            assertThat(stored).isEqualTo(lineTotalMinor(cycleIdOf(reference)));
        }

        @Test
        @DisplayName("will not take the same payment twice, however many events describe it")
        void countsOnePaymentOnce() {
            resetLedger();
            UUID payment = UUID.randomUUID();

            // Three events, same payment, different event ids — the shape a producer replay or a
            // re-emitting migration produces. The consumer's event-id dedup does not catch it, because
            // these are genuinely different events, so the statement's own uniqueness has to.
            service.applyCapture(capture(payment, "75.00", at(9, 12)));
            service.applyCapture(capture(payment, "75.00", at(9, 12)));
            service.applyCapture(capture(payment, "75.00", at(9, 12)));

            assertThat(lineCount(cycleIdOf(cycleReferenceOf(2026, 3, 12)))).isEqualTo(1);
            assertThat(lineTotalMinor(cycleIdOf(cycleReferenceOf(2026, 3, 12)))).isEqualTo(7500L);
        }
    }

    @Nested
    @DisplayName("a refund")
    class Refund {

        @Test
        @DisplayName("lands as a negative line in its own period, with no break, when that day is still open")
        void ordinaryRefundNeedsNoBreak() {
            resetLedger();
            UUID payment = UUID.randomUUID();
            service.applyCapture(capture(payment, "500.00", at(9, 20)));

            // Refund the next day, before the capture's period has been closed. Both periods are still
            // counting and each will be reconciled against its own declared figure, so this is a refund,
            // not a finding. Keying the break on "settled in a different period" would have flagged every
            // ordinary overnight refund, and a break that fires on normal operation is a break nobody
            // reads.
            SettlementCycle refundDay = service.applyReversal(refund(payment, "500.00", at(9, 21)));

            assertThat(refundDay.getBusinessDate()).isEqualTo(LocalDate.of(2026, 3, 21));
            assertThat(lineCount(refundDay.getId())).isEqualTo(1);
            assertThat(lineTotalMinor(refundDay.getId())).isEqualTo(-50000L);
            assertThat(breakKindsOf(refundDay.getId())).isEmpty();
        }

        @Test
        @DisplayName("nets against nothing in its own period once the capture's period is given out")
        void refundAfterCloseCarriesAFinding() {
            resetLedger();
            UUID payment = UUID.randomUUID();
            service.applyCapture(capture(payment, "500.00", at(9, 22)));
            String captureDay = cycleReferenceOf(2026, 3, 22);
            service.close(captureDay);

            SettlementCycle refundDay = service.applyReversal(refund(payment, "500.00", at(9, 23)));

            // The refund is real money owed, so it is written — in the refund's own period, which is still
            // open. What it cannot do is net against the 500.00 the merchant was already shown, because
            // that period is final. The finding is the record of that gap.
            assertThat(lineCount(refundDay.getId())).isEqualTo(1);
            assertThat(lineTotalMinor(refundDay.getId())).isEqualTo(-50000L);
            assertThat(breakKindsOf(refundDay.getId())).containsExactly(BreakKind.PERIOD_ALREADY_CLOSED.name());

            // The break names the period it settled in, so the row is something somebody can act on
            // rather than a question. A break saying "this refund has no capture here" and one saying
            // "it settled in 2026-03-22, which you have already been given" are different amounts of
            // somebody's evening.
            String detail = breakDetailOf(refundDay.getId(), BreakKind.PERIOD_ALREADY_CLOSED);
            assertThat(detail).contains(captureDay);
        }

        @Test
        @DisplayName("is a finding and no line at all when this service never saw it settle")
        void orphanRefundGetsNoLine() {
            resetLedger();
            UUID payment = UUID.randomUUID();

            SettlementCycle refundDay = service.applyReversal(refund(payment, "120.00", at(9, 24)));

            assertThat(breakKindsOf(refundDay.getId())).containsExactly(BreakKind.ORPHAN_REVERSAL.name());
            // A refund with no capture nets against nothing, and a statement whose total is a refund
            // nobody initiated is not a statement. The finding carries the amount; the statement does not.
            assertThat(lineCount(refundDay.getId())).isZero();
        }

        @Test
        @DisplayName("is recorded rather than written when its own period is already given out")
        void refundIntoClosedPeriod() {
            resetLedger();
            // Both payments settle on the 25th and both are refunded on the 26th, so the 26th opens with a
            // real reversal line in it. Only after that does the 26th close, and only then does the second
            // refund arrive — which is the case under test. The earlier version of this test refunded a
            // payment that had never settled, so it hit the orphan branch first and asserted nothing about
            // the branch it was named for.
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            service.applyCapture(capture(first, "500.00", at(9, 25)));
            service.applyCapture(capture(second, "75.00", at(9, 25)));

            service.applyReversal(refund(first, "500.00", at(9, 26)));
            String refundDay = cycleReferenceOf(2026, 3, 26);
            service.close(refundDay);

            service.applyReversal(refund(second, "75.00", at(10, 26)));

            // The closed period keeps exactly the one line it was given out with.
            assertThat(breakKindsOf(cycleIdOf(refundDay))).containsExactly(BreakKind.PERIOD_ALREADY_CLOSED.name());
            assertThat(lineCount(cycleIdOf(refundDay))).isEqualTo(1);
            assertThat(lineTotalMinor(cycleIdOf(refundDay))).isEqualTo(-50000L);
        }
    }

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("reports a gap once, however many times the period is re-declared")
        void reDeclaringDoesNotMultiplyTheFinding() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "500.00", at(9, 27)));
            String reference = cycleReferenceOf(2026, 3, 27);
            service.close(reference);

            service.declareActual(reference, gbp("400.00"));
            long afterFirst = pendingOutbox();

            // A finding reported five times has not been reported five times, and an alert that fires once
            // per attempt is an alert people learn to ignore. Re-declaring is also a state conflict rather
            // than a 500: the period is already BROKEN and asking again is the caller's mistake.
            assertThatThrownBy(() -> service.declareActual(reference, gbp("400.00")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cannot declare an actual");

            assertThat(breakKindsOf(cycleIdOf(reference))).containsExactly(BreakKind.AMOUNT_MISMATCH.name());
            assertThat(pendingOutbox()).isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("stamps the break event with the cycle already marked broken, not still closed")
        void breakEventIsStampedAfterTheCycleBreaks() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "500.00", at(9, 28)));
            String reference = cycleReferenceOf(2026, 3, 28);
            service.close(reference);
            service.declareActual(reference, gbp("450.00"));

            // aggregate_version is the cycle's status as an ordinal, and it is the only place the
            // break event says anything about the cycle. A row stamped CLOSED describes a period that is
            // still clean, and a consumer that trusted it would treat a known 50.00 hole as agreed --
            // which is how a real BROKEN status stops being believed anywhere. The version is read from
            // the stored row rather than from the returned entity, because the returned entity is a
            // persistence-context copy and the row is what was committed.
            long stampedVersion = transactions.execute(tx -> jdbc.queryForObject(
                    "SELECT aggregate_version FROM outbox_events "
                            + "WHERE topic = 'settlement-break-detected' ORDER BY created_at DESC LIMIT 1",
                    Long.class));
            assertThat(stampedVersion).isEqualTo(SettlementCycleStatus.BROKEN.ordinal());

            // The body carries the break's own status, which is OPEN: a finding nobody has looked at yet.
            // The two are different facts and conflating them would lose whichever was not being reported.
            String breakStatus = transactions.execute(tx -> jdbc.queryForObject(
                    "SELECT payload::jsonb -> 'payload' ->> 'status' FROM outbox_events "
                            + "WHERE topic = 'settlement-break-detected' ORDER BY created_at DESC LIMIT 1",
                    String.class));
            assertThat(breakStatus).isEqualTo(BreakStatus.OPEN.name());
        }

        @Test
        @DisplayName("announces every outcome that stops a period changing, including the ones that do not reconcile")
        void everyFinalOutcomeIsAnnounced() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "500.00", at(9, 30)));
            String balanced = cycleReferenceOf(2026, 3, 30);
            service.close(balanced);
            service.declareActual(balanced, gbp("500.00"));
            service.reconcile(balanced);

            // A second period that will not reconcile, so the broken case is observed rather than
            // assumed: same currency, different business date, so the cycles do not share a reference
            // and the first one's state cannot be mistaken for the second's.
            service.applyCapture(capture(UUID.randomUUID(), "500.00", at(9, 28)));
            String broken = cycleReferenceOf(2026, 3, 28);
            service.close(broken);
            service.declareActual(broken, gbp("450.00"));

            // The topic is named for finalisation, and the three event types are the whole reason a
            // reporting consumer can stop watching a period. A closed-but-unreconciled cycle is the case
            // that earns it: that period is no longer growing but its money is not final either, and a
            // consumer not told about it reports a figure a day later that the platform then contradicts.
            assertThat(finalisingEventTypes())
                    .as("closed, reconciled and broken must all be announced on the finalising topic, or a "
                            + "reporting consumer keeps watching a period that will never change again -- or, "
                            + "worse, is never told that a period which did not reconcile is final")
                    .containsExactly(
                            "settlement.cycle.closed",
                            "settlement.cycle.reconciled",
                            "settlement.cycle.closed",
                            "settlement.cycle.broken");

            // The broken announcement carries the finding that caused it. "This period is 50.00 short"
            // is not actionable on its own, and a consumer that cannot read settlement-service's
            // database has nothing else to go on.
            String kind = transactions.execute(tx -> jdbc.queryForObject(
                    "SELECT payload::jsonb -> 'payload' ->> 'kind' FROM outbox_events "
                            + "WHERE topic = 'settlement-cycle-finalised' AND event_type = 'settlement.cycle.broken' "
                            + "ORDER BY created_at DESC LIMIT 1",
                    String.class));
            assertThat(kind).isEqualTo(BreakKind.AMOUNT_MISMATCH.name());
        }

        @Test
        @DisplayName("will not confirm a period that still has an open finding")
        void refusesToReconcileWithAnOpenBreak() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "500.00", at(9, 29)));
            String reference = cycleReferenceOf(2026, 3, 29);
            service.close(reference);
            service.declareActual(reference, gbp("450.00"));

            // Acknowledging is not resolving. Somebody has to have looked at the gap and recorded what
            // the money is doing before the period can be called final.
            SettlementBreak breakOne = breaks.findByCycleIdAndKind(cycleIdOf(reference), BreakKind.AMOUNT_MISMATCH)
                    .orElseThrow();
            service.acknowledgeBreak(breakOne.getId(), "operator-1");
            // An ApiException rather than a bare IllegalStateException: a refused confirmation is a
            // business outcome that a staff API should report as a 409 with a code, not a 500 with a
            // stack trace. Asserting the type here is how that stays true.
            assertThatThrownBy(() -> service.reconcile(reference))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("unresolved break");

            service.resolveBreak(breakOne.getId(), "bank charge of 50.00 found in the clearing file");
            // A BROKEN cycle is terminal by design — the statement is frozen and the gap is real, so
            // "reconciled" would be a lie. Resolution is the end of this cycle's life, not a step on the
            // way back to CLOSED.
            assertThatThrownBy(() -> service.reconcile(reference))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cannot reconcile");
            assertThat(breakStatusOf(breakOne.getId())).isEqualTo(BreakStatus.RESOLVED.name());
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("reports closing a period twice as a conflict, not a server fault")
        void closingTwiceIsAConflict() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "10.00", at(9, 30)));
            String reference = cycleReferenceOf(2026, 3, 30);
            service.close(reference);

            // 500 would say the platform is broken. What actually happened is that the caller asked for
            // something the period's state forbids, and the colleague who clicks the same button next
            // will get the same answer. A conflict with a code is the honest shape for that.
            assertThatThrownBy(() -> service.close(reference))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cannot close");
        }

        @Test
        @DisplayName("reports resolving a break nobody looked at, and says what to do about it")
        void resolutionRequiresAcknowledgement() {
            resetLedger();
            service.applyCapture(capture(UUID.randomUUID(), "10.00", at(9, 31)));
            String reference = cycleReferenceOf(2026, 3, 31);
            service.close(reference);
            service.declareActual(reference, gbp("5.00"));
            SettlementBreak found = breaks.findByCycleIdAndKind(cycleIdOf(reference), BreakKind.AMOUNT_MISMATCH)
                    .orElseThrow();

            // Its own code, not the generic state conflict: the caller's next move is different. A generic
            // "not in a state that allows this change" teaches the caller nothing and they will try the
            // same request again.
            assertThatThrownBy(() -> service.resolveBreak(found.getId(), "looked at it"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("acknowledged");
        }
    }

    // ------------------------------------------------------------------ small reads

    private Money gbp(String decimal) {
        return Money.parse(decimal, GBP);
    }

    private String cycleReferenceOf(int year, int month, int day) {
        return transactions.execute(status -> jdbc.queryForObject(
                "SELECT reference FROM settlement_cycles WHERE business_date = ? AND currency_code = 'GBP'",
                String.class,
                LocalDate.of(year, month, day)));
    }

    private UUID cycleIdOf(String reference) {
        return transactions.execute(status ->
                jdbc.queryForObject("SELECT id FROM settlement_cycles WHERE reference = ?", UUID.class, reference));
    }

    private Money expectedOf(String reference) {
        return transactions.execute(status -> {
            long minor = jdbc.queryForObject(
                    "SELECT expected_minor FROM settlement_cycles WHERE reference = ?", Long.class, reference);
            return new Money(minor, GBP);
        });
    }

    private String breakDetailOf(UUID cycleId, BreakKind kind) {
        return transactions.execute(status -> jdbc.queryForObject(
                "SELECT detail FROM settlement_breaks WHERE cycle_id = ? AND kind = ?",
                String.class,
                cycleId,
                kind.name()));
    }

    private String breakStatusOf(UUID breakId) {
        return transactions.execute(status ->
                jdbc.queryForObject("SELECT status FROM settlement_breaks WHERE id = ?", String.class, breakId));
    }
}
