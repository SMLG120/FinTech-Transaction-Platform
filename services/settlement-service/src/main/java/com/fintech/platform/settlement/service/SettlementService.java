package com.fintech.platform.settlement.service;

import com.fintech.platform.common.event.KafkaTopics;
import com.fintech.platform.settlement.domain.BreakStatus;
import com.fintech.platform.settlement.domain.Money;
import com.fintech.platform.settlement.domain.SettlementBreak;
import com.fintech.platform.settlement.domain.SettlementCycle;
import com.fintech.platform.settlement.domain.SettlementCycleStatus;
import com.fintech.platform.settlement.domain.SettlementLine;
import com.fintech.platform.settlement.domain.SettlementLineKind;
import com.fintech.platform.settlement.error.SettlementErrors;
import com.fintech.platform.settlement.messaging.TransactionMovementEvent;
import com.fintech.platform.settlement.persistence.ProcessedEventRepository;
import com.fintech.platform.settlement.persistence.SettlementBreakRepository;
import com.fintech.platform.settlement.persistence.SettlementCycleRepository;
import com.fintech.platform.settlement.persistence.SettlementLineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds settlement cycles from consumed transaction events, and reconciles them.
 *
 * <p>Everything this service knows about a payment arrives as an event. It never reads transaction-service's
 * tables, which is the decision ADR-0009 argues for and the reason a settlement bug cannot become a
 * payments outage.
 *
 * <p>The interesting method is {@link #applyReversal}, and it exists because of a specific problem: a
 * payment settles in one period, the period closes and is given to the merchant, and then the customer
 * charges back and the money comes back later. The refund is real money owed to a customer, so it cannot
 * be refused; and the first period's statement has already been seen, so it cannot be edited. The only
 * answer that satisfies both is to carry the refund as a negative line in the period covering the
 * <em>refund's</em> business date, leaving the closed period exactly as it was, and to record the resulting
 * mismatch as a finding.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementCycleRepository cycles;

    private final SettlementLineRepository lines;

    private final SettlementBreakRepository breaks;

    private final ProcessedEventRepository processedEvents;

    private final OutboxWriter outbox;

    private final Clock clock;

    public SettlementService(
            SettlementCycleRepository cycles,
            SettlementLineRepository lines,
            SettlementBreakRepository breaks,
            ProcessedEventRepository processedEvents,
            OutboxWriter outbox,
            Clock clock) {
        this.cycles = cycles;
        this.lines = lines;
        this.breaks = breaks;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Claims an event id and applies it, in one transaction, or reports that it was a redelivery.
     *
     * <p>The claim is an {@code INSERT ... ON CONFLICT DO NOTHING} and its return value is the only thing
     * that decides whether to act. A read-then-write has a window between the two statements in which a
     * second consumer sees no row and applies the same event again, and the symptom of that is a statement
     * that counts one payment twice — a cycle that will not reconcile against anything, discovered days
     * later by somebody comparing two figures.
     *
     * <p>Claim first, then apply, in the same transaction: a failure after the claim rolls the claim back
     * and the event is retried whole. The reverse order — mark processed, then fail — would silently lose
     * a payment from its statement, which is the worse of the two.
     *
     * @param eventId the envelope's event id
     * @param topic the topic it arrived on
     * @param apply what to do if this delivery is the one that gets there
     * @return true if the event was claimed and applied
     */
    @Transactional
    public boolean claimAndApply(UUID eventId, String topic, Runnable apply) {
        if (processedEvents.claim(eventId, topic, clock.instant()) == 0) {
            return false;
        }
        apply.run();
        return true;
    }

    // ------------------------------------------------------------------ consumption

    /**
     * Records a capture.
     *
     * <p>Opens the cycle for the payment's business date if it is not already open, then adds the line.
     * Idempotency is the consumer's job — it claims the event id first — because dedup that lives inside
     * the write is dedup that a second code path can forget.
     *
     * @param event the {@code transaction-settled} payload
     * @return the cycle the line landed in
     */
    @Transactional
    public SettlementCycle applyCapture(TransactionMovementEvent event) {
        Instant now = clock.instant();
        Money amount = Money.parse(event.amount(), event.currencyObject());
        SettlementCycle cycle = openCycle(event.businessDate(), event.currencyObject(), now);

        if (lines.existsByTransactionIdAndKind(event.transactionId(), SettlementLineKind.CAPTURE)) {
            // Reachable even with event-id dedup: a different event id for the same fact, from a producer
            // replay or a migration that re-emits. Colliding on the statement's own uniqueness is the
            // backstop that stops a statement counting one payment twice.
            log.info(
                    "capture for transaction {} already has a line; not adding another to {}",
                    event.transactionId(),
                    cycle.getReference());
            return cycle;
        }

        if (!cycle.isOpen()) {
            // The period closed in the same instant this payment settled, which is an ordinary consequence
            // of a batch boundary rather than a fault. What is not acceptable is losing the payment quietly,
            // so it becomes a break. Recorded rather than thrown because throwing would park the event on
            // the dead-letter topic, where it is a message about parsing, and the actual problem is that a
            // period somebody was already given is now short — which is a finance question, not a
            // consumer's. A cycle cannot be reopened, so the payment has to be accounted for by hand.
            recordBreak(
                    cycle,
                    SettlementBreak.closedPeriodMovement(cycle, event.transactionId(), amount, "capture", null, now),
                    now);
            log.warn(
                    "capture of {} for transaction {} arrived after cycle {} closed; recorded as a break because "
                            + "a closed statement cannot take another line",
                    amount,
                    event.transactionId(),
                    cycle.getReference());
            return cycle;
        }

        lines.save(SettlementLine.capture(cycle, event.transactionId(), amount, now));
        log.info("captured {} into cycle {}", amount, cycle.getReference());
        return cycle;
    }

    /**
     * Records a refund, wherever it belongs.
     *
     * <p>Three cases, each decided by a fact about the database rather than by the event, because the
     * event cannot know what this service has already seen:
     *
     * <ul>
     *   <li><b>No capture in any cycle.</b> An orphan. Recorded as a break against the cycle for the
     *       refund's business date, with no line written, because a statement that nets a refund against
     *       nothing has a total nobody can explain.
     *   <li><b>The refund's own cycle is closed.</b> Also a break, and again no line. The money moved and
     *       no statement will show it, which is exactly what a break is for; writing a line would mean
     *       reopening a period somebody was already given.
     *   <li><b>Otherwise.</b> A negative line in the refund's own cycle. If the payment settled in a cycle
     *       that has since been given out, a {@code PERIOD_ALREADY_CLOSED} finding is recorded as well —
     *       the line is written, because the refund is owed, but a refund landing in a different period
     *       than its capture nets against a statement somebody already has, and that is a reconciliation
     *       item in its own right.
     * </ul>
     *
     * <p>Note what the last case is <em>not</em> keyed on. It is tempting to treat "settled in a different
     * period" as the trigger, and that is wrong: a capture yesterday and a refund today, with yesterday's
     * cycle still open, is an ordinary refund and needs no break. Both periods are still counting, and
     * each will be reconciled against its own declared figure. The trigger is whether the capture's
     * period is <em>final</em> — a difference that is only interesting once the period can no longer
     * absorb it.
     *
     * @param event the {@code transaction-reversed} payload
     * @return the cycle the outcome was recorded against
     */
    @Transactional
    public SettlementCycle applyReversal(TransactionMovementEvent event) {
        Instant now = clock.instant();
        Money amount = Money.parse(event.amount(), event.currencyObject()).negate();
        LocalDate businessDate = event.businessDate();

        if (!lines.existsByTransactionIdAndKind(event.transactionId(), SettlementLineKind.CAPTURE)) {
            SettlementCycle orphanCycle = openCycle(businessDate, event.currencyObject(), now);
            recordBreak(
                    orphanCycle, SettlementBreak.orphanReversal(orphanCycle, event.transactionId(), amount, now), now);
            log.warn(
                    "reversal for transaction {} has no capture in any cycle; recorded as a break on {}",
                    event.transactionId(),
                    orphanCycle.getReference());
            return orphanCycle;
        }

        SettlementCycle captureCycle = settledCycleOf(event.transactionId()).orElse(null);
        String settledIn = captureCycle == null ? null : captureCycle.getReference();

        SettlementCycle cycle = openCycle(businessDate, event.currencyObject(), now);
        if (!cycle.isOpen()) {
            recordBreak(
                    cycle,
                    SettlementBreak.closedPeriodMovement(
                            cycle, event.transactionId(), amount, "refund", settledIn, now),
                    now);
            log.warn(
                    "cycle {} is already given out and cannot take the refund of transaction {}; recorded as a "
                            + "break rather than reopening a period somebody was already given",
                    cycle.getReference(),
                    event.transactionId());
            return cycle;
        }

        lines.save(SettlementLine.reversal(cycle, event.transactionId(), amount, now));

        if (captureCycle != null && !captureCycle.isOpen()) {
            recordBreak(
                    cycle,
                    SettlementBreak.closedPeriodMovement(
                            cycle, event.transactionId(), amount, "refund", settledIn, now),
                    now);
            log.info(
                    "refund of transaction {} carried into {}, where it nets against no line, because the {} it "
                            + "settled in is already given out",
                    event.transactionId(),
                    cycle.getReference(),
                    settledIn);
        } else {
            log.info("reversed {} into cycle {}", amount, cycle.getReference());
        }
        return cycle;
    }

    // ------------------------------------------------------------------ closing and reconciling

    /**
     * Closes a cycle, freezing its lines and its expected total.
     *
     * <p>Closing is the moment a period's contents become a fact. Nothing after this call can change the
     * lines or the total, and a movement arriving later belongs to the period covering its own business
     * date instead.
     *
     * <p>Publishes {@code settlement.cycle.closed} here, while the cycle is closing, because "we have
     * finished counting" is itself something a consumer needs to know: it is the point at which reporting
     * stops watching a period grow. The period's money is not final yet — see {@link #reconcile} and
     * {@link #declareActual} — and the event type says so, so a consumer cannot mistake a closed period
     * for a settled one.
     *
     * @param reference the cycle's reference
     * @return the closed cycle
     */
    @Transactional
    public SettlementCycle close(String reference) {
        Instant now = clock.instant();
        SettlementCycle cycle = requireCycle(reference);
        Money expected = totalOf(cycle);
        refusingAsClosedState("cannot close " + reference, () -> cycle.close(expected, now));
        outbox.record(
                "SettlementCycle",
                cycle.getId(),
                cycle.getStatus().ordinal(),
                KafkaTopics.SETTLEMENT_CYCLE_FINALISED,
                "settlement.cycle.closed",
                cycleClosedPayload(cycle, expected),
                now);
        log.info("closed cycle {} with expected total {}", cycle.getReference(), expected);
        return cycle;
    }

    /**
     * Records the independently declared actual for a period and compares it with the expected total.
     *
     * <p>A difference is a break, and the cycle becomes {@code BROKEN} in the same act that discovered it.
     * Not an exception and not a deferral: the money has already moved, so the platform's job is to record
     * the gap accurately and escalate it. A declared actual that matches leaves the cycle {@code CLOSED},
     * ready for {@link #reconcile} to confirm.
     *
     * @param reference the cycle's reference
     * @param actual the declared clearing figure
     * @return the cycle, now carrying both figures and the difference
     */
    @Transactional
    public SettlementCycle declareActual(String reference, Money actual) {
        Instant now = clock.instant();
        SettlementCycle cycle = requireCycle(reference);
        Money difference = refusingAsClosedState(
                "cannot declare an actual for " + reference, () -> cycle.declareActual(actual, now));
        if (difference.isZero()) {
            log.info("declared actual {} for {} matches the expected total", actual, cycle.getReference());
            return cycle;
        }
        // Break the cycle first, then record the finding. recordBreak writes the outbox row, and that
        // row carries the cycle's status: a break event describing a cycle as still CLOSED is a thing
        // downstream has to know to distrust, because the whole point of the event is that the period
        // has stopped being clean.
        refusingAsClosedState("cannot break " + reference, () -> cycle.breakWith(now));
        recordBreak(cycle, SettlementBreak.amountMismatch(cycle, cycle.expected(), actual, now), now);
        log.warn(
                "cycle {} does not balance: expected {}, declared {}, difference {}; recorded as a break",
                cycle.getReference(),
                cycle.expected(),
                actual,
                difference);
        return cycle;
    }

    /**
     * Confirms a cycle whose declared actual matched.
     *
     * <p>Refuses a cycle that is not closed, has no declared actual, still carries an unresolved break, or
     * does not balance. The break check is re-read inside this transaction rather than trusted from the
     * caller's view, because acknowledging a break is not resolving it, and a period whose difference is
     * still open is a period whose money has not been accounted for.
     *
     * @param reference the cycle's reference
     * @return the reconciled cycle
     */
    @Transactional
    public SettlementCycle reconcile(String reference) {
        Instant now = clock.instant();
        SettlementCycle cycle = requireCycle(reference);
        if (breaks.existsByCycleIdAndStatusNot(cycle.getId(), BreakStatus.RESOLVED)) {
            throw SettlementErrors.CYCLE_NOT_OPEN.exception("cannot reconcile " + cycle.getReference()
                    + ": it still has an unresolved break. Acknowledging a break is not resolving it — somebody "
                    + "has to have looked at it and recorded what the money is doing before the period can be "
                    + "called final.");
        }
        refusingAsClosedState("cannot reconcile " + reference, () -> cycle.reconcile(now));
        outbox.record(
                "SettlementCycle",
                cycle.getId(),
                cycle.getStatus().ordinal(),
                KafkaTopics.SETTLEMENT_CYCLE_FINALISED,
                "settlement.cycle.reconciled",
                cycleReconciledPayload(cycle),
                now);
        log.info("reconciled cycle {}", cycle.getReference());
        return cycle;
    }

    // ------------------------------------------------------------------ breaks

    /**
     * Records that somebody has seen a break.
     *
     * <p>Does not resolve it. See {@link SettlementBreak#acknowledge} for why the two are separate.
     *
     * @param breakId the break's id
     * @param actor who is acknowledging it
     * @return the acknowledged break
     */
    @Transactional
    public SettlementBreak acknowledgeBreak(UUID breakId, String actor) {
        SettlementBreak found = requireBreak(breakId);
        refusingAsClosedState("cannot acknowledge break " + breakId, () -> found.acknowledge(actor, clock.instant()));
        log.info("break {} on {} acknowledged by {}", breakId, found.getCycle().getReference(), actor);
        return found;
    }

    /**
     * Records that the money is accounted for, and why.
     *
     * @param breakId the break's id
     * @param explanation what was found and what was done
     * @return the resolved break
     */
    @Transactional
    public SettlementBreak resolveBreak(UUID breakId, String explanation) {
        SettlementBreak found = requireBreak(breakId);
        // Resolution before acknowledgement gets its own code rather than the generic state conflict,
        // because the fix is different: somebody has to go and look at the break. A caller who sees
        // "not in a state that allows this change" has learned nothing and will try the same thing again.
        try {
            found.resolve(explanation, clock.instant());
        } catch (IllegalStateException e) {
            throw SettlementErrors.BREAK_NOT_ACKNOWLEDGED.exception(e.getMessage());
        }
        log.info("break {} on {} resolved", breakId, found.getCycle().getReference());
        return found;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The sum of a cycle's lines, as money.
     *
     * <p>Read from the lines rather than from {@code expected_minor} because this runs <em>before</em> the
     * cycle closes, when the stored total is still the initial zero. The stored total is the frozen copy
     * and this is the live sum; two implementations of the same figure is a duplication, and it is
     * deliberate, because one can be wrong right up to the moment it is frozen and the other is the frozen
     * value itself.
     *
     * @param cycle the cycle
     * @return the total of its lines
     */
    @Transactional(readOnly = true)
    public Money totalOf(SettlementCycle cycle) {
        Money total = new Money(0L, cycle.currency());
        for (SettlementLine line : lines.findByCycleIdOrderByCreatedAtAsc(cycle.getId())) {
            total = total.add(line.amount());
        }
        return total;
    }

    /**
     * A cycle by reference.
     *
     * @param reference the reference
     * @return the cycle
     */
    @Transactional(readOnly = true)
    public SettlementCycle requireCycle(String reference) {
        return cycles.findByReference(reference)
                .orElseThrow(() ->
                        SettlementErrors.CYCLE_NOT_FOUND.exception("No settlement cycle with reference " + reference));
    }

    /**
     * Runs a state transition, reporting a refusal as a settlement conflict rather than as a server fault.
     *
     * <p>The domain throws {@link IllegalStateException} for every rule it enforces, and those messages are
     * written to be read by whoever tripped the rule. What they are not is HTTP: with no handler for that
     * type, "you already closed this period" arrives at the client as a 500 with a stack trace, which says
     * the platform is broken when in fact the caller asked for something impossible and a colleague
     * clicking the same button will get the same answer.
     *
     * <p>Done here rather than by teaching the shared {@code ApiExceptionHandler} about
     * {@code IllegalStateException}, because that would apply to every service: an unexpected
     * {@code IllegalStateException} elsewhere usually <em>is</em> a bug, and 500 is the honest answer for
     * it. Only the settlement service knows which of its state errors are ordinary business outcomes.
     *
     * @param context what was being attempted, for the caller's error message
     * @param transition the domain call that enforces the rule
     */
    private void refusingAsClosedState(String context, Runnable transition) {
        refusingAsClosedState(context, () -> {
            transition.run();
            return null;
        });
    }

    /**
     * The value-returning form, for transitions whose result is the thing being computed rather than a
     * side effect. Declaring an actual both changes the cycle and yields the difference that decides
     * whether it broke, so it cannot be expressed as a {@link Runnable} without discarding the half the
     * caller needs.
     *
     * @param context what was being attempted
     * @param transition the domain call that enforces the rule
     * @param <T> what the transition returns
     * @return whatever the transition returned
     */
    private <T> T refusingAsClosedState(String context, java.util.function.Supplier<T> transition) {
        try {
            return transition.get();
        } catch (IllegalStateException e) {
            throw SettlementErrors.CYCLE_NOT_OPEN.exception(context + ": " + e.getMessage());
        }
    }

    private SettlementBreak requireBreak(UUID breakId) {
        return breaks.findById(breakId)
                .orElseThrow(
                        () -> SettlementErrors.CYCLE_NOT_FOUND.exception("No settlement break with id " + breakId));
    }

    // ------------------------------------------------------------------ internals

    /**
     * The cycle for a business date and currency, opening it if it does not exist.
     *
     * <p>The insert can still lose a race against another consumer, which is what the unique index on
     * {@code (business_date, currency_code)} is for. The loser re-reads rather than failing, because two
     * consumers legitimately posting money into the same day is the normal case and not an error worth
     * failing a payment's settlement for.
     *
     * @param businessDate the day
     * @param currency the currency
     * @param now the current instant
     * @return the cycle for that day and currency
     */
    private SettlementCycle openCycle(LocalDate businessDate, Currency currency, Instant now) {
        return cycles.findByBusinessDateAndCurrencyCode(businessDate, currency.getCurrencyCode())
                .orElseGet(() -> {
                    try {
                        return cycles.saveAndFlush(SettlementCycle.open(businessDate, currency, now));
                    } catch (DataIntegrityViolationException e) {
                        log.debug(
                                "cycle for {} {} was opened concurrently; using the existing one",
                                businessDate,
                                currency.getCurrencyCode());
                        return cycles.findByBusinessDateAndCurrencyCode(businessDate, currency.getCurrencyCode())
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * Persists a break, unless the cycle already has one of that kind, and announces it.
     *
     * <p>Re-reconciling a period must not multiply the finding. A reconciliation that reports the same
     * missing money five times has not reported it five times, and an alert that fires once per attempt is
     * an alert people learn to ignore.
     *
     * @param cycle the cycle the break belongs to
     * @param candidate the break to record
     * @param now the current instant
     */
    private void recordBreak(SettlementCycle cycle, SettlementBreak candidate, Instant now) {
        boolean already =
                breaks.findByCycleIdAndKind(cycle.getId(), candidate.getKind()).isPresent();
        if (already) {
            log.info("cycle {} already has a {} break; not adding a second", cycle.getReference(), candidate.getKind());
            return;
        }
        SettlementBreak saved = breaks.save(candidate);
        outbox.record(
                "SettlementCycle",
                cycle.getId(),
                cycle.getStatus().ordinal(),
                KafkaTopics.SETTLEMENT_BREAK_DETECTED,
                "settlement.break.detected",
                breakPayload(saved, cycle),
                now);
        // Only a break that actually moves the cycle announces the transition. A finding against a cycle
        // that is still CLOSED — a mismatch is recorded before the caller breaks it in one path, a late
        // movement breaks it before recording in another — leaves the period reconcilable, and a second
        // announcement of "this will never finalise" for a period that still might would be a lie that a
        // reporting consumer has to be taught to ignore.
        if (cycle.getStatus() == SettlementCycleStatus.BROKEN) {
            outbox.record(
                    "SettlementCycle",
                    cycle.getId(),
                    cycle.getStatus().ordinal(),
                    KafkaTopics.SETTLEMENT_CYCLE_FINALISED,
                    "settlement.cycle.broken",
                    cycleBrokenPayload(cycle, saved),
                    now);
        }
        log.warn("recorded {} break on cycle {}", candidate.getKind(), cycle.getReference());
    }

    /**
     * The cycle a payment's capture landed in.
     *
     * <p>Returns the cycle rather than just its reference because the reference alone cannot answer the
     * question {@link #applyReversal} asks of it. "Which period did this settle in" is what a break's
     * detail text needs to name; "is that period still counting" is what decides whether the refund is
     * ordinary or a finding. Handing back a String here would force the caller to load the cycle again
     * to ask the second question, and to write the first one in a way that invites using it for both.
     *
     * @param transactionId the payment
     * @return the cycle the capture is in, or empty
     */
    private Optional<SettlementCycle> settledCycleOf(UUID transactionId) {
        return lines.findByTransactionIdAndKind(transactionId, SettlementLineKind.CAPTURE)
                .map(line -> line.getCycle());
    }

    private Map<String, Object> cycleClosedPayload(SettlementCycle cycle, Money expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleId", cycle.getId().toString());
        body.put("reference", cycle.getReference());
        body.put("businessDate", cycle.getBusinessDate().toString());
        body.put("currency", cycle.getCurrencyCode());
        body.put("expected", expected.toDecimal());
        body.put("status", cycle.getStatus().name());
        return body;
    }

    private Map<String, Object> cycleReconciledPayload(SettlementCycle cycle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleId", cycle.getId().toString());
        body.put("reference", cycle.getReference());
        body.put("businessDate", cycle.getBusinessDate().toString());
        body.put("currency", cycle.getCurrencyCode());
        body.put("expected", cycle.expected().toDecimal());
        body.put("actual", cycle.actual() == null ? "" : cycle.actual().toDecimal());
        body.put("status", cycle.getStatus().name());
        return body;
    }

    /**
     * The body a reporting consumer gets when a period will never reconcile.
     *
     * <p>Carries the break that caused it, not just the difference, because "this period is 1.00 short"
     * is not actionable on its own and "this period is 1.00 short because a payment settled after the
     * close" is. The expected and declared figures are both here so the consumer does not have to join
     * back to a settlement database it is deliberately not allowed to read.
     *
     * @param cycle the cycle that has just broken
     * @param cause the break that moved it out of CLOSED
     * @return the payload body
     */
    private Map<String, Object> cycleBrokenPayload(SettlementCycle cycle, SettlementBreak cause) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleId", cycle.getId().toString());
        body.put("reference", cycle.getReference());
        body.put("businessDate", cycle.getBusinessDate().toString());
        body.put("currency", cycle.getCurrencyCode());
        body.put("expected", cycle.expected().toDecimal());
        body.put("actual", cycle.actual() == null ? "" : cycle.actual().toDecimal());
        body.put("status", cycle.getStatus().name());
        body.put("breakId", cause.getId().toString());
        body.put("kind", cause.getKind().name());
        body.put("detail", cause.getDetail());
        return body;
    }

    private Map<String, Object> breakPayload(SettlementBreak found, SettlementCycle cycle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakId", found.getId().toString());
        body.put("cycleId", cycle.getId().toString());
        body.put("reference", cycle.getReference());
        body.put("businessDate", cycle.getBusinessDate().toString());
        body.put("currency", cycle.getCurrencyCode());
        body.put("kind", found.getKind().name());
        body.put("difference", found.getDifferenceMinor());
        body.put("detail", found.getDetail());
        body.put("status", found.getStatus().name());
        return body;
    }
}
